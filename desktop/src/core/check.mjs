// Verificação de canais — mesmas regras que o APK (ChannelCheck.kt). Funções puras, testadas.

export const OK = "OK", DEAD = "DEAD", BLOCKED = "BLOCKED";

/** hls | dash | direct, pela extensão do caminho. */
export function kindOf(url) {
  let p = "";
  try { p = new URL(url).pathname.toLowerCase(); } catch { p = String(url).split("?")[0].toLowerCase(); }
  if (p.endsWith(".mpd")) return "dash";
  if (p.endsWith(".m3u8") || p.endsWith(".m3u")) return "hls";
  return "direct";
}

/** Decide pelo código HTTP e pelo início do conteúdo → [estado, motivo]. */
export function classify(code, contentType, body, kind) {
  if (code === 403 || code === 451) return [BLOCKED, `bloqueado (${code}) — talvez só com IP de outro país`];
  // RTP e outros respondem 204 (vazio) a IPs de fora de Portugal
  if (code === 204) return [BLOCKED, "sem conteúdo para este país (204)"];
  if (code === 401) return [BLOCKED, "pede autenticação (401)"];
  if (code === 404 || code === 410) return [DEAD, `já não existe (${code})`];
  if (code < 200 || code > 299) return [DEAD, `o servidor respondeu ${code}`];
  const head = String(body).trimStart().slice(0, 64);
  const html = /text\/html/i.test(contentType || "") || /^<!doctype/i.test(head) || /^<html/i.test(head);
  if (kind === "hls") {
    if (head.startsWith("#EXTM3U") && /#EXTINF|#EXT-X-STREAM-INF|#EXT-X-TARGETDURATION/.test(body)) return [OK, ""];
    if (head.startsWith("#EXTM3U")) return [DEAD, "lista de vídeo vazia"];
    return [DEAD, "resposta não é um stream"];
  }
  if (kind === "dash") return body.includes("<MPD") ? [OK, ""] : [DEAD, "resposta não é um stream"];
  return html ? [DEAD, "resposta é uma página web"] : [OK, ""];
}

/** URL absoluto da primeira variante de uma lista HLS principal (ou null). */
export function firstVariant(base, body) {
  const lines = String(body).split(/\r?\n/).map((l) => l.trim());
  const i = lines.findIndex((l) => l.startsWith("#EXT-X-STREAM-INF"));
  if (i < 0) return null;
  const uri = lines.slice(i + 1).find((l) => l && !l.startsWith("#"));
  if (!uri) return null;
  try { return new URL(uri, base).toString(); } catch { return null; }
}

/** Guia preparado (ferramentas/guia.mjs): linhas S/N/P → { programmes, nameToId, source }. */
export function parseTsv(text, now = Date.now()) {
  const programmes = {}, nameToId = {};
  let source = null;
  for (const line of String(text).split("\n")) {
    const p = line.split("\t");
    if (p[0] === "S" && p.length >= 2) source = p[1];
    else if (p[0] === "N" && p.length >= 3) nameToId[p[1]] = p[2];
    else if (p[0] === "P" && p.length >= 5) {
      const stop = Number(p[3]);
      if (!Number.isFinite(stop) || stop < now) continue;
      (programmes[p[1]] ||= []).push({ start: Number(p[2]) || 0, stop, title: p[4], desc: p[5] || null });
    }
  }
  for (const l of Object.values(programmes)) l.sort((a, b) => a.start - b.start);
  return { programmes, nameToId, source };
}

/** "1.17.0" > "1.8.1"? (só números) */
export function newerVersion(a, b) {
  const x = String(a).split(".").map(Number), y = String(b).split(".").map(Number);
  for (let i = 0; i < Math.max(x.length, y.length); i++) {
    const d = (x[i] || 0) - (y[i] || 0);
    if (d) return d > 0;
  }
  return false;
}
