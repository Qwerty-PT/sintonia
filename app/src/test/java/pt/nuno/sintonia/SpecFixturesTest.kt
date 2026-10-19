package pt.nuno.sintonia

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File

/**
 * Corre os leitores do APK contra os exemplos partilhados em spec/fixtures.
 * A versão desktop usa os mesmos ficheiros: se um lado interpretar diferente, um teste falha.
 */
class SpecFixturesTest {

    private val spec = File("../spec/fixtures")

    private fun json(path: String) = JSONObject(File(spec, path).readText())
    private fun JSONObject.str(k: String): String? = if (isNull(k)) null else getString(k)

    @Test
    fun m3uBasico() {
        val text = File(spec, "m3u/basico.m3u").readText()
        val exp = json("m3u/basico.expected.json")

        val urls = exp.getJSONArray("epgUrls")
        assertEquals((0 until urls.length()).map { urls.getString(it) }, M3uParser.epgUrls(text))

        val list = M3uParser.parse(text)
        val chans = exp.getJSONArray("channels")
        assertEquals("número de canais", chans.length(), list.size)
        for (i in 0 until chans.length()) {
            val e = chans.getJSONObject(i)
            val c = list[i]
            val at = "canal $i (${e.getString("name")})"
            assertEquals(at, e.getString("name"), c.name)
            assertEquals(at, e.getString("group"), c.group)
            assertEquals(at, e.getString("url"), c.url)
            assertEquals(at, e.str("tvgId"), c.tvgId)
            assertEquals(at, e.str("logo"), c.logo)
            assertEquals(at, e.getBoolean("isRadio"), c.isRadio)
            assertEquals(at, e.str("userAgent"), c.userAgent)
            val h = e.getJSONObject("headers")
            assertEquals(at, h.keys().asSequence().associateWith { h.getString(it) }, c.headers)
            assertEquals(at, e.str("drmType"), c.drmType)
            assertEquals(at, e.str("drmKey"), c.drmKey)
            assertEquals(at, e.getString("key"), c.key)
        }
    }

    @Test
    fun epgBasico() {
        val exp = json("epg/basico.expected.json")
        val input = exp.getJSONObject("input")
        fun set(a: JSONArray) = (0 until a.length()).map { a.getString(it) }.toSet()

        val xpp = KXmlParser().apply { setInput(File(spec, "epg/basico.xml").reader()) }
        val r = EpgParser.parse(
            xpp,
            set(input.getJSONArray("wantedIds")),
            set(input.getJSONArray("wantedNames")),
            EpgParser.parseTime(input.getString("from")),
            EpgParser.parseTime(input.getString("to")),
        )

        val n2i = exp.getJSONObject("nameToId")
        assertEquals(n2i.keys().asSequence().associateWith { n2i.getString(it) }, r.nameToId)

        val progs = exp.getJSONObject("programmes")
        assertEquals(progs.keys().asSequence().toSet(), r.programmes.keys)
        progs.keys().forEach { id ->
            val arr = progs.getJSONArray(id)
            val got = r.programmes.getValue(id)
            assertEquals("programas de $id", arr.length(), got.size)
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val p = got[i]
                assertEquals("$id[$i]", e.getLong("start"), p.start)
                assertEquals("$id[$i]", e.getLong("stop"), p.stop)
                assertEquals("$id[$i]", e.getString("title"), p.title)
                assertEquals("$id[$i]", e.str("desc"), p.desc)
            }
        }
    }

    // spec/fixtures/listas: o leitor do APK ainda não existe (backlog passo 2). Por agora é verificado
    // só no configurador: node spec/check-configurador.mjs
}
