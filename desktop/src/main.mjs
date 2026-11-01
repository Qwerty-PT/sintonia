// Sintonia desktop — processo principal (Electron).
// - Serve a interface pelo protocolo app:// (módulos ES sem problemas de file://).
// - Põe nos pedidos de vídeo os cabeçalhos de cada canal (User-Agent, Referer, Origin) e
//   desliga o CORS só para esses pedidos: é o que o browser não deixa fazer e a app Android faz.
// - Descarrega a lista e o guia (gz/xz, dezenas de MB) fora do ecrã, e guarda cache em disco.

import { app, BrowserWindow, ipcMain, protocol, screen, session, shell } from "electron";
import { fileURLToPath } from "node:url";
import path from "node:path";
import fs from "node:fs/promises";
import zlib from "node:zlib";
import sax from "sax";
import xzPkg from "xz-decompress";
import { createCollector } from "./core/epg.mjs";
import { classify, firstVariant, kindOf, parseTsv, newerVersion, DEAD } from "./core/check.mjs";

const { XzReadableStream } = xzPkg; // pacote CommonJS: só exporta via default

const here = path.dirname(fileURLToPath(import.meta.url));
const appRoot = path.join(here, "..");

const DEFAULT_UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";

/** Cabeçalhos do canal que está a tocar (só há um leitor de cada vez). */
let stream = null; // { ua, headers }

// ---------- Protocolo app:// ----------
protocol.registerSchemesAsPrivileged([
  { scheme: "app", privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: true } },
]);

const ROUTES = {
  "/vendor/hls.min.js": path.join(appRoot, "node_modules/hls.js/dist/hls.min.js"),
  "/vendor/shaka-player.compiled.js": path.join(appRoot, "node_modules/shaka-player/dist/shaka-player.compiled.js"),
};
const MIME = {
  ".html": "text/html; charset=utf-8", ".mjs": "text/javascript; charset=utf-8", ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8", ".png": "image/png", ".svg": "image/svg+xml", ".json": "application/json",
};

async function serveApp(request) {
  const { pathname } = new URL(request.url);
  let file = ROUTES[pathname];
  if (!file) {
    const rel = path.normalize(decodeURIComponent(pathname)).replace(/^([/\\])+/, "");
    file = path.join(here, rel);
    if (!file.startsWith(here + path.sep)) return new Response("Proibido", { status: 403 });
  }
  try {
    const body = await fs.readFile(file);
    return new Response(body, { headers: { "content-type": MIME[path.extname(file)] || "application/octet-stream" } });
  } catch {
    return new Response("Não encontrado", { status: 404 });
  }
}

// ---------- Cabeçalhos e CORS dos pedidos de vídeo ----------
const STREAM_TYPES = new Set(["xhr", "media", "other"]);
const WEB = { urls: ["http://*/*", "https://*/*"] };

function setupNetwork() {
  const ses = session.defaultSession;
  ses.setUserAgent(DEFAULT_UA);

  ses.webRequest.onBeforeSendHeaders(WEB, (d, cb) => {
    const h = d.requestHeaders;
    if (stream && STREAM_TYPES.has(d.resourceType)) {
      h["User-Agent"] = stream.ua || DEFAULT_UA;
      for (const [k, v] of Object.entries(stream.headers || {})) h[k] = v;
    }
    cb({ requestHeaders: h });
  });

  ses.webRequest.onHeadersReceived(WEB, (d, cb) => {
    if (!STREAM_TYPES.has(d.resourceType)) return cb({});
    const rh = { ...(d.responseHeaders || {}) };
    for (const k of Object.keys(rh)) if (/^access-control-/i.test(k)) delete rh[k];
    rh["Access-Control-Allow-Origin"] = ["*"];
    rh["Access-Control-Allow-Headers"] = ["*"];
    rh["Access-Control-Allow-Methods"] = ["GET, HEAD, OPTIONS"];
    rh["Access-Control-Expose-Headers"] = ["*"];
    const res = { responseHeaders: rh };
    if (d.method === "OPTIONS" && (d.statusCode < 200 || d.statusCode >= 300)) res.statusLine = "HTTP/1.1 200 OK";
    cb(res);
  });
}

