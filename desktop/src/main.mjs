// Sintonia desktop — processo principal (Electron).
// - Serve a interface pelo protocolo app:// (módulos ES sem problemas de file://).
// - Põe nos pedidos de vídeo os cabeçalhos de cada canal (User-Agent, Referer, Origin) e
//   desliga o CORS só para esses pedidos: é o que o browser não deixa fazer e a app Android faz.
// - Descarrega a lista e o guia (gz/xz, dezenas de MB) fora do ecrã, e guarda cache em disco.

import { app, BrowserWindow, ipcMain, protocol, session, shell } from "electron";
import { fileURLToPath } from "node:url";
import path from "node:path";
import fs from "node:fs/promises";
import zlib from "node:zlib";
import sax from "sax";
import xzPkg from "xz-decompress";
import { createCollector } from "./core/epg.mjs";

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
const CACHE_FILES = new Set(["playlist.m3u", "epg.json"]);
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
