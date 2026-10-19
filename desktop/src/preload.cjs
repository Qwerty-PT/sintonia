// Ponte segura entre o ecrã e o processo principal (sem Node no ecrã).
const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("sintonia", {
  fetchText: (url) => ipcRenderer.invoke("fetch-text", url),
  cacheRead: (name) => ipcRenderer.invoke("cache-read", name),
  cacheWrite: (name, text) => ipcRenderer.invoke("cache-write", name, text),
  cacheDelete: (name) => ipcRenderer.invoke("cache-delete", name),
  setStream: (s) => ipcRenderer.invoke("set-stream", s),
  loadEpg: (opts) => ipcRenderer.invoke("load-epg", opts),
  toggleFullscreen: () => ipcRenderer.invoke("toggle-fullscreen"),
  version: () => ipcRenderer.invoke("version"),
});
