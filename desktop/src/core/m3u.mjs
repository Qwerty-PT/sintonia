// Leitor de listas M3U — mesmas regras que o APK (spec/FORMATOS.md §1).
// Usado no ecrã (renderer) e nos testes (Node).

const ATTR = /([A-Za-z0-9_-]+)="([^"]*)"/g;
const JSON_PAIR = /"([^"]+)"\s*:\s*"([^"]*)"/g;

const isStreamUrl = (s) => /^https?:\/\//i.test(s);

/** Endereços do guia no cabeçalho: #EXTM3U url-tvg="a,b" (ou x-tvg-url). */
export function epgUrls(text) {
  const head = text.split(/\r?\n/).map((l) => l.trim().replace(/^﻿/, ""))
    .find((l) => /^#EXTM3U/i.test(l));
  if (!head) return [];
  const attrs = readAttrs(head);
  const v = attrs["url-tvg"] ?? attrs["x-tvg-url"];
  if (v == null) return [];
  return v.split(",").map((s) => s.trim()).filter((s) => /^http/i.test(s));
}

export function parse(text) {
  const out = [];
  let info = null, ua = null, headers = {}, drmType = null, drmKey = null;
  const reset = () => { info = null; ua = null; headers = {}; drmType = null; drmKey = null; };

  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim().replace(/^﻿/, "");
    if (!line) continue;

    if (/^#EXTINF/i.test(line)) { reset(); info = line; continue; }

    if (/^#EXTVLCOPT:/i.test(line)) {
      const opt = afterColon(line);
      const k = before(opt, "=").trim().toLowerCase();
      const v = unquote(after(opt, "="));
      if (k === "http-user-agent") ua = v;
      else if (k === "http-referrer" || k === "http-referer") headers.Referer = v;
      else if (k === "http-origin") headers.Origin = v;
      continue;
    }

    if (/^#KODIPROP:/i.test(line)) {
      const opt = afterColon(line);
      const k = before(opt, "=").trim().toLowerCase();
      const v = after(opt, "=").trim();
      if (k === "inputstream.adaptive.license_type") drmType = v;
      else if (k === "inputstream.adaptive.license_key") drmKey = v;
      else if (k === "inputstream.adaptive.stream_headers" || k === "inputstream.adaptive.manifest_headers") {
        for (const [a, b] of headerPairs(v)) { if (a.toLowerCase() === "user-agent") ua = b; else headers[a] = b; }
      }
      continue;
    }

    if (/^#EXTHTTP:/i.test(line)) {
      for (const m of line.matchAll(JSON_PAIR)) {
        if (m[1].toLowerCase() === "user-agent") ua = m[2]; else headers[m[1]] = m[2];
      }
      continue;
    }

    if (line.startsWith("#")) continue; // comentários e outras diretivas

    if (info != null) {
      let url = line;
      let agent = ua;
      const h = { ...headers };
      const bar = url.indexOf("|");
      if (bar >= 0) {
        const extra = url.slice(bar + 1);
        url = url.slice(0, bar);
        for (const [a, b] of headerPairs(extra)) { if (a.toLowerCase() === "user-agent") agent = b; else h[a] = b; }
      }
      if (isStreamUrl(url)) out.push(build(info, url, agent, h, drmType, drmKey));
    }
    reset();
  }
  return out;
}

function build(inf, url, ua, headers, drmType, drmKey) {
  const sep = nameSeparator(inf);
  const head = sep >= 0 ? inf.slice(0, sep) : inf;
  const attrs = readAttrs(head);
  let name = sep >= 0 ? inf.slice(sep + 1).trim() : "";
  if (!name) name = attrs["tvg-name"] ?? "";
  if (!name) name = url.slice(url.lastIndexOf("/") + 1);
  const g = (attrs["group-title"] ?? "").split(";")[0].trim();
  const group = g || "Outros";
  const isRadio = (attrs["radio"] ?? "").toLowerCase() === "true" || /r[aá]dio/i.test(group);
  const logo = (attrs["tvg-logo"] ?? "").trim() || null;
  const tvgId = (attrs["tvg-id"] ?? "").trim() || null;
  return {
    name, url, group, logo, isRadio,
    userAgent: ua,
    headers,
    drmType,
    drmKey: drmKey && drmKey.trim() ? drmKey : null,
    tvgId,
    key: `${group}|${name}`,
  };
}

/** Posição da primeira vírgula fora de aspas (separa atributos do nome). */
function nameSeparator(s) {
  let q = false;
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (c === '"') q = !q;
    else if (c === "," && !q) return i;
  }
  return -1;
}

function readAttrs(s) {
  const o = {};
  for (const m of s.matchAll(ATTR)) o[m[1].toLowerCase()] = m[2];
  return o;
}

function headerPairs(s) {
  return s.split("&").flatMap((part) => {
    const i = part.indexOf("=");
    if (i <= 0) return [];
    const k = part.slice(0, i).trim();
    const v = part.slice(i + 1);
    let d = v;
    try { d = decodeURIComponent(v.replace(/\+/g, " ")); } catch { /* fica como está */ }
    return k ? [[k, d]] : [];
  });
}

const afterColon = (s) => s.slice(s.indexOf(":") + 1);
const before = (s, c) => { const i = s.indexOf(c); return i < 0 ? s : s.slice(0, i); };
const after = (s, c) => { const i = s.indexOf(c); return i < 0 ? "" : s.slice(i + 1); };
function unquote(s) {
  const t = s.trim();
  return t.length >= 2 && t.startsWith('"') && t.endsWith('"') ? t.slice(1, -1) : t;
}
