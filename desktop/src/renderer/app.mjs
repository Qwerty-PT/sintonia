// Sintonia desktop — interface (mesmo comportamento que o APK 1.8; teclado + rato).
import { parse, epgUrls as readEpgUrls } from "../core/m3u.mjs";
import { nowAndNext, upcoming } from "../core/epg.mjs";
import { Player } from "./player.mjs";

const api = window.sintonia;
const DEFAULT_URL = "https://raw.githubusercontent.com/LITUATUI/M3UPT/main/M3U/M3UPT.m3u";
const FAV = "★ Favoritos";
const HOUR = 3_600_000;
const PLAYLIST_MAX_AGE = 2 * HOUR;
const EPG_MAX_AGE = 12 * HOUR;
const PREVIEW_DELAY = 700;
const MAX_RETRIES = 3;

const $ = (s) => document.querySelector(s);
const hhmm = (t) => new Date(t).toLocaleTimeString("pt-PT", { hour: "2-digit", minute: "2-digit" });

// ---------- Preferências (iguais às do APK) ----------
const PREF_KEY = "sintonia-prefs";
const prefs = Object.assign({
  playlistUrl: DEFAULT_URL, autoplayLast: true, autoPreview: false,
  lastKey: null, lastGroup: null, lastUpdate: 0,
  epgUrl: "", epgUpdate: 0, epgSource: "",
  hiddenGroups: [], favourites: [], volume: 1, muted: false,
}, (() => { try { return JSON.parse(localStorage.getItem(PREF_KEY) || "{}"); } catch { return {}; } })());
const savePrefs = () => { try { localStorage.setItem(PREF_KEY, JSON.stringify(prefs)); } catch { /* sem espaço */ } };
const favSet = () => new Set(prefs.favourites);

// ---------- Estado ----------
const S = { channels: [], epgUrls: [], text: "", guide: null, guideState: "idle", refreshing: false, epgLoading: false, autoplayDone: false };

// ---------- Catálogo ----------
function allGroups() {
  const seen = new Set(), out = [];
  for (const c of S.channels) if (!seen.has(c.group)) { seen.add(c.group); out.push(c.group); }
  return out;
}
function groupsList() {
  const hidden = new Set(prefs.hiddenGroups), favs = favSet();
  const names = allGroups().filter((g) => !hidden.has(g));
  return (S.channels.some((c) => favs.has(c.key)) ? [FAV] : []).concat(names);
}
function channelsFor(g) {
  if (g === FAV) { const f = favSet(); return S.channels.filter((c) => f.has(c.key)); }
  return S.channels.filter((c) => c.group === g);
}

// ---------- Aviso curto ----------
const toastEl = Object.assign(document.createElement("div"), { className: "toast" });
toastEl.hidden = true;
document.body.append(toastEl);
let toastTimer;
function toast(msg) {
  toastEl.textContent = msg;
  toastEl.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { toastEl.hidden = true; }, 2800);
}

// ---------- Lista navegável com setas (grupos ou canais) ----------
class ListView {
  constructor(el, opts) {
    this.el = el;
    this.o = opts;
    this.items = [];
    el.addEventListener("keydown", (e) => this.onKey(e));
    el.addEventListener("focusin", (e) => {
      const b = e.target.closest(".item");
      if (b) this.o.onFocus?.(+b.dataset.i);
    });
    el.addEventListener("click", (e) => {
      const b = e.target.closest(".item");
      if (b) this.o.onActivate?.(+b.dataset.i);
    });
    el.addEventListener("dblclick", (e) => {
      const b = e.target.closest(".item");
      if (b) this.o.onDouble?.(+b.dataset.i);
    });
    el.addEventListener("contextmenu", (e) => {
      const b = e.target.closest(".item");
      if (b && this.o.onContext) { e.preventDefault(); this.o.onContext(+b.dataset.i); }
    });
  }
  set(items) {
    this.items = items;
    this.el.replaceChildren(...items.map((it, i) => this.build(it, i)));
    this.el.scrollTop = 0;
  }
  build(it, i) {
    const b = this.o.render(it, i);
    b.classList.add("item");
    b.type = "button";
    b.dataset.i = i;
    return b;
  }
  refreshItem(i) {
    const old = this.el.children[i];
    if (!old) return;
    const had = document.activeElement === old;
    const b = this.build(this.items[i], i);
    old.replaceWith(b);
    if (had) b.focus({ preventScroll: true });
  }
  select(i) {
    [...this.el.children].forEach((c, j) => c.classList.toggle("selected", j === i));
  }
  focus(i) {
    const n = this.el.children.length;
    if (!n) { this.el.focus?.(); return; }
    const b = this.el.children[Math.max(0, Math.min(n - 1, i))];
    b.focus({ preventScroll: true });
    b.scrollIntoView({ block: "nearest" });
  }
  hasFocus() { return this.el.contains(document.activeElement); }
  forEachItem(fn) { [...this.el.children].forEach((b, i) => fn(b, this.items[i], i)); }
  onKey(e) {
    const b = e.target.closest(".item");
    if (!b) return;
    const i = +b.dataset.i;
    const k = e.key;
    const handled = () => { e.preventDefault(); e.stopPropagation(); };
    if (k === "ArrowDown") { handled(); this.focus(i + 1); }
    else if (k === "ArrowUp") { handled(); if (i === 0 && this.o.onUpTop) this.o.onUpTop(); else this.focus(i - 1); }
    else if (k === "PageDown") { handled(); this.focus(i + 10); }
    else if (k === "PageUp") { handled(); this.focus(i - 10); }
    else if (k === "Home") { handled(); this.focus(0); }
    else if (k === "End") { handled(); this.focus(this.items.length - 1); }
    else if (k === "ArrowLeft" && this.o.onLeft) { handled(); this.o.onLeft(i); }
    else if (k === "ArrowRight" && this.o.onRight) { handled(); this.o.onRight(i); }
    else if (this.o.onKey && this.o.onKey(k, i)) handled();
  }
}

