// Guia XMLTV — mesmas regras que o APK (spec/FORMATOS.md §2).
// Recolhe programas a partir de eventos de um leitor XML em streaming (sax), para guias de dezenas de MB.

/** "20261019060000 +0000" → epoch ms (sem fuso → UTC). 0 se inválido. */
export function parseTime(s) {
  if (!s || s.length < 12) return 0;
  const num = (a, b) => {
    const t = s.slice(a, b);
    return /^\d+$/.test(t) ? Number(t) : -1;
  };
  const y = num(0, 4), mo = num(4, 6), d = num(6, 8), h = num(8, 10), mi = num(10, 12);
  const se = s.length >= 14 ? Math.max(num(12, 14), 0) : 0;
  if (y < 0 || mo < 1 || d < 1 || h < 0 || mi < 0) return 0;
  let offsetMin = 0;
  const tz = s.slice(Math.min(14, s.length)).trim();
  if (tz.length >= 5 && (tz[0] === "+" || tz[0] === "-")) {
    const oh = Number(tz.slice(1, 3)) || 0;
    const om = Number(tz.slice(3, 5)) || 0;
    offsetMin = (oh * 60 + om) * (tz[0] === "-" ? -1 : 1);
  }
  return Date.UTC(y, mo - 1, d, h, mi, se) - offsetMin * 60_000;
}

function clean(s, max) {
  const t = s.replace(/[\t\r\n]/g, " ").trim();
  return t.length > max ? t.slice(0, max).trimEnd() + "…" : t;
}

/**
 * Cria um recolhedor para ligar a um parser sax (opentag/text/closetag).
 * wantedNames em minúsculas. Devolve { handlers, result() }.
 */
export function createCollector({ wantedIds, wantedNames, from, to }) {
  const accept = new Set(wantedIds);
  const names = new Set(wantedNames);
  const progs = new Map();
  const nameToId = {};

  let chId = null, pCh = null, pStart = 0, pStop = 0, title = null, desc = null;
  let target = 0; // 1 display-name, 2 title, 3 desc
  let text = "";
  let skipDepth = 0; // >0: dentro de um <programme> que não interessa

  const handlers = {
    opentag(node) {
      if (skipDepth > 0) { skipDepth++; return; }
      const tag = node.name.toLowerCase();
      const a = node.attributes || {};
      if (tag === "channel") chId = a.id ?? null;
      else if (tag === "display-name") { if (chId != null) { target = 1; text = ""; } }
      else if (tag === "programme") {
        const c = a.channel;
        const s = parseTime(a.start), e = parseTime(a.stop);
        if (c != null && accept.has(c) && s > 0 && e > from && s < to) {
          pCh = c; pStart = s; pStop = e > s ? e : s + 30 * 60_000; title = null; desc = null;
        } else {
          skipDepth = 1;
        }
      } else if (tag === "title") { if (pCh != null && title == null) { target = 2; text = ""; } }
      else if (tag === "desc") { if (pCh != null && desc == null) { target = 3; text = ""; } }
    },
    text(t) { if (target) text += t; },
    cdata(t) { if (target) text += t; },
    closetag(name) {
      if (skipDepth > 0) { skipDepth--; return; }
      const tag = name.toLowerCase();
      if (target === 1 && tag === "display-name") {
        const n = text.trim().toLowerCase();
        if (chId != null && names.has(n) && !(n in nameToId)) { nameToId[n] = chId; accept.add(chId); }
      } else if (target === 2 && tag === "title") title = clean(text, 200);
      else if (target === 3 && tag === "desc") desc = clean(text, 400);
      target = 0;
      if (tag === "channel") chId = null;
      else if (tag === "programme") {
        if (pCh != null && title) {
          if (!progs.has(pCh)) progs.set(pCh, []);
          progs.get(pCh).push({ start: pStart, stop: pStop, title, desc: desc || null });
        }
        pCh = null;
      }
    },
  };

  return {
    handlers,
    result() {
      const programmes = {};
      for (const [k, list] of progs) programmes[k] = list.sort((x, y) => x.start - y.start);
      return { programmes, nameToId };
    },
  };
}

/** Programa atual (se houver) e seguintes. */
export function upcoming(guide, ch, t = Date.now(), count = 5) {
  if (!guide) return [];
  let list = ch.tvgId ? guide.programmes[ch.tvgId] : null;
  if (!list) {
    const id = guide.nameToId[ch.name.toLowerCase()];
    list = id ? guide.programmes[id] : null;
  }
  if (!list) return [];
  const i = list.findIndex((p) => t < p.stop);
  return i < 0 ? [] : list.slice(i, i + count);
}

export function nowAndNext(guide, ch, t = Date.now()) {
  const l = upcoming(guide, ch, t, 2);
  if (!l.length) return [null, null];
  return l[0].start <= t ? [l[0], l[1] ?? null] : [null, l[0]];
}