// ---------- Cache em disco ----------
const CACHE_FILES = new Set(["playlist.m3u", "epg.json", "canais.json"]);
const cachePath = (name) => path.join(app.getPath("userData"), name);

// ---------- Guia (XMLTV .xml/.gz/.xz em streaming) ----------
async function loadEpg({ urls, ids, names, from, to }) {
  const programmes = {};
  const nameToId = {};
  const errors = [];
  let ok = 0;
  for (const u of urls) {
    try {
      const res = await fetch(u, { headers: { "User-Agent": DEFAULT_UA }, redirect: "follow" });
      if (!res.ok) throw new Error(`o servidor respondeu ${res.status}`);
      const buf = Buffer.from(await res.arrayBuffer());
      const col = createCollector({ wantedIds: ids, wantedNames: names, from, to });
      const p = sax.parser(false, { lowercase: true, trim: false, normalize: false });
      p.onopentag = col.handlers.opentag;
      p.ontext = col.handlers.text;
      p.oncdata = col.handlers.cdata;
      p.onclosetag = col.handlers.closetag;
      p.onerror = () => { p.error = null; p.resume(); };

      const decoder = new TextDecoder("utf-8");
      const isGz = buf[0] === 0x1f && buf[1] === 0x8b;
      const isXz = buf.length > 5 && buf[0] === 0xfd && buf.toString("latin1", 1, 5) === "7zXZ" && buf[5] === 0;
      if (isXz) {
        const xz = new XzReadableStream(new Blob([buf]).stream());
        for await (const chunk of xz) p.write(decoder.decode(chunk, { stream: true }));
      } else {
        const data = isGz ? zlib.gunzipSync(buf) : buf;
        const step = 1 << 20;
        for (let i = 0; i < data.length; i += step) p.write(decoder.decode(data.subarray(i, i + step), { stream: true }));
      }
      p.write(decoder.decode()).close();

      const r = col.result();
      for (const [k, v] of Object.entries(r.programmes)) if (!(k in programmes)) programmes[k] = v;
      for (const [k, v] of Object.entries(r.nameToId)) if (!(k in nameToId)) nameToId[k] = v;
      ok++;
    } catch (e) {
      errors.push(`${u}: ${e.message}`);
    }
  }
  return { ok, errors, programmes, nameToId };
}

// ---------- Verificação de canais (só o índice do stream, nunca vídeo) ----------
async function readSome(res, max) {
  if (!res.body) return Buffer.alloc(0);
  const reader = res.body.getReader();
  const chunks = [];
  let n = 0;
  try {
    while (n < max) {
      const { done, value } = await reader.read();
      if (done) break;
      chunks.push(value); n += value.length;
    }
  } finally { reader.cancel().catch(() => {}); }
  return Buffer.concat(chunks).subarray(0, max);
}

/** Alguns servidores (ex.: RTP) mandam a lista em gzip sem o dizer no cabeçalho. */
function gunzipIfNeeded(b) {
  if (b.length < 2 || b[0] !== 0x1f || b[1] !== 0x8b) return b;
  try { return zlib.gunzipSync(b, { finishFlush: zlib.constants.Z_SYNC_FLUSH }); } catch { return b; }
}

async function fetchIndex(ch, url, max) {
  const headers = { "User-Agent": ch.userAgent || DEFAULT_UA, ...(ch.headers || {}) };
  if (max <= 4096) headers.Range = `bytes=0-${max - 1}`;
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), 10_000);
  try {
    const res = await fetch(url, { headers, redirect: "follow", signal: ctl.signal });
    const body = gunzipIfNeeded(await readSome(res, max)).toString("utf8");
    return { code: res.status === 206 ? 200 : res.status, type: res.headers.get("content-type"), body, url: res.url || url };
  } finally { clearTimeout(t); }
}