// ---------- Itens ----------
function placeholder(ch) {
  const d = document.createElement("div");
  d.className = "logo ph";
  d.textContent = ch.isRadio ? "♪" : "▭";
  return d;
}
function logoEl(ch, cls = "logo") {
  if (!ch.logo) return placeholder(ch);
  const img = document.createElement("img");
  img.className = cls;
  img.loading = "lazy";
  img.alt = "";
  img.src = ch.logo;
  img.addEventListener("error", () => img.replaceWith(placeholder(ch)), { once: true });
  return img;
}
function subText(ch, showGroup) {
  const [now] = nowAndNext(S.guide, ch);
  const parts = [];
  if (showGroup) parts.push(ch.group);
  if (now) parts.push(`▶ ${now.title}`);
  else if (ch.isRadio && !showGroup) parts.push("Rádio");
  return parts.join(" · ");
}
function renderChannel(ch, showGroup) {
  const b = document.createElement("button");
  const text = document.createElement("div");
  text.className = "ch-text";
  const name = Object.assign(document.createElement("div"), { className: "ch-name", textContent: ch.name });
  const sub = Object.assign(document.createElement("div"), { className: "ch-sub" });
  sub.textContent = subText(ch, showGroup);
  sub.hidden = !sub.textContent;
  text.append(name, sub);
  b.append(logoEl(ch), text);
  if (favSet().has(ch.key)) b.append(Object.assign(document.createElement("span"), { className: "star", textContent: "★" }));
  return b;
}
function renderGroup(g) {
  const b = document.createElement("button");
  b.textContent = g;
  b.title = g;
  return b;
}
function updateSubs(list, showGroup) {
  list.forEachItem((b, ch) => {
    const sub = b.querySelector(".ch-sub");
    if (!sub) return;
    sub.textContent = subText(ch, showGroup);
    sub.hidden = !sub.textContent;
  });
}

// ---------- Painel do guia ----------
class GuidePanel {
  constructor(el) {
    this.el = el;
    el.append($("#guideTpl").content.cloneNode(true));
    this.q = (s) => el.querySelector(s);
    this.ch = null;
  }
  bind(ch) {
    const changed = ch?.key !== this.ch?.key;
    this.ch = ch;
    this.el.style.visibility = ch ? "visible" : "hidden";
    if (!ch) return;
    this.q(".g-name").textContent = ch.name;
    if (changed) {
      const logo = this.q(".g-logo");
      if (ch.logo) { logo.hidden = false; logo.src = ch.logo; logo.onerror = () => { logo.hidden = true; }; }
      else logo.hidden = true;
    }
    const t = Date.now();
    const list = upcoming(S.guide, ch, t, 5);
    const cur = list[0] && list[0].start <= t ? list[0] : null;
    const next = cur ? list.slice(1) : list;
    const now = this.q(".g-now"), range = this.q(".g-range"), desc = this.q(".g-desc");
    now.hidden = range.hidden = desc.hidden = !cur;
    if (cur) {
      now.textContent = cur.title;
      range.querySelector("span").textContent = `${hhmm(cur.start)} – ${hhmm(cur.stop)}`;
      range.querySelector(".bar > div").style.width = `${Math.min(100, Math.max(0, ((t - cur.start) / (cur.stop - cur.start)) * 100))}%`;
      desc.textContent = cur.desc || "";
      desc.hidden = !cur.desc;
    }
    const nx = this.q(".g-next");
    nx.replaceChildren(...next.slice(0, 4).map((p) => {
      const d = document.createElement("div");
      const b = Object.assign(document.createElement("b"), { textContent: hhmm(p.start) });
      d.append(b, document.createTextNode(p.title));
      return d;
    }));
    this.q(".g-next-title").hidden = !next.length;
    const empty = this.q(".g-empty");
    empty.hidden = list.length > 0;
    empty.textContent = S.guideState === "loading" ? "O guia ainda está a carregar…" : "Sem guia para este canal";
  }
  refresh() { this.bind(this.ch); }
}

// =====================================================================
// Ecrã principal
// =====================================================================
const home = { groups: [], sel: 0 };
const homeGuide = new GuidePanel($("#homeGuide"));

