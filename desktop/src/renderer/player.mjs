// Motores de vídeo: hls.js (HLS), Shaka Player (DASH e DRM ClearKey) e <video> direto (MP3/AAC/MP4).
// Os cabeçalhos do canal (User-Agent, Referer, Origin) são postos pelo processo principal.

const api = window.sintonia;

export function streamKind(url) {
  let p = "";
  try { p = new URL(url).pathname.toLowerCase(); } catch { /* URL inválido */ }
  if (p.endsWith(".mpd")) return "dash";
  if (p.endsWith(".m3u8") || p.endsWith(".m3u")) return "hls";
  return "direct";
}

function b64urlToHex(s) {
  const pad = "=".repeat((4 - (s.length % 4)) % 4);
  const bin = atob(s.replace(/-/g, "+").replace(/_/g, "/") + pad);
  return [...bin].map((c) => c.charCodeAt(0).toString(16).padStart(2, "0")).join("");
}

/** Configuração de DRM do Shaka a partir do #KODIPROP da lista (spec/FORMATOS.md §1). */
export function drmConfig(ch) {
  const key = ch.drmKey?.trim();
  if (!key) return null;
  const type = (ch.drmType || "").toLowerCase();
  const ks = type.includes("widevine") ? "com.widevine.alpha"
    : type.includes("playready") ? "com.microsoft.playready" : "org.w3.clearkey";
  if (/^https?:/i.test(key)) return { servers: { [ks]: key.split("|")[0] } };
  if (ks !== "org.w3.clearkey") return null; // Widevine/PlayReady sem URL de licença
  const clearKeys = {};
  if (key.startsWith("{")) {
    try {
      const o = JSON.parse(key);
      if (Array.isArray(o.keys)) o.keys.forEach((k) => { clearKeys[b64urlToHex(k.kid)] = b64urlToHex(k.k); });
      else Object.entries(o).forEach(([a, b]) => { clearKeys[a.toLowerCase()] = String(b).toLowerCase(); });
    } catch { /* chave inválida */ }
  } else {
    key.split(",").forEach((pair) => {
      const [a, b] = pair.trim().split(":");
      if (a && b) clearKeys[a.replace(/-/g, "").toLowerCase()] = b.trim().toLowerCase();
    });
  }
  return Object.keys(clearKeys).length ? { clearKeys } : null;
}

function shakaText(err) {
  if (!err) return "erro desconhecido";
  const code = err.code;
  if (code === 1001 && err.data && err.data[1]) return `o servidor respondeu ${err.data[1]} (Shaka ${code})`;
  if (code >= 6000 && code < 7000) return `falha no DRM (Shaka ${code})`;
  if (code >= 1000 && code < 2000) return `falha de rede (Shaka ${code})`;
  return `Shaka ${code}`;
}

export class Player {
  constructor(video, { onError, onPlaying }) {
    this.video = video;
    this.onError = onError;
    this.onPlaying = onPlaying;
    this.hls = null;
    this.shaka = null;
    this.mode = null;
    this.token = 0;
    this.shakaReady = false;
    video.addEventListener("playing", () => this.onPlaying?.());
    video.addEventListener("error", () => {
      if (this.mode === "direct" && video.error) this.onError(`o vídeo não abre (código ${video.error.code})`);
    });
  }

  get isLive() {
    const d = this.video.duration;
    return !Number.isFinite(d) || d === 0 || this.shaka?.isLive?.() === true;
  }

  async play(ch) {
    const token = ++this.token;
    await this.stop(false);
    await api.setStream({ ua: ch.userAgent || null, headers: ch.headers || {} });
    if (token !== this.token) return;
    const kind = streamKind(ch.url);
    try {
      if (kind === "dash" || ch.drmKey) await this.playShaka(ch, token);
      else if (kind === "hls" && window.Hls && window.Hls.isSupported()) this.playHls(ch, token);
      else this.playDirect(ch);
    } catch (e) {
      if (token === this.token) this.onError(e?.code ? shakaText(e) : (e?.message || String(e)));
    }
  }

  playHls(ch, token) {
    const Hls = window.Hls;
    const hls = new Hls({
      enableWorker: true,
      backBufferLength: 30,
      manifestLoadingMaxRetry: 2,
      levelLoadingMaxRetry: 2,
      fragLoadingMaxRetry: 3,
    });
    this.hls = hls;
    this.mode = "hls";
    let mediaRecover = 0;
    hls.on(Hls.Events.MANIFEST_PARSED, () => { this.video.play().catch(() => {}); });
    hls.on(Hls.Events.ERROR, (_e, d) => {
      if (!d.fatal || token !== this.token) return;
      if (d.type === Hls.ErrorTypes.MEDIA_ERROR && mediaRecover++ < 2) { hls.recoverMediaError(); return; }
      const status = d.response && d.response.code ? ` — o servidor respondeu ${d.response.code}` : "";
      this.onError(`${d.details}${status}`);
    });
    hls.loadSource(ch.url);
    hls.attachMedia(this.video);
  }

  async playShaka(ch, token) {
    const shaka = window.shaka;
    if (!shaka) throw new Error("motor DASH indisponível");
    if (!this.shakaReady) { shaka.polyfill.installAll(); this.shakaReady = true; }
    const p = new shaka.Player();
    this.shaka = p;
    this.mode = "dash";
    await p.attach(this.video);
    const drm = drmConfig(ch);
    p.configure({ streaming: { retryParameters: { maxAttempts: 3, baseDelay: 800 } } });
    if (drm) p.configure({ drm });
    p.addEventListener("error", (ev) => { if (token === this.token) this.onError(shakaText(ev.detail)); });
    await p.load(ch.url);
    if (token !== this.token) return;
    this.video.play().catch(() => {});
  }

  playDirect(ch) {
    this.mode = "direct";
    this.video.src = ch.url;
    this.video.play().catch(() => {});
  }

  /** Volta ao direto se ficou para trás (ex.: depois de pausa num canal em direto). */
  seekToLive() {
    if (this.hls && Number.isFinite(this.hls.liveSyncPosition)) this.video.currentTime = this.hls.liveSyncPosition;
    else if (this.shaka?.isLive?.()) this.shaka.goToLive?.();
  }

  async stop(clearHeaders = true) {
    const hls = this.hls, sk = this.shaka;
    this.hls = null; this.shaka = null; this.mode = null;
    try { hls?.destroy(); } catch { /* já parado */ }
    try { await sk?.destroy(); } catch { /* já parado */ }
    this.video.pause();
    this.video.removeAttribute("src");
    this.video.load();
    if (clearHeaders) await api.setStream(null);
  }
}
