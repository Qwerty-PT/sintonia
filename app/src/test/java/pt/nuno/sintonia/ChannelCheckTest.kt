package pt.nuno.sintonia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.nuno.sintonia.ChannelCheck.Kind as K
import pt.nuno.sintonia.ChannelCheck.State as S
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ChannelCheckTest {

    private val hlsMaster = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow/index.m3u8\n"
    private val hlsMedia = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg1.ts\n"

    @Test
    fun classifiesResponses() {
        assertEquals(S.OK, ChannelCheck.classify(200, "application/vnd.apple.mpegurl", hlsMaster, K.HLS).first)
        assertEquals(S.OK, ChannelCheck.classify(200, null, hlsMedia, K.HLS).first)
        assertEquals(S.DEAD, ChannelCheck.classify(200, "application/x-mpegurl", "#EXTM3U\n", K.HLS).first)
        assertEquals(S.DEAD, ChannelCheck.classify(200, "text/html", "<!DOCTYPE html><html>", K.HLS).first)
        assertEquals(S.BLOCKED, ChannelCheck.classify(403, null, "", K.HLS).first)
        assertEquals(S.BLOCKED, ChannelCheck.classify(204, "application/x-mpegurl", "", K.HLS).first)
        assertEquals(S.DEAD, ChannelCheck.classify(404, null, "", K.DASH).first)
        assertEquals(S.DEAD, ChannelCheck.classify(503, null, "", K.HLS).first)
        assertEquals(S.OK, ChannelCheck.classify(200, "application/dash+xml", "<?xml?><MPD xmlns=", K.DASH).first)
        assertEquals(S.OK, ChannelCheck.classify(200, "audio/mpeg", "ID3...", K.DIRECT).first)
        assertEquals(S.DEAD, ChannelCheck.classify(200, "text/html; charset=utf-8", "<html>", K.DIRECT).first)
    }

    @Test
    fun resolvesFirstVariant() {
        assertEquals(
            "https://cdn.pt/live/low/index.m3u8",
            ChannelCheck.firstVariant("https://cdn.pt/live/master.m3u8?t=1", hlsMaster),
        )
        assertEquals(null, ChannelCheck.firstVariant("https://x/", hlsMedia))
    }

    @Test
    fun unzipsUndeclaredGzip() {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(hlsMaster.toByteArray()) }
        assertEquals(hlsMaster, String(ChannelCheck.gunzipIfNeeded(bos.toByteArray())))
        assertEquals("abc", String(ChannelCheck.gunzipIfNeeded("abc".toByteArray())))
    }

    @Test
    fun kindByPath() {
        assertEquals(ChannelCheck.Kind.HLS, ChannelCheck.kindOf("https://a/b/playlist.m3u8?token=1"))
        assertEquals(ChannelCheck.Kind.DASH, ChannelCheck.kindOf("https://a/manifest.mpd"))
        assertEquals(ChannelCheck.Kind.DIRECT, ChannelCheck.kindOf("https://radio/stream"))
    }

    @Test
    fun readsTsvGuide() {
        val now = 1_000_000L
        val r = EpgRepo.parseTsv(
            sequenceOf(
                "S\thttps://exemplo/epg.xml.xz",
                "N\tcanal x\tX.pt",
                "P\tRTP1.pt\t2000000\t3000000\tDepois\t",
                "P\tRTP1.pt\t900000\t2000000\tAgora\tDescrição",
                "P\tRTP1.pt\t100\t500\tJá passou\t",
            ),
            now,
        )
        assertEquals("https://exemplo/epg.xml.xz", r.source)
        assertEquals("X.pt", r.nameToId["canal x"])
        assertEquals(listOf("Agora", "Depois"), r.programmes.getValue("RTP1.pt").map { it.title })
        assertEquals("Descrição", r.programmes.getValue("RTP1.pt")[0].desc)
        assertEquals(null, r.programmes.getValue("RTP1.pt")[1].desc)
    }

    /** Verificação real: CHECK_M3U=/caminho/lista.m3u ./gradlew testReleaseUnitTest -i */
    @Test
    fun checksRealListIfProvided() {
        val path = System.getenv("CHECK_M3U") ?: return
        val channels = M3uParser.parse(File(path).readText()).filter { it.group in setOf("TV", "Rádios") }
        val pool = Executors.newFixedThreadPool(8)
        val t0 = System.currentTimeMillis()
        val results = channels.map { ch -> pool.submit<Pair<Channel, ChannelCheck.Status>> { ch to ChannelCheck.check(ch) } }
            .map { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()
        val by = results.groupBy { it.second.state }
        println("VERIFICADOS=${results.size} em ${(System.currentTimeMillis() - t0) / 1000}s " +
            by.mapValues { it.value.size })
        by[ChannelCheck.State.DEAD].orEmpty().take(12).forEach { (c, s) -> println("  DEAD ${c.name}: ${s.reason}") }
        by[ChannelCheck.State.BLOCKED].orEmpty().take(8).forEach { (c, s) -> println("  BLOCKED ${c.name}: ${s.reason}") }
        assertTrue(results.isNotEmpty())
    }
}