const homeGroups = new ListView($("#groups"), {
  render: renderGroup,
  onFocus: (i) => { if (i !== home.sel) showGroup(i); },
  onActivate: (i) => { showGroup(i); focusHomeChannel(null); },
  onRight: () => focusHomeChannel(prefs.lastKey),
  onUpTop: () => $("#btnReload").focus(),
  onContext: (i) => hideGroup(i),
  onKey: (k, i) => { if (k === "h" || k === "H") { hideGroup(i); return true; } return false; },
});

const homeChannels = new ListView($("#channels"), {
  render: (ch) => renderChannel(ch, home.groups[home.sel] === FAV),
  onFocus: (i) => homeGuide.bind(homeChannels.items[i]),
  onActivate: (i) => play(homeChannels.items, i),
  onLeft: () => focusHomeGroup(),
  onUpTop: () => $("#btnReload").focus(),
  onContext: (i) => toggleFavHome(i),
  onKey: (k, i) => { if (k === "f" || k === "F") { toggleFavHome(i); return true; } return false; },
});

function applyChannels() {
  startEpg(false);
  home.groups = groupsList();
  const sel = home.groups.indexOf(prefs.lastGroup);
  home.sel = sel >= 0 ? sel : Math.max(0, home.groups.findIndex((g) => g !== FAV));
  homeGroups.set(home.groups);
  showGroup(home.sel);
  $("#empty").hidden = S.channels.length > 0;
  updateStatus();

  if (!S.autoplayDone) {
    S.autoplayDone = true;
    const last = prefs.lastKey;
    if (prefs.autoplayLast && last) {
      const ch = S.channels.find((c) => c.key === last);
      if (ch) {
        let list = homeChannels.items;
        if (!list.some((c) => c.key === last)) list = channelsFor(ch.group);
        play(list, list.findIndex((c) => c.key === last));
        return;
      }
    }
  }
  focusHomeChannel(prefs.lastKey);
}

function showGroup(i) {
  if (i < 0 || i >= home.groups.length) return;
  home.sel = i;
  homeGroups.select(i);
  prefs.lastGroup = home.groups[i];
  savePrefs();
  const items = channelsFor(home.groups[i]);
  homeChannels.set(items);
  if (!homeChannels.hasFocus()) homeGuide.bind(items.find((c) => c.key === prefs.lastKey) || items[0] || null);
}

function focusHomeChannel(key) {
  const i = key ? homeChannels.items.findIndex((c) => c.key === key) : -1;
  homeChannels.focus(Math.max(0, i));
}
function focusHomeGroup() { homeGroups.focus(home.sel); }

function updateStatus() {
  const n = S.channels.length;
  if (!n) { $("#status").textContent = ""; return; }
  const base = prefs.lastUpdate ? `${n} canais · atualizada às ${hhmm(prefs.lastUpdate)}` : `${n} canais`;
  const guide = {
    loading: "guia a carregar…",
    ready: `guia: ${S.guide ? Object.keys(S.guide.programmes).length : 0} canais`,
    error: "guia indisponível",
    none: "sem guia",
  }[S.guideState];
  $("#status").textContent = guide ? `${base} · ${guide}` : base;
}

function toggleFav(ch) {
  const favs = favSet();
  const added = !favs.has(ch.key);
  if (added) favs.add(ch.key); else favs.delete(ch.key);
  prefs.favourites = [...favs];
  savePrefs();
  toast(`${ch.name} ${added ? "adicionado aos" : "removido dos"} favoritos`);
}

function toggleFavHome(i) {
  const ch = homeChannels.items[i];
  if (!ch) return;
  toggleFav(ch);
  const current = home.groups[home.sel];
  const newGroups = groupsList();
  if (newGroups.join("\n") !== home.groups.join("\n")) {
    home.groups = newGroups;
    homeGroups.set(home.groups);
    const sel = home.groups.indexOf(current);
    if (sel < 0) { home.sel = 0; showGroup(0); focusHomeChannel(null); return; }
    home.sel = sel;
    homeGroups.select(sel);
  }
  if (current === FAV) {
    homeChannels.set(channelsFor(FAV));
    homeChannels.focus(i);
  } else homeChannels.refreshItem(i);
}

function hideGroup(i) {
  const g = home.groups[i];
  if (!g || g === FAV) return;
  if (home.groups.filter((x) => x !== FAV).length <= 1) { toast("Tem de ficar pelo menos um grupo visível"); return; }
  prefs.hiddenGroups = [...new Set([...prefs.hiddenGroups, g])];
  savePrefs();
  toast(`Grupo "${g}" escondido — repor em Definições → Grupos visíveis`);
  applyChannels();
  focusHomeGroup();
}

// ---------- Lista: carregar / atualizar ----------
function loadText(text) {
  S.text = text;
  S.channels = parse(text);
  S.epgUrls = readEpgUrls(text);
}

