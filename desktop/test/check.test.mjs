// Regras da verificação de canais e do guia preparado — iguais às do APK (ChannelCheckTest.kt).
import { test } from "node:test";
import assert from "node:assert/strict";
import { classify, firstVariant, kindOf, parseTsv, newerVersion } from "../src/core/check.mjs";

const master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow/index.m3u8\n";
const media = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg1.ts\n";

test("classify", () => {
  const st = (...a) => classify(...a)[0];
  assert.equal(st(200, "application/vnd.apple.mpegurl", master, "hls"), "OK");
  assert.equal(st(200, null, media, "hls"), "OK");
  assert.equal(st(200, "application/x-mpegurl", "#EXTM3U\n", "hls"), "DEAD");
  assert.equal(st(200, "text/html", "<!DOCTYPE html><html>", "hls"), "DEAD");
  assert.equal(st(403, null, "", "hls"), "BLOCKED");
  assert.equal(st(204, "application/x-mpegurl", "", "hls"), "BLOCKED");
  assert.equal(st(404, null, "", "dash"), "DEAD");
  assert.equal(st(503, null, "", "hls"), "DEAD");
  assert.equal(st(200, "application/dash+xml", "<?xml?><MPD xmlns=", "dash"), "OK");
  assert.equal(st(200, "audio/mpeg", "ID3...", "direct"), "OK");
  assert.equal(st(200, "text/html; charset=utf-8", "<html>", "direct"), "DEAD");
});

test("firstVariant e kindOf", () => {
  assert.equal(firstVariant("https://cdn.pt/live/master.m3u8?t=1", master), "https://cdn.pt/live/low/index.m3u8");
  assert.equal(firstVariant("https://x/", media), null);
  assert.equal(kindOf("https://a/b/playlist.m3u8?token=1"), "hls");
  assert.equal(kindOf("https://a/manifest.mpd"), "dash");
  assert.equal(kindOf("https://radio/stream"), "direct");
});

test("parseTsv", () => {
  const r = parseTsv([
    "S\thttps://exemplo/epg.xml.xz", "N\tcanal x\tX.pt",
    "P\tRTP1.pt\t2000000\t3000000\tDepois\t", "P\tRTP1.pt\t900000\t2000000\tAgora\tDescrição",
    "P\tRTP1.pt\t100\t500\tJá passou\t",
  ].join("\n"), 1_000_000);
  assert.equal(r.source, "https://exemplo/epg.xml.xz");
  assert.equal(r.nameToId["canal x"], "X.pt");
  assert.deepEqual(r.programmes["RTP1.pt"].map((p) => p.title), ["Agora", "Depois"]);
  assert.equal(r.programmes["RTP1.pt"][0].desc, "Descrição");
  assert.equal(r.programmes["RTP1.pt"][1].desc, null);
});

test("newerVersion", () => {
  assert.ok(newerVersion("1.17.0", "1.8.1"));
  assert.ok(!newerVersion("1.8.1", "1.8.1"));
  assert.ok(!newerVersion("1.8.0", "1.17.0"));
});
