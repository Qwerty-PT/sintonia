// Testa os leitores do desktop contra os mesmos exemplos que o APK (spec/fixtures).
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import sax from "sax";
import { parse, epgUrls } from "../src/core/m3u.mjs";
import { createCollector, parseTime } from "../src/core/epg.mjs";

const spec = join(dirname(fileURLToPath(import.meta.url)), "../../spec/fixtures");
const read = (p) => readFileSync(join(spec, p), "utf8");
const json = (p) => JSON.parse(read(p));

test("m3u/basico", () => {
  const text = read("m3u/basico.m3u");
  const exp = json("m3u/basico.expected.json");
  assert.deepEqual(epgUrls(text), exp.epgUrls);
  const got = parse(text).map((c) => ({
    name: c.name, group: c.group, url: c.url, tvgId: c.tvgId, logo: c.logo, isRadio: c.isRadio,
    userAgent: c.userAgent, headers: c.headers, drmType: c.drmType, drmKey: c.drmKey, key: c.key,
  }));
  assert.deepEqual(got, exp.channels);
});

test("epg/basico", () => {
  const exp = json("epg/basico.expected.json");
  const { input } = exp;
  const col = createCollector({
    wantedIds: input.wantedIds, wantedNames: input.wantedNames,
    from: parseTime(input.from), to: parseTime(input.to),
  });
  const p = sax.parser(true);
  p.onopentag = col.handlers.opentag;
  p.ontext = col.handlers.text;
  p.oncdata = col.handlers.cdata;
  p.onclosetag = col.handlers.closetag;
  p.write(read("epg/basico.xml")).close();
  const r = col.result();
  assert.deepEqual(r.nameToId, exp.nameToId);
  assert.deepEqual(r.programmes, exp.programmes);
});

test("lista real M3UPT (se M3U_FILE estiver definido)", { skip: !process.env.M3U_FILE }, () => {
  const list = parse(readFileSync(process.env.M3U_FILE, "utf8"));
  console.log(`canais: ${list.length}`);
  assert.ok(list.length > 100);
});