async function refresh(userAsked) {
  if (S.refreshing) return;
  S.refreshing = true;
  const had = S.channels.length > 0;
  if (!had) { $("#loading").hidden = false; $("#empty").hidden = true; }
  try {
    const text = await api.fetchText(prefs.playlistUrl);
    if (!parse(text).length) throw new Error("A lista não tem canais reconhecíveis");
    await api.cacheWrite("playlist.m3u", text);
    prefs.lastUpdate = Date.now();
    savePrefs();
    if (userAsked) startEpg(true);
    if (text !== S.text) { loadText(text); applyChannels(); } else updateStatus();
    if (userAsked) toast("Lista atualizada");
  } catch (e) {
    const msg = String(e?.message || e).replace(/^Error invoking remote method '[^']+': (Error: )?/, "");
    if (S.channels.length) { if (userAsked) toast("Sem ligação — a usar a lista guardada"); }
    else {
      const empty = $("#empty");
      empty.textContent = `Não foi possível carregar a lista.\n${msg}\n\nEnter ou clique para tentar de novo`;
      empty.hidden = false;
      empty.focus();
    }
  } finally {
    S.refreshing = false;
    $("#loading").hidden = true;
  }
}

// ---------- Guia ----------
const effectiveEpgUrls = () => {
  const own = prefs.epgUrl.split(",").map((s) => s.trim()).filter((s) => /^http/i.test(s));
  return own.length ? own : S.epgUrls;
};

async function loadEpgCache() {
  const raw = await api.cacheRead("epg.json");
  if (!raw) return;
  try {
    const g = JSON.parse(raw);
    const now = Date.now();
    for (const k of Object.keys(g.programmes)) {
      g.programmes[k] = g.programmes[k].filter((p) => p.stop > now);
      if (!g.programmes[k].length) delete g.programmes[k];
    }
    if (Object.keys(g.programmes).length) { S.guide = { programmes: g.programmes, nameToId: g.nameToId || {} }; S.guideState = "ready"; }
  } catch { /* cache estragada: ignora */ }
}

async function startEpg(force) {
  if (!S.channels.length || S.epgLoading) return;
  const urls = effectiveEpgUrls();
  if (!urls.length) { if (!S.guide) S.guideState = "none"; updateStatus(); return; }
  const key = urls.join(",");
  const fresh = !force && S.guide && prefs.epgSource === key && Date.now() - prefs.epgUpdate < EPG_MAX_AGE;
  if (fresh) { S.guideState = "ready"; updateStatus(); return; }

  S.epgLoading = true;
  S.guideState = "loading";
  updateStatus();
  const now = Date.now();
  try {
    const r = await api.loadEpg({
      urls,
      ids: [...new Set(S.channels.map((c) => c.tvgId).filter(Boolean))],
      names: [...new Set(S.channels.map((c) => c.name.toLowerCase()))],
      from: now - 2 * HOUR,
      to: now + 48 * HOUR,
    });
    if (r.ok === 0) {
      S.guideState = S.guide ? "ready" : "error";
      console.warn("Guia:", r.errors);
    } else {
      S.guide = { programmes: r.programmes, nameToId: r.nameToId };
      S.guideState = Object.keys(r.programmes).length ? "ready" : "none";
      prefs.epgSource = key;
      prefs.epgUpdate = Date.now();
      savePrefs();
      await api.cacheWrite("epg.json", JSON.stringify({ source: key, ...S.guide }));
    }
  } catch (e) {
    S.guideState = S.guide ? "ready" : "error";
    console.warn("Guia:", e);
  } finally {
    S.epgLoading = false;
    updateStatus();
    refreshGuides();
  }
}

function refreshGuides() {
  updateSubs(homeChannels, home.groups[home.sel] === FAV);
  homeGuide.refresh();
  if (P.overlayOpen) { updateSubs(ovChannels, ov.groups[ov.sel] === FAV); ovGuide.refresh(); }
  if (!$("#info").hidden && P.infoEpg && current()) bindEpg(current());
}

// =====================================================================
// Leitor
// =====================================================================
const video = $("#video");
video.volume = Math.min(1, Math.max(0, prefs.volume));
video.muted = !!prefs.muted;
video.addEventListener("volumechange", () => { prefs.volume = video.volume; prefs.muted = video.muted; savePrefs(); });
video.addEventListener("waiting", () => { $("#spinner").hidden = false; });
video.addEventListener("playing", () => { $("#spinner").hidden = true; });
video.addEventListener("play", syncPlayBtn);
video.addEventListener("pause", syncPlayBtn);

const P = {
  list: [], index: 0, retries: 0, retryTimer: null,
  controlsOpen: false, overlayOpen: false, infoTimer: null, ctlTimer: null, infoEpg: false,
  previewTimer: null,
};
const current = () => P.list[P.index];

const player = new Player(video, {
  onPlaying: () => { P.retries = 0; hideMessage(); $("#spinner").hidden = true; },
  onError: (msg) => {
    const ch = current();
    if (!ch) return;
    $("#spinner").hidden = true;
    if (P.retries < MAX_RETRIES) {
      P.retries++;
      showMessage(`A religar… (${P.retries}/${MAX_RETRIES})`);
      clearTimeout(P.retryTimer);
      P.retryTimer = setTimeout(() => player.play(ch), 2000 * P.retries);
    } else {
      showMessage(`Não foi possível abrir este canal.\n${msg}\n\n▲ ▼ para mudar de canal`);
    }
  },
});

