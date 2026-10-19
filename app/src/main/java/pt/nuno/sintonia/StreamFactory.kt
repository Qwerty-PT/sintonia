@file:OptIn(UnstableApi::class)

package pt.nuno.sintonia

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import org.json.JSONArray
import org.json.JSONObject

/** Cria a fonte de vídeo de um canal: cabeçalhos HTTP próprios, HLS/DASH e DRM ClearKey. */
object StreamFactory {

    const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    fun build(ctx: Context, ch: Channel): MediaSource {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(ch.userAgent ?: DEFAULT_UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(ch.headers)

        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http))
        drmManager(ch, http)?.let { manager -> factory.setDrmSessionManagerProvider { manager } }

        val item = MediaItem.Builder()
            .setUri(ch.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(ch.name).build())
            .apply { mimeFor(ch.url)?.let { setMimeType(it) } }
            .build()
        return factory.createMediaSource(item)
    }

    private fun mimeFor(url: String): String? {
        val path = Uri.parse(url).path?.lowercase() ?: return null
        return when {
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> MimeTypes.APPLICATION_M3U8
            else -> null // o ExoPlayer deteta (MP3/AAC/TS/MP4...)
        }
    }

    private fun drmManager(ch: Channel, http: DefaultHttpDataSource.Factory): DrmSessionManager? {
        val key = ch.drmKey?.trim() ?: return null
        val type = ch.drmType?.lowercase().orEmpty()
        val uuid = when {
            type.contains("widevine") -> C.WIDEVINE_UUID
            type.contains("playready") -> C.PLAYREADY_UUID
            else -> C.CLEARKEY_UUID
        }
        val callback: MediaDrmCallback = when {
            key.startsWith("http", ignoreCase = true) ->
                HttpMediaDrmCallback(key.substringBefore('|'), http)
            uuid != C.CLEARKEY_UUID -> return null // Widevine/PlayReady sem URL de licença
            key.startsWith("{") -> LocalMediaDrmCallback(jsonKeys(key).toByteArray())
            else -> LocalMediaDrmCallback(clearKeyJson(key).toByteArray())
        }
        return DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(uuid, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .build(callback)
    }

    /** "kid:key,kid2:key2" (hex) → JSON Web Key Set do ClearKey. */
    private fun clearKeyJson(pairs: String): String {
        val keys = JSONArray()
        pairs.split(',').map { it.trim() }.filter { ':' in it }.forEach { p ->
            keys.put(
                JSONObject()
                    .put("kty", "oct")
                    .put("kid", hexToB64(p.substringBefore(':')))
                    .put("k", hexToB64(p.substringAfter(':')))
            )
        }
        return JSONObject().put("keys", keys).put("type", "temporary").toString()
    }

    /** JSON já no formato JWK ({"keys":[...]}) ou mapa simples {"kidHex":"keyHex"}. */
    private fun jsonKeys(json: String): String {
        val o = JSONObject(json)
        if (o.has("keys")) return json
        return clearKeyJson(o.keys().asSequence().joinToString(",") { "$it:${o.getString(it)}" })
    }

    private fun hexToB64(hex: String): String {
        val h = hex.trim().replace("-", "")
        val bytes = ByteArray(h.length / 2) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
