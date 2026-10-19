package pt.nuno.sintonia

import org.xmlpull.v1.XmlPullParser

data class Programme(val start: Long, val stop: Long, val title: String, val desc: String?)

/**
 * Leitor XMLTV em streaming (guias de dezenas de MB sem os carregar em memória).
 * Só guarda programas dos canais pedidos (por tvg-id ou pelo nome) dentro da janela [from, to).
 */
object EpgParser {

    class Result(
        val programmes: Map<String, List<Programme>>,
        /** nome do canal (minúsculas) → id no EPG, para canais sem tvg-id compatível */
        val nameToId: Map<String, String>,
    )

    fun parse(
        xpp: XmlPullParser,
        wantedIds: Set<String>,
        wantedNames: Set<String>,
        from: Long,
        to: Long,
    ): Result {
        val progs = HashMap<String, MutableList<Programme>>()
        val names = HashMap<String, String>()
        val accept = HashSet(wantedIds)

        var chId: String? = null
        var pCh: String? = null
        var pStart = 0L
        var pStop = 0L
        var title: String? = null
        var desc: String? = null
        var target = 0 // 1 display-name, 2 title, 3 desc

        var ev = xpp.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (xpp.name) {
                    "channel" -> chId = xpp.getAttributeValue(null, "id")
                    "display-name" -> if (chId != null) target = 1
                    "programme" -> {
                        val c = xpp.getAttributeValue(null, "channel")
                        val s = parseTime(xpp.getAttributeValue(null, "start"))
                        val e = parseTime(xpp.getAttributeValue(null, "stop"))
                        if (c != null && c in accept && s > 0 && e > from && s < to) {
                            pCh = c; pStart = s; pStop = if (e > s) e else s + 30 * 60_000L
                            title = null; desc = null
                        } else {
                            skipElement(xpp) // não interessa: salta o conteúdo todo
                        }
                    }
                    "title" -> if (pCh != null && title == null) target = 2
                    "desc" -> if (pCh != null && desc == null) target = 3
                }

                XmlPullParser.TEXT -> {
                    val t = xpp.text
                    when (target) {
                        1 -> {
                            val n = t.trim().lowercase()
                            val id = chId
                            if (id != null && n in wantedNames && n !in names) {
                                names[n] = id
                                accept += id
                            }
                        }
                        2 -> title = clean(t, 200)
                        3 -> desc = clean(t, 400)
                    }
                }

                XmlPullParser.END_TAG -> {
                    target = 0
                    when (xpp.name) {
                        "channel" -> chId = null
                        "programme" -> {
                            val c = pCh
                            val tt = title
                            if (c != null && !tt.isNullOrEmpty()) {
                                progs.getOrPut(c) { ArrayList() } += Programme(pStart, pStop, tt, desc)
                            }
                            pCh = null
                        }
                    }
                }
            }
            ev = xpp.next()
        }
        progs.values.forEach { list -> list.sortBy { it.start } }
        return Result(progs, names)
    }

    private fun skipElement(xpp: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (xpp.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private fun clean(s: String, max: Int): String {
        val t = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
        return if (t.length > max) t.substring(0, max).trimEnd() + "…" else t
    }

    /** "20261019060000 +0000" → epoch ms (sem SimpleDateFormat: rápido para 50 mil entradas). */
    fun parseTime(s: String?): Long {
        if (s == null || s.length < 12) return 0
        fun num(a: Int, b: Int): Int {
            var v = 0
            for (i in a until b) {
                val d = s[i] - '0'
                if (d !in 0..9) return -1
                v = v * 10 + d
            }
            return v
        }
        val y = num(0, 4); val mo = num(4, 6); val d = num(6, 8)
        val h = num(8, 10); val mi = num(10, 12)
        val se = if (s.length >= 14) num(12, 14).coerceAtLeast(0) else 0
        if (y < 0 || mo < 1 || d < 1 || h < 0 || mi < 0) return 0
        var offsetMin = 0
        val tz = s.substring(minOf(14, s.length)).trim()
        if (tz.length >= 5 && (tz[0] == '+' || tz[0] == '-')) {
            val oh = tz.substring(1, 3).toIntOrNull() ?: 0
            val om = tz.substring(3, 5).toIntOrNull() ?: 0
            offsetMin = (oh * 60 + om) * (if (tz[0] == '-') -1 else 1)
        }
        val days = daysFromCivil(y, mo, d)
        return ((days * 86400L) + h * 3600L + mi * 60L + se - offsetMin * 60L) * 1000L
    }

    /** Dias desde 1970-01-01 (algoritmo de Howard Hinnant). */
    private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }
}