function play(list, index) {
  if (!list.length || index < 0 || index >= list.length) return;
  P.list = list;
  P.index = index;
  $("#home").hidden = true;
  $("#player").hidden = false;
  document.activeElement?.blur();
  playCurrent();
}

function playCurrent() {
  const ch = current();
  if (!ch) return;
  P.retries = 0;
  clearTimeout(P.retryTimer);
  hideMessage();
  prefs.lastKey = ch.key;
  savePrefs();
  hideControls();
  showInfo(ch);
  const logo = $("#radioLogo");
  if (ch.isRadio && ch.logo) { logo.src = ch.logo; logo.hidden = P.overlayOpen; } else logo.hidden = true;
  $("#spinner").hidden = false;
  player.play(ch);
}

function zap(delta) {
  const n = P.list.length;
  if (!n) return;
  P.index = (((P.index + delta) % n) + n) % n;
  playCurrent();
}

async function exitPlayer() {
  if (P.overlayOpen) closeOverlay(false);
  hideControls();
  clearTimeout(P.retryTimer);
  cancelPreview();
  $("#info").hidden = true;
  await player.stop();
  $("#player").hidden = true;
  $("#home").hidden = false;
  // mostra no ecrã principal o grupo e o canal que estavam a tocar (favoritos podem ter mudado)
  home.groups = groupsList();
  homeGroups.set(home.groups);
  const g = home.groups.indexOf(prefs.lastGroup);
  showGroup(g >= 0 ? g : Math.min(home.sel, home.groups.length - 1));
  focusHomeChannel(prefs.lastKey);
}

// ---------- Mensagens e informação ----------
function showMessage(t) {
  const m = $("#message");
  m.textContent = t;
  m.hidden = P.overlayOpen; // com a lista aberta não tapa; aparece ao voltar a ecrã inteiro
  m.dataset.pending = "1";
}
function hideMessage() { const m = $("#message"); m.hidden = true; m.textContent = ""; delete m.dataset.pending; }

/** Ao mudar de canal: só nome e número, 3 s. Com os controlos (Enter): também o guia. */
function showInfo(ch, { epg = false, autoHide = true } = {}) {
  if (P.overlayOpen) return;
  $("#infoName").textContent = ch.name;
  $("#infoMeta").textContent = `${P.index + 1}/${P.list.length} · ${ch.group}`;
  P.infoEpg = epg;
  if (epg) bindEpg(ch); else $("#epgBox").hidden = true;
  $("#info").hidden = false;
  clearTimeout(P.infoTimer);
  if (autoHide) P.infoTimer = setTimeout(() => { $("#info").hidden = true; }, 3000);
}
function toggleInfo() {
  if (!$("#info").hidden) { clearTimeout(P.infoTimer); $("#info").hidden = true; }
  else if (current()) showInfo(current());
}
function bindEpg(ch) {
  const t = Date.now();
  const [now, next] = nowAndNext(S.guide, ch, t);
  $("#epgBox").hidden = !now && !next;
  $("#epgNow").hidden = !now;
  $("#epgNow").parentElement.querySelector(".epg-range").hidden = !now;
  $("#epgDesc").hidden = !now?.desc;
  if (now) {
    $("#epgNow").textContent = `Agora: ${now.title}`;
    $("#epgRange").textContent = `${hhmm(now.start)} – ${hhmm(now.stop)}`;
    $("#epgBar").style.width = `${Math.min(100, Math.max(0, ((t - now.start) / (now.stop - now.start)) * 100))}%`;
    $("#epgDesc").textContent = now.desc || "";
  }
  $("#epgNext").hidden = !next;
  if (next) $("#epgNext").textContent = `A seguir ${hhmm(next.start)} · ${next.title}`;
}

// ---------- Controlos (Enter / rato) ----------
const ctlButtons = () => [...document.querySelectorAll("#controls .ctl")].filter((b) => !b.hidden);

function showControls(fromKeyboard) {
  if (P.overlayOpen || !current()) return;
  const live = player.isLive;
  document.querySelectorAll("#controls .vod").forEach((el) => { el.hidden = live; });
  $("#cLive").hidden = !live;
  $("#cTime").hidden = live;
  syncPlayBtn();
  syncTime();
  const wasOpen = P.controlsOpen;
  P.controlsOpen = true;
  $("#controls").hidden = false;
  $("#player").classList.remove("hide-cursor");
  if (!wasOpen) showInfo(current(), { epg: true, autoHide: false });
  if (fromKeyboard && !$("#controls").contains(document.activeElement)) $("#cPlay").focus();
  armControlsTimer();
}
function hideControls() {
  if (!P.controlsOpen) return;
  P.controlsOpen = false;
  $("#controls").hidden = true;
  clearTimeout(P.ctlTimer);
  $("#info").hidden = true;
  if ($("#controls").contains(document.activeElement)) document.activeElement.blur();
  $("#player").classList.add("hide-cursor");
}
function armControlsTimer() {
  clearTimeout(P.ctlTimer);
  P.ctlTimer = setTimeout(() => { if (!video.paused) hideControls(); }, 6000);
}
function syncPlayBtn() { $("#cPlay").textContent = video.paused ? "▶" : "❚❚"; }
function fmtDur(s) {
  if (!Number.isFinite(s)) return "--:--";
  s = Math.floor(s);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), x = s % 60;
  return (h ? `${h}:${String(m).padStart(2, "0")}` : `${m}`) + `:${String(x).padStart(2, "0")}`;
}
function syncTime() {
  if (!P.controlsOpen || player.isLive) return;
  $("#cTime").textContent = `${fmtDur(video.currentTime)} / ${fmtDur(video.duration)}`;
  if (document.activeElement !== $("#cSeek")) $("#cSeek").value = String(Math.round((video.currentTime / video.duration) * 1000) || 0);
}
video.addEventListener("timeupdate", syncTime);

