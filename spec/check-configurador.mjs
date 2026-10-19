// Verifica o leitor do ficheiro de listas do configurador (JS) contra spec/fixtures/listas.
// Uso: node spec/check-configurador.mjs
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const html = readFileSync(join(here, "../ferramentas/configurador-listas.html"), "utf8");
const m = html.match(/\/\/ spec:listas-parse-start[^\n]*\n([\s\S]*?)\/\/ spec:listas-parse-end/);
if (!m) { console.error("Marcadores spec:listas-parse não encontrados"); process.exit(1); }
const isUrl = (s) => /^https?:\/\/\S+$/i.test(s.trim());
const parse = new Function("isUrl", `${m[1]}; return parse;`)(isUrl);

const text = readFileSync(join(here, "fixtures/listas/exemplo.txt"), "utf8");
const expected = JSON.parse(readFileSync(join(here, "fixtures/listas/exemplo.expected.json"), "utf8"));
const { groups, ignored } = parse(text);
const got = {
  groups: groups.map((g) => ({ code: g.code, vpnFile: g.code ? g.code.toLowerCase() + ".conf" : null, items: g.items })),
  ignoredLines: ignored,
};
const a = JSON.stringify(got), b = JSON.stringify(expected);
if (a !== b) { console.error("FALHOU\nobtido:  " + a + "\nesperado: " + b); process.exit(1); }
console.log("OK listas/exemplo");
