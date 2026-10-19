package pt.nuno.sintonia

import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

object Http {

    /** Abre um GET seguindo redireções (incluindo http↔https). Fechar o stream devolvido. */
    fun open(start: String, readTimeoutMs: Int = 30_000): InputStream {
        var url = start
        repeat(6) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = readTimeoutMs
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", StreamFactory.DEFAULT_UA)
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                url = URL(URL(url), loc ?: throw IOException("Redireção sem destino")).toString()
                return@repeat
            }
            if (code !in 200..299) {
                c.disconnect()
                throw IOException("O servidor respondeu $code")
            }
            return c.inputStream
        }
        throw IOException("Demasiadas redireções")
    }

    fun getText(url: String): String = open(url, 20_000).bufferedReader(Charsets.UTF_8).use { it.readText() }

    /** Descomprime automaticamente .gz e .xz (pelos primeiros bytes), ou devolve tal como está. */
    fun decompress(raw: InputStream): InputStream {
        val s = BufferedInputStream(raw, 64 * 1024)
        s.mark(8)
        val b = ByteArray(6)
        val n = s.read(b)
        s.reset()
        return when {
            n >= 2 && b[0] == 0x1F.toByte() && b[1] == 0x8B.toByte() -> GZIPInputStream(s, 64 * 1024)
            n >= 6 && b[0] == 0xFD.toByte() && b[1] == '7'.code.toByte() && b[2] == 'z'.code.toByte() &&
                b[3] == 'X'.code.toByte() && b[4] == 'Z'.code.toByte() && b[5] == 0.toByte() ->
                BufferedInputStream(XZInputStream(s), 64 * 1024)
            else -> s
        }
    }
}