$("#cPlay").addEventListener("click", () => { if (video.paused) video.play().catch(() => {}); else video.pause(); armControlsTimer(); });
$("#cBack").addEventListener("click", () => { video.currentTime = Math.max(0, video.currentTime - 10); armControlsTimer(); });
$("#cFwd").addEventListener("click", () => { video.currentTime = Math.min(video.duration || 0, video.currentTime + 10); armControlsTimer(); });
$("#cSeek").tabIndex = -1;
$("#cSeek").addEventListener("input", () => { if (Number.isFinite(video.duration)) video.currentTime = (+$("#cSeek").value / 1000) * video.duration; armControlsTimer(); });
$("#cLive").addEventListener("click", () => { player.seekToLive(); video.play().catch(() => {}); armControlsTimer(); });
$("#cChannels").addEventListener("click", () => openOverlay());
$("#cFull").addEventListener("click", () => api.toggleFullscreen());

// Rato: mexer mostra os controlos; duplo clique alterna ecrã inteiro; roda = volume
let mouseIdle;
$("#player").addEventListener("mousemove", () => {
  if (P.overlayOpen) return;
  $("#player").classList.remove("hide-cursor");
  clearTimeout(mouseIdle);
  if (!P.controlsOpen) showControls(false); else armControlsTimer();
  mouseIdle = setTimeout(() => { if (!P.controlsOpen) $("#player").classList.add("hide-cursor"); }, 3000);
});
$("#stage").addEventListener("dblclick", () => { if (!P.overlayOpen) api.toggleFullscreen(); });
$("#stage").addEventListener("wheel", (e) => {
  if (P.overlayOpen) return;
  e.preventDefault();
  video.muted = false;
  video.volume = Math.min(1, Math.max(0, video.volume + (e.deltaY < 0 ? 0.05 : -0.05)));
  toast(`Volume ${Math.round(video.volume * 100)}%`);
}, { passive: false });

// =====================================================================
// Lista de canais sobre o vídeo (vídeo numa janela, guia do canal focado)
// =====================================================================
const ov = { groups: [], sel: 0 };
const ovGuide = new GuidePanel($("#ovGuide"));

const ovGroups = new ListView($("#ovGroups"), {
  render: renderGroup,
  onFocus: (i) => { if (i !== ov.sel) showOvGroup(i, null); },
  onActivate: (i) => { showOvGroup(i, null); ovChannels.focus(0); },
  onRight: () => focusOvChannel(),
});

const ovChannels = new ListView($("#ovChannels"), {
  render: (ch) => renderChannel(ch, ov.groups[ov.sel] === FAV),
  onFocus: (i) => { ovGuide.bind(ovChannels.items[i]); if (prefs.autoPreview) schedulePreview(i); },
  onActivate: (i) => choose(i),
  onDouble: (i) => { if (ovChannels.items[i]?.key === current()?.key) closeOverlay(); },
  onLeft: () => ovGroups.focus(ov.sel),
  onRight: () => $("#slot").focus(),
  onContext: (i) => { toggleFav(ovChannels.items[i]); ovChannels.refreshItem(i); },
  onKey: (k, i) => {
    if (k === "f" || k === "F") { toggleFav(ovChannels.items[i]); ovChannels.refreshItem(i); return true; }
    return false;
  },
});

function openOverlay() {
  if (P.overlayOpen) return true;
  if (!S.channels.length || !current()) return false;
  const cur = current();
  hideControls();
  clearTimeout(P.infoTimer);
  $("#info").hidden = true;
  $("#radioLogo").hidden = true;
  $("#message").hidden = true;
  P.overlayOpen = true;
  $("#player").classList.add("list-open");
  $("#player").classList.remove("hide-cursor");
  $("#overlay").hidden = false;

  ov.groups = groupsList();
  const wanted = prefs.lastGroup && channelsFor(prefs.lastGroup).some((c) => c.key === cur.key) ? prefs.lastGroup : cur.group;
  ov.sel = Math.max(0, ov.groups.indexOf(wanted));
  ovGroups.set(ov.groups);
  showOvGroup(ov.sel, cur.key);
  ovGuide.bind(cur);
  placeVideo();
  return true;
}

