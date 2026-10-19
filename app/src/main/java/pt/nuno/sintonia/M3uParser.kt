package pt.nuno.sintonia

import java.net.URLDecoder

/** Um canal (ou emissão) da lista. */
data class Channel(
    val name: String,
    val url: String,
    val group: String,
    val logo: String?,
    val isRadio: Boolean,
    val userAgent: String?,
    val headers: Map<String, String>,
    /** Ex.: "org.w3.clearkey" ou "com.widevine.alpha" (#KODIPROP license_type). */
    val drmType: String?,
    /** "kid:key[,kid:key]", JSON ou URL do servidor de licenças (#KODIPROP license_key). */
    val drmKey: String?,
    /** tvg-id, para ligar ao guia (EPG). */
    val tvgId: String? = null,
) {
    /** Chave estável para favoritos/último canal (o URL pode trazer tokens que mudam). */
    val key: String get() = "$group|$name"
}

/**
 * Leitor de listas M3U/M3U8 "estilo IPTV".
 * Suporta: #EXTINF (tvg-logo, group-title, radio), #EXTVLCOPT (user-agent, referrer, origin),
 * #KODIPROP (license_type, license_key, stream_headers), #EXTHTTP e cabeçalhos "url|K=V&K=V".
 * Kotlin puro (sem Android) para poder ser testado no JVM.
 */
object M3uParser {

    private val attrRegex = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
    private val jsonPairRegex = Regex(""""([^"]+)"\s*:\s*"([^"]*)"""")

    /** Endereços do guia no cabeçalho: #EXTM3U url-tvg="a,b" (ou x-tvg-url). */
    fun epgUrls(text: String): List<String> {
        val head = text.lineSequence().map { it.trim().removePrefix("\uFEFF") }
            .firstOrNull { it.startsWith("#EXTM3U", true) } ?: return emptyList()
        val attrs = attrRegex.findAll(head).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        val v = attrs["url-tvg"] ?: attrs["x-tvg-url"] ?: return emptyList()
        return v.split(',').map { it.trim() }.filter { it.startsWith("http", true) }
    }

    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>()

        var info: String? = null
        var ua: String? = null
        var headers = LinkedHashMap<String, String>()
        var drmType: String? = null
        var drmKey: String? = null

        fun reset() {
            info = null; ua = null; headers = LinkedHashMap(); drmType = null; drmKey = null
        }

        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    reset(); info = line
                }

                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substringAfter(':')
                    val k = opt.substringBefore('=').trim().lowercase()
                    val v = unquote(opt.substringAfter('=', ""))
                    when (k) {
                        "http-user-agent" -> ua = v
                        "http-referrer", "http-referer" -> headers["Referer"] = v
                        "http-origin" -> headers["Origin"] = v
                    }
                }

                line.startsWith("#KODIPROP:", ignoreCase = true) -> {
                    val opt = line.substringAfter(':')
                    val k = opt.substringBefore('=').trim().lowercase()
                    val v = opt.substringAfter('=', "").trim()
                    when (k) {
                        "inputstream.adaptive.license_type" -> drmType = v
                        "inputstream.adaptive.license_key" -> drmKey = v
                        "inputstream.adaptive.stream_headers",
                        "inputstream.adaptive.manifest_headers" ->
                            parseHeaderString(v).forEach { (a, b) ->
                                if (a.equals("user-agent", true)) ua = b else headers[a] = b
                            }
                    }
                }

                line.startsWith("#EXTHTTP:", ignoreCase = true) -> {
                    jsonPairRegex.findAll(line).forEach { m ->
                        val (a, b) = m.destructured
                        if (a.equals("user-agent", true)) ua = b else headers[a] = b
                    }
                }

                line.startsWith("#") -> Unit // comentários e outras diretivas

                else -> {
                    val inf = info
                    if (inf != null) {
                        var url = line
                        val h = LinkedHashMap(headers)
                        var agent = ua
                        if ('|' in url) {
                            val extra = url.substringAfter('|')
                            url = url.substringBefore('|')
                            parseHeaderString(extra).forEach { (a, b) ->
                                if (a.equals("user-agent", true)) agent = b else h[a] = b
                            }
                        }
                        if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                            out += build(inf, url, agent, h, drmType, drmKey)
                        }
                    }
                    reset()
                }
            }
        }
        return out
    }

    private fun build(
        inf: String, url: String, ua: String?, headers: Map<String, String>,
        drmType: String?, drmKey: String?,
    ): Channel {
        val sep = nameSeparator(inf)
        val head = if (sep >= 0) inf.substring(0, sep) else inf
        val attrs = attrRegex.findAll(head).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        val name = (if (sep >= 0) inf.substring(sep + 1).trim() else "")
            .ifBlank { attrs["tvg-name"].orEmpty() }
            .ifBlank { url.substringAfterLast('/') }
        val group = attrs["group-title"]?.split(';')?.firstOrNull()?.trim()
            .takeUnless { it.isNullOrEmpty() } ?: "Outros"
        val isRadio = attrs["radio"].equals("true", true) ||
            group.contains("rádio", true) || group.contains("radio", true)
        return Channel(
            name = name,
            url = url,
            group = group,
            logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
            isRadio = isRadio,
            userAgent = ua,
            headers = headers,
            drmType = drmType,
            drmKey = drmKey?.takeIf { it.isNotBlank() },
            tvgId = attrs["tvg-id"]?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** Posição da primeira vírgula fora de aspas (separa atributos do nome). -1 se não houver. */
    private fun nameSeparator(inf: String): Int {
        var quoted = false
        for (i in inf.indices) {
            val c = inf[i]
            if (c == '"') quoted = !quoted
            else if (c == ',' && !quoted) return i
        }
        return -1
    }

    private fun parseHeaderString(s: String): List<Pair<String, String>> =
        s.split('&').mapNotNull { part ->
            val k = part.substringBefore('=').trim()
            if (k.isEmpty() || '=' !in part) return@mapNotNull null
            val v = part.substringAfter('=')
            k to runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
        }

    private fun unquote(s: String): String {
        val t = s.trim()
        return if (t.length >= 2 && t.startsWith('"') && t.endsWith('"')) t.substring(1, t.length - 1) else t
    }
}
