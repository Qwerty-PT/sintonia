package pt.nuno.sintonia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.StringReader

class EpgParserTest {

    private fun parser(xml: String) = KXmlParser().apply { setInput(StringReader(xml)) }

    @Test
    fun parsesTimes() {
        assertEquals(1_760_853_600_000L, EpgParser.parseTime("20251019060000 +0000"))
        assertEquals(1_760_853_600_000L, EpgParser.parseTime("20251019070000 +0100"))
        assertEquals(1_760_853_600_000L, EpgParser.parseTime("20251019020000 -0400"))
    }

    @Test
    fun filtersByIdNameAndWindow() {
        val xml = """
            <tv>
              <channel id="RTP1.pt"><display-name>RTP 1</display-name></channel>
              <channel id="X.pt"><display-name>Canal X</display-name></channel>
              <channel id="Z.pt"><display-name>Outro</display-name></channel>
              <programme start="20251019060000 +0000" stop="20251019070000 +0000" channel="RTP1.pt">
                <title lang="pt">Bom Dia Portugal</title><desc>Notícias &amp; tempo</desc>
              </programme>
              <programme start="20251019070000 +0000" stop="20251019080000 +0000" channel="X.pt"><title>Por nome</title></programme>
              <programme start="20251019070000 +0000" stop="20251019080000 +0000" channel="Z.pt"><title>Ignorar</title></programme>
              <programme start="20251001070000 +0000" stop="20251001080000 +0000" channel="RTP1.pt"><title>Antigo</title></programme>
            </tv>
        """.trimIndent()
        val from = EpgParser.parseTime("20251019000000 +0000")
        val r = EpgParser.parse(parser(xml), setOf("RTP1.pt"), setOf("canal x"), from, from + 86_400_000L)
        assertEquals(setOf("RTP1.pt", "X.pt"), r.programmes.keys)
        val p = r.programmes["RTP1.pt"]!!.single()
        assertEquals("Bom Dia Portugal", p.title)
        assertEquals("Notícias & tempo", p.desc)
        assertEquals("X.pt", r.nameToId["canal x"])
    }

    /** Guia real: M3U_FILE=…/M3UPT.m3u EPG_FILE=…/epg-m3upt.xml.xz ./gradlew test -i */
    @Test
    fun parsesRealGuideIfProvided() {
        val epg = System.getenv("EPG_FILE") ?: return
        val m3u = System.getenv("M3U_FILE") ?: return
        val channels = M3uParser.parse(File(m3u).readText())
        val now = EpgParser.parseTime(System.getenv("EPG_NOW") ?: "20261019070000 +0000")
        val t0 = System.currentTimeMillis()
        val r = XZInputStream(File(epg).inputStream().buffered()).use { input ->
            val x = KXmlParser(); x.setInput(input, "UTF-8")
            EpgParser.parse(
                x, channels.mapNotNull { it.tvgId }.toSet(), channels.map { it.name.lowercase() }.toSet(),
                now - 2 * 3_600_000L, now + 48 * 3_600_000L,
            )
        }
        val ms = System.currentTimeMillis() - t0
        val total = r.programmes.values.sumOf { it.size }
        println("EPG: ${r.programmes.size} canais, $total programas, ${ms} ms, por nome=${r.nameToId.size}")
        r.programmes["RTP1.pt"]?.firstOrNull { it.stop > now }?.let { println("RTP1 agora/seguinte: ${it.title} | ${it.desc?.take(60)}") }
        r.programmes["SIC.pt"]?.firstOrNull { it.stop > now }?.let { println("SIC: ${it.title}") }
        assertTrue(r.programmes.size > 50)
    }
}