function closeOverlay(withInfo = true) {
  if (!P.overlayOpen) return;
  cancelPreview();
  P.overlayOpen = false;
  $("#overlay").hidden = true;
  $("#player").classList.remove("list-open");
  $("#stage").removeAttribute("style");
  const ch = current();
  if (ch?.isRadio && ch.logo) $("#radioLogo").hidden = false;
  const m = $("#message");
  if (m.dataset.pending && m.textContent) m.hidden = false;
  document.activeElement?.blur();
  if (withInfo && ch) showInfo(ch);
}

/** Encolhe o vídeo (que continua a tocar) para a janela 16:9 do painel direito. */
function placeVideo() {
  if (!P.overlayOpen) return;
  requestAnimationFrame(() => {
    const r = $("#slot").getBoundingClientRect();
    Object.assign($("#stage").style, { inset: "auto", left: `${r.left}px`, top: `${r.top}px`, width: `${r.width}px`, height: `${r.height}px` });
  });
}
window.addEventListener("resize", placeVideo);

function showOvGroup(i, focusKey) {
  if (i < 0 || i >= ov.groups.length) return;
  ov.sel = i;
  ovGroups.select(i);
  const items = channelsFor(ov.groups[i]);
  ovChannels.set(items);
  if (focusKey != null) {
    const idx = Math.max(0, items.findIndex((c) => c.key === focusKey));
    ovChannels.focus(idx);
  } else if (!ovChannels.hasFocus()) {
    ovGuide.bind(items[0] || null);
  }
}

function focusOvChannel() {
  const idx = ovChannels.items.findIndex((c) => c.key === current()?.key);
  ovChannels.focus(Math.max(0, idx));
}

/** Enter num canal: se ainda não está na janela, pré-visualiza; se já está, ecrã inteiro. */
function choose(i) {
  const items = ovChannels.items;
  if (!items[i]) return;
  cancelPreview();
  if (items[i].key === current()?.key) { closeOverlay(); return; }
  prefs.lastGroup = ov.groups[ov.sel];
  savePrefs();
  P.list = items;
  P.index = i;
  playCurrent();
}

function schedulePreview(i) {
  cancelPreview();
  const items = ovChannels.items;
  const ch = items[i];
  if (!ch || ch.key === current()?.key) return;
  const group = ov.groups[ov.sel];
  P.previewTimer = setTimeout(() => {
    if (!P.overlayOpen) return;
    prefs.lastGroup = group;
    P.list = items;
    P.index = i;
    playCurrent();
  }, PREVIEW_DELAY);
}
function cancelPreview() { clearTimeout(P.previewTimer); P.previewTimer = null; }

$("#slot").addEventListener("click", () => closeOverlay());
$("#slot").addEventListener("keydown", (e) => {
  if (e.key === "ArrowLeft") { e.preventDefault(); e.stopPropagation(); focusOvChannel(); }
});

// =====================================================================
// Teclado
// =====================================================================
const ZAP_UP = new Set(["ArrowUp", "PageUp", "+", "ChannelUp"]);
const ZAP_DOWN = new Set(["ArrowDown", "PageDown", "-", "ChannelDown"]);

document.addEventListener("keydown", (e) => {
  if (document.querySelector("dialog[open]")) return; // os diálogos tratam das suas teclas
  if (e.defaultPrevented) return;
  if (e.key === "F11") { e.preventDefault(); api.toggleFullscreen(); return; }
  if (!$("#player").hidden) playerKey(e); else homeKey(e);
});

function homeKey(e) {
  const k = e.key;
  const inInput = /INPUT|TEXTAREA/.test(document.activeElement?.tagName || "");
  if (inInput) return;
  if (k === "Escape" || k === "Backspace") {
    if (homeChannels.hasFocus()) { e.preventDefault(); focusHomeGroup(); }
    return;
  }
  if (k === "r" || k === "R") { e.preventDefault(); refresh(true); return; }
  if (/^Arrow/.test(k) && (document.activeElement === document.body || !document.activeElement)) {
    e.preventDefault();
    focusHomeChannel(prefs.lastKey);
  }
}

function playerKey(e) {
  const k = e.key;
  const stop = () => e.preventDefault();

  if (P.overlayOpen) {
    if (k === "Escape" || k === "Backspace") {
      stop();
      if (ovGroups.hasFocus()) exitPlayer(); else closeOverlay();
      return;
    }
    if (/^Arrow/.test(k) && !$("#overlay").contains(document.activeElement)) { stop(); focusOvChannel(); }
    return;
  }

  if (P.controlsOpen) {
    armControlsTimer();
    if (k === "Escape" || k === "Backspace") { stop(); hideControls(); return; }
    if (k === "ArrowLeft" || k === "ArrowRight") {
      stop();
      const btns = ctlButtons();
      const i = btns.indexOf(document.activeElement);
      const next = i < 0 ? 0 : Math.max(0, Math.min(btns.length - 1, i + (k === "ArrowRight" ? 1 : -1)));
      btns[next]?.focus();
      return;
    }
    if (k === "ArrowUp" || k === "ArrowDown") { stop(); return; }
    if (k === " " && !$("#controls").contains(document.activeElement)) { stop(); $("#cPlay").click(); }
    return;
  }

  // Ecrã inteiro, sem controlos
  if (ZAP_UP.has(k)) { stop(); zap(+1); return; }
  if (ZAP_DOWN.has(k)) { stop(); zap(-1); return; }
  if (k === "ArrowLeft") { stop(); openOverlay(); return; }
  if (k === "ArrowRight" || k === "i" || k === "I") { stop(); toggleInfo(); return; }
  if (k === "Enter" || k === " ") { stop(); showControls(true); return; }
  if (k === "Escape" || k === "Backspace") { stop(); if (!openOverlay()) exitPlayer(); return; }
  if (k === "f" || k === "F") { stop(); api.toggleFullscreen(); return; }
  if (k === "m" || k === "M") { stop(); video.muted = !video.muted; toast(video.muted ? "Som desligado" : "Som ligado"); }
}