async function checkOnce(ch) {
  const now = Date.now();
  try {
    const kind = kindOf(ch.url);
    const r = await fetchIndex(ch, ch.url, kind === "direct" ? 2048 : 256 * 1024);
    let [state, reason] = classify(r.code, r.type, r.body, kind);
    // HLS principal: confirmar também a 1.ª variante (é aí que os canais "meio mortos" falham)
    if (state === "OK" && r.body.includes("#EXT-X-STREAM-INF")) {
      const v = firstVariant(r.url, r.body);
      if (v) { const rv = await fetchIndex(ch, v, 256 * 1024); [state, reason] = classify(rv.code, rv.type, rv.body, "hls"); }
    }
    return { state, at: now, reason };
  } catch (e) {
    const c = e?.cause?.code || "";
    const reason = e?.name === "AbortError" || /TIMEOUT/.test(c) ? "sem resposta"
      : c === "ENOTFOUND" ? "servidor não existe" : (e?.cause?.message || e?.message || "erro de rede");
    return { state: DEAD, at: now, reason };
  }
}

async function checkChannel(ch) {
  const first = await checkOnce(ch);
  // erros de rede podem ser passageiros: só conta como morto se falhar duas vezes
  if (first.state !== DEAD || first.reason.startsWith("já não existe")) return first;
  await new Promise((r) => setTimeout(r, 1500));
  return checkOnce(ch);
}

// ---------- Guia preparado (GitHub Actions, guia.yml) ----------
const REPO = "Qwerty-PT/sintonia";
const PREBUILT_URL = `https://raw.githubusercontent.com/${REPO}/dados/guia-m3upt.tsv.gz`;

async function loadPrebuilt(sourceKey) {
  const res = await fetch(PREBUILT_URL, { headers: { "User-Agent": DEFAULT_UA } });
  if (!res.ok) throw new Error(`guia preparado: ${res.status}`);
  const r = parseTsv(gunzipIfNeeded(Buffer.from(await res.arrayBuffer())).toString("utf8"));
  if (r.source && sourceKey && r.source !== sourceKey) throw new Error(`guia preparado é de outra fonte (${r.source})`);
  return { programmes: r.programmes, nameToId: r.nameToId };
}

// ---------- Atualizações (releases desktop-v* no GitHub) ----------
async function checkUpdate() {
  const res = await fetch(`https://api.github.com/repos/${REPO}/releases?per_page=30`, {
    headers: { "User-Agent": "Sintonia-desktop", Accept: "application/vnd.github+json" },
  });
  if (!res.ok) throw new Error(`GitHub respondeu ${res.status}`);
  const list = await res.json();
  const rel = list.filter((r) => !r.draft && /^desktop-v/.test(r.tag_name))
    .map((r) => ({ version: r.tag_name.replace(/^desktop-v/, ""), url: r.html_url, notes: r.body || "" }))
    .sort((a, b) => (newerVersion(a.version, b.version) ? -1 : 1))[0];
  if (!rel) return { available: false, current: app.getVersion() };
  return { available: newerVersion(rel.version, app.getVersion()), current: app.getVersion(), ...rel };
}

// ---------- Janela flutuante (PiP): pequena, 16:9, sempre por cima das outras apps ----------
const mini = new WeakMap(); // janela → estado anterior { bounds, maximized, fullscreen }

function setMini(win, on) {
  if (!win) return false;
  if (on && !mini.has(win)) {
    mini.set(win, { bounds: win.getNormalBounds(), maximized: win.isMaximized(), fullscreen: win.isFullScreen() });
    if (win.isFullScreen()) win.setFullScreen(false);
    if (win.isMaximized()) win.unmaximize();
    const wa = screen.getDisplayMatching(win.getBounds()).workArea;
    const w = Math.round(Math.min(560, wa.width / 3)), h = Math.round((w * 9) / 16);
    win.setMinimumSize(256, 144);
    win.setBounds({ x: wa.x + wa.width - w - 24, y: wa.y + wa.height - h - 24, width: w, height: h });
    win.setAspectRatio(16 / 9);
    win.setAlwaysOnTop(true, "floating");
    win.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true });
  } else if (!on && mini.has(win)) {
    const prev = mini.get(win);
    mini.delete(win);
    win.setAlwaysOnTop(false);
    win.setVisibleOnAllWorkspaces(false);
    win.setAspectRatio(0);
    win.setMinimumSize(900, 560);
    win.setBounds(prev.bounds);
    if (prev.maximized) win.maximize();
    if (prev.fullscreen) win.setFullScreen(true);
  }
  return mini.has(win);
}

