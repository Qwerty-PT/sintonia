package pt.nuno.sintonia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class M3uParserTest {

    private val sample = """
        #EXTM3U url-tvg="https://exemplo/epg.xml"

        #EXTINF:-1 group-title="TV" tvg-id="RTP1.pt" tvg-logo="https://x/rtp1.png",RTP 1
        #EXTVLCOPT:http-user-agent="Mozilla/5.0 Teste"
        #EXTVLCOPT:http-origin=https://www.rtp.pt
        #EXTVLCOPT:http-referrer=https://www.rtp.pt/play
        https://streaming-live.rtp.pt/rtp1/playlist.m3u8

        #EXTINF:-1 group-title="TV" tvg-logo="https://x/nbc.png",NBC, com vírgula
        #KODIPROP:inputstream.adaptive.license_type=org.w3.clearkey
        #KODIPROP:inputstream.adaptive.license_key=52e6281b1d3c364c872497e8058977b1:11d6e9a2abb2e241804148713531f64d
        #https://url-comentado/ignorar.m3u8
        https://exemplo/master.mpd

        #EXTINF:-1 radio="true" group-title="Rádios" tvg-logo="",Antena 1
        https://radio/antena1.m3u8|User-Agent=VLC&Referer=https%3A%2F%2Frtp.pt

        #EXTINF:-1,Sem grupo
        plugin://plugin.video.youtube/abc

        #EXTINF:-1 tvg-name="Só nome"
        http://exemplo/so.ts
    """.trimIndent()

    @Test
    fun parsesSample() {
        val list = M3uParser.parse(sample)
        assertEquals(4, list.size) // plugin:// é ignorado

        val rtp = list[0]
        assertEquals("RTP 1", rtp.name)
        assertEquals("TV", rtp.group)
        assertEquals("Mozilla/5.0 Teste", rtp.userAgent)
        assertEquals("https://www.rtp.pt", rtp.headers["Origin"])
        assertEquals("https://www.rtp.pt/play", rtp.headers["Referer"])
        assertNull(rtp.drmKey)

        val nbc = list[1]
        assertEquals("NBC, com vírgula", nbc.name)
        assertEquals("https://exemplo/master.mpd", nbc.url)
        assertEquals("org.w3.clearkey", nbc.drmType)
        assertTrue(nbc.drmKey!!.startsWith("52e6281b"))
        assertNull(nbc.userAgent) // não herda do canal anterior

        val radio = list[2]
        assertTrue(radio.isRadio)
        assertNull(radio.logo)
        assertEquals("https://radio/antena1.m3u8", radio.url)
        assertEquals("VLC", radio.userAgent)
        assertEquals("https://rtp.pt", radio.headers["Referer"])

        assertEquals("Só nome", list[3].name)
        assertEquals("Outros", list[3].group)
    }

    @Test
    fun readsEpgHeaderAndTvgId() {
        val urls = M3uParser.epgUrls(sample)
        assertEquals(listOf("https://exemplo/epg.xml"), urls)
        assertEquals("RTP1.pt", M3uParser.parse(sample)[0].tvgId)
    }

    /** Teste com a lista real: M3U_FILE=/caminho/M3UPT.m3u ./gradlew test */
    @Test
    fun parsesRealPlaylistIfProvided() {
        val path = System.getenv("M3U_FILE") ?: return
        val list = M3uParser.parse(File(path).readText())
        val groups = list.groupBy { it.group }.mapValues { it.value.size }
        println("CANAIS=${list.size} GRUPOS=$groups")
        println("DRM=${list.count { it.drmKey != null }} RADIO=${list.count { it.isRadio }}")
        println("EXEMPLOS=" + list.take(6).joinToString { "${it.name} [${it.headers.keys}]" })
        assertTrue(list.size > 100)
        assertTrue(list.any { it.name == "RTP 1" })
    }
}