// =====================================================================
// Definições
// =====================================================================
const dlg = $("#settings");
$("#btnSettings").addEventListener("click", () => {
  $("#setUrl").value = prefs.playlistUrl;
  $("#setEpg").value = prefs.epgUrl;
  $("#setAutoplay").checked = prefs.autoplayLast;
  $("#setPreview").checked = prefs.autoPreview;
  dlg.showModal();
});
$("#btnCancel").addEventListener("click", () => dlg.close());
$("#settingsForm").addEventListener("submit", (e) => {
  e.preventDefault();
  saveSettings($("#setUrl").value, $("#setEpg").value);
});
$("#btnResetDefault").addEventListener("click", () => saveSettings(DEFAULT_URL, ""));

function saveSettings(url, epg) {
  prefs.autoplayLast = $("#setAutoplay").checked;
  prefs.autoPreview = $("#setPreview").checked;
  savePrefs();
  dlg.close();
  changeEpg(epg);
  changeUrl(url);
}

async function changeEpg(u) {
  const v = u.trim();
  if (v === prefs.epgUrl) return;
  prefs.epgUrl = v;
  prefs.epgUpdate = 0;
  savePrefs();
  S.guide = null;
  await api.cacheDelete("epg.json");
  startEpg(true);
}

async function changeUrl(u) {
  const v = u.trim() || DEFAULT_URL;
  if (v === prefs.playlistUrl && S.channels.length) return;
  prefs.playlistUrl = v;
  prefs.lastGroup = null;
  prefs.lastUpdate = 0;
  prefs.epgUpdate = 0;
  savePrefs();
  await api.cacheDelete("playlist.m3u");
  await api.cacheDelete("epg.json");
  S.channels = []; S.text = ""; S.guide = null; S.guideState = "idle";
  homeGroups.set([]); homeChannels.set([]); homeGuide.bind(null);
  updateStatus();
  refresh(true);
}

// Grupos visíveis
const gdlg = $("#groupsDialog");
$("#btnGroups").addEventListener("click", () => {
  const hidden = new Set(prefs.hiddenGroups);
  $("#groupChecks").replaceChildren(...allGroups().map((g, i) => {
    const label = document.createElement("label");
    label.className = "check";
    const cb = Object.assign(document.createElement("input"), { type: "checkbox", checked: !hidden.has(g), id: `grp-${i}` });
    cb.dataset.group = g;
    label.append(cb, document.createTextNode(g));
    return label;
  }));
  gdlg.showModal();
});
$("#btnGroupsCancel").addEventListener("click", () => gdlg.close());
$("#btnGroupsSave").addEventListener("click", () => {
  const boxes = [...$("#groupChecks").querySelectorAll("input")];
  if (!boxes.some((b) => b.checked)) { toast("Tem de ficar pelo menos um grupo visível"); return; }
  prefs.hiddenGroups = boxes.filter((b) => !b.checked).map((b) => b.dataset.group);
  savePrefs();
  gdlg.close();
  applyChannels();
});

// =====================================================================
// Arranque
// =====================================================================
$("#btnReload").addEventListener("click", () => refresh(true));
$("#empty").addEventListener("click", () => refresh(true));
$("#btnReload").addEventListener("keydown", (e) => { if (e.key === "ArrowDown") { e.preventDefault(); focusHomeChannel(prefs.lastKey); } });
$("#btnSettings").addEventListener("keydown", (e) => { if (e.key === "ArrowDown") { e.preventDefault(); focusHomeChannel(prefs.lastKey); } });

setInterval(refreshGuides, 60_000);
setInterval(() => {
  if (!S.channels.length) return;
  if (Date.now() - prefs.lastUpdate > PLAYLIST_MAX_AGE) refresh(false);
  else startEpg(false);
}, 10 * 60_000);

(async function init() {
  api.version().then((v) => { $("#version").textContent = `Sintonia ${v} (desktop)`; });
  const cached = await api.cacheRead("playlist.m3u");
  await loadEpgCache();
  if (cached) { loadText(cached); applyChannels(); }
  if (!cached || Date.now() - prefs.lastUpdate > PLAYLIST_MAX_AGE) await refresh(false);
  else startEpg(false);
})();