// ---------- IPC ----------
function setupIpc() {
  ipcMain.handle("fetch-text", async (_e, url) => {
    const res = await fetch(url, { headers: { "User-Agent": DEFAULT_UA }, redirect: "follow" });
    if (!res.ok) throw new Error(`O servidor respondeu ${res.status}`);
    return await res.text();
  });
  ipcMain.handle("cache-read", async (_e, name) => {
    if (!CACHE_FILES.has(name)) return null;
    try { return await fs.readFile(cachePath(name), "utf8"); } catch { return null; }
  });
  ipcMain.handle("cache-write", async (_e, name, text) => {
    if (!CACHE_FILES.has(name)) return false;
    await fs.writeFile(cachePath(name), text, "utf8");
    return true;
  });
  ipcMain.handle("cache-delete", async (_e, name) => {
    if (!CACHE_FILES.has(name)) return false;
    await fs.rm(cachePath(name), { force: true });
    return true;
  });
  ipcMain.handle("set-stream", (_e, s) => { stream = s && typeof s === "object" ? s : null; return true; });
  ipcMain.handle("load-epg", (_e, opts) => loadEpg(opts));
  ipcMain.handle("toggle-fullscreen", (e) => {
    const w = BrowserWindow.fromWebContents(e.sender);
    if (w) w.setFullScreen(!w.isFullScreen());
    return w?.isFullScreen() ?? false;
  });
  ipcMain.handle("version", () => app.getVersion());
  ipcMain.handle("check-channel", (_e, ch) => checkChannel(ch));
  ipcMain.handle("load-prebuilt", (_e, key) => loadPrebuilt(key));
  ipcMain.handle("check-update", () => checkUpdate());
  ipcMain.handle("open-external", (_e, url) => { if (/^https:\/\/github\.com\//.test(url)) shell.openExternal(url); return true; });
  ipcMain.handle("quit", () => app.quit());
  ipcMain.handle("mini", (e, on) => setMini(BrowserWindow.fromWebContents(e.sender), !!on));
}

// ---------- Janela ----------
function createWindow() {
  const win = new BrowserWindow({
    width: 1400, height: 860, minWidth: 900, minHeight: 560,
    backgroundColor: "#0E1116",
    title: "Sintonia",
    autoHideMenuBar: true,
    icon: path.join(appRoot, "build/icon.png"),
    webPreferences: {
      preload: path.join(here, "preload.cjs"),
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
      autoplayPolicy: "no-user-gesture-required",
    },
  });
  win.setMenuBarVisibility(false);
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/i.test(url)) shell.openExternal(url);
    return { action: "deny" };
  });
  win.webContents.on("will-navigate", (e, url) => { if (!url.startsWith("app://")) e.preventDefault(); });
  win.loadURL("app://sintonia/renderer/index.html");
  win.maximize();
  return win;
}

app.whenReady().then(() => {
  protocol.handle("app", serveApp);
  setupNetwork();
  setupIpc();
  const win = createWindow();
  // Diagnóstico: SINTONIA_DEBUG=1 mostra a consola do ecrã; SINTONIA_SHOT=ficheiro.png tira uma captura e sai
  if (process.env.SINTONIA_DEBUG) {
    win.webContents.on("console-message", (_e, level, message, line, src) => console.log(`[ecrã:${level}] ${message} (${src}:${line})`));
  }
  if (process.env.SINTONIA_SHOT) {
    const keys = (process.env.SINTONIA_KEYS || "").split(",").filter(Boolean);
    win.webContents.once("did-finish-load", async () => {
      await new Promise((r) => setTimeout(r, 2500));
      for (const k of keys) {
        win.webContents.sendInputEvent({ type: "keyDown", keyCode: k });
        if (k === "Return") win.webContents.sendInputEvent({ type: "char", keyCode: "\r" });
        win.webContents.sendInputEvent({ type: "keyUp", keyCode: k });
        await new Promise((r) => setTimeout(r, 700));
      }
      await new Promise((r) => setTimeout(r, Number(process.env.SINTONIA_WAIT || 1200)));
      const img = await win.webContents.capturePage();
      await fs.writeFile(process.env.SINTONIA_SHOT, img.toPNG());
      app.quit();
    });
  }
  app.on("activate", () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});

app.on("window-all-closed", () => { if (process.platform !== "darwin") app.quit(); });
