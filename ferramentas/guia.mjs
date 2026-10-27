// Prepara o guia (EPG) da lista M3UPT num ficheiro pequeno para o Sintonia.
// Corre no GitHub Actions (guia.yml): a TV descarrega ~100 KB já filtrados em vez de ler um XMLTV de dezenas
// de MB — muito mais rápido numa Fire TV Stick e poupa dados.
//
// Usa os mesmos leitores do desktop (testados contra spec/), por isso as regras são as do APK.
// Saída: TSV em gzip, o mesmo formato da cache do APK:
//   S <tab> fontes do guia (url-tvg)            ← identifica de onde veio
//   N <tab> nome do canal (minúsculas) <tab> id  ← canais ligados pelo nome
//   P <tab> id <tab> início ms <tab> fim ms <tab> título <tab> descrição
//
// Uso: node ferramentas/guia.mjs <url da lista> <ficheiro de saída .tsv.gz>
import { writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import zlib from "node:zlib";

const require = createRequire(new URL("../desktop/package.json", import.meta.url));
const sax = require("sax");
const xzPkg = require("xz-decompress");
const { parse, epgUrls } = await import("../desktop/src/core/m3u.mjs");
const { createCollector } = await import("../desktop/src/core/epg.mjs");

const [listUrl, outFile] = process.argv.slice(2);
if (!listUrl || !outFile) { console.error("uso: node guia.mjs <lista.m3u> <saida.tsv.gz>"); process.exit(2); }

const HOUR = 3_600_000;
const UA = "Mozilla/5.0 (Sintonia guia)";

const fetchBuf = async (u) => {
  const r = await fetch(u, { headers: { "User-Agent": UA }, redirect: "follow" });
  if (!r.ok) throw new Error(`${u}: ${r.status}`);
  return Buffer.from(await r.arrayBuffer());
};

const text = (await fetchBuf(listUrl)).toString("utf8");
const channels = parse(text);
const urls = epgUrls(text);
if (!channels.length || !urls.length) throw new Error("lista sem canais ou sem url-tvg");

const now = Date.now();
const from = now - 2 * HOUR, to = now + 60 * HOUR; // gerado de 6 em 6 h: cobre sempre ≥ 48 h à frente
const ids = [...new Set(channels.map((c) => c.tvgId).filter(Boolean))];
const names = [...new Set(channels.map((c) => c.name.toLowerCase()))];

const programmes = {};
const nameToId = {};
for (const u of urls) {
  try {
    const buf = await fetchBuf(u);
    const col = createCollector({ wantedIds: ids, wantedNames: names, from, to });
    const p = sax.parser(false, { lowercase: true });
    Object.assign(p, {
      onopentag: col.handlers.opentag, ontext: col.handlers.text, oncdata: col.handlers.cdata,
      onclosetag: col.handlers.closetag, onerror() { p.error = null; p.resume(); },
    });
    const dec = new TextDecoder("utf-8");
    const isXz = buf[0] === 0xfd && buf.toString("latin1", 1, 5) === "7zXZ";
    const isGz = buf[0] === 0x1f && buf[1] === 0x8b;
    if (isXz) {
      for await (const chunk of new xzPkg.XzReadableStream(new Blob([buf]).stream())) p.write(dec.decode(chunk, { stream: true }));
    } else {
      const data = isGz ? zlib.gunzipSync(buf) : buf;
      for (let i = 0; i < data.length; i += 1 << 20) p.write(dec.decode(data.subarray(i, i + (1 << 20)), { stream: true }));
    }
    p.write(dec.decode()).close();
    const r = col.result();
    for (const [k, v] of Object.entries(r.programmes)) if (!(k in programmes)) programmes[k] = v;
    for (const [k, v] of Object.entries(r.nameToId)) if (!(k in nameToId)) nameToId[k] = v;
    console.log(`ok ${u}: ${Object.keys(r.programmes).length} canais`);
  } catch (e) {
    console.warn(`falhou ${u}: ${e.message}`);
  }
}

const clean = (s) => (s || "").replace(/[\t\r\n]+/g, " ");
const lines = [`S\t${urls.join(",")}`];
for (const [n, id] of Object.entries(nameToId)) lines.push(`N\t${clean(n)}\t${clean(id)}`);
let count = 0;
for (const [id, list] of Object.entries(programmes)) {
  for (const p of list) { lines.push(`P\t${clean(id)}\t${p.start}\t${p.stop}\t${clean(p.title)}\t${clean(p.desc)}`); count++; }
}
if (!Object.keys(programmes).length) throw new Error("guia vazio — não publicar");
const out = zlib.gzipSync(Buffer.from(lines.join("\n") + "\n", "utf8"), { level: 9 });
writeFileSync(outFile, out);
console.log(`guia: ${Object.keys(programmes).length} canais, ${count} programas, ${(out.length / 1024).toFixed(0)} KB`);
