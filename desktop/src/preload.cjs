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
  checkChannel: (ch) => ipcRenderer.invoke("check-channel", ch),
  loadPrebuilt: (key) => ipcRenderer.invoke("load-prebuilt", key),
  checkUpdate: () => ipcRenderer.invoke("check-update"),
  openExternal: (url) => ipcRenderer.invoke("open-external", url),
  quit: () => ipcRenderer.invoke("quit"),
  mini: (on) => ipcRenderer.invoke("mini", on),
  win: (what) => ipcRenderer.invoke("win", what),
});
