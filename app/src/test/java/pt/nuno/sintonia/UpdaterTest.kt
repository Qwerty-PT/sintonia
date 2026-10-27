package pt.nuno.sintonia

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class UpdaterTest {

    @Test
    fun readsUpdateJson() {
        val i = Updater.parseInfo(
            """
            {"versionCode": 10, "versionName": "1.9",
             "apk": "https://github.com/Qwerty-PT/sintonia/releases/download/v1.9/Sintonia-1.9.apk",
             "sha256": "ABCDEF0123", "notes": "Atualização automática"}
            """.trimIndent()
        )
        assertEquals(10, i.versionCode)
        assertEquals("1.9", i.versionName)
        assertEquals("abcdef0123", i.sha256) // comparação sem maiúsculas
        assertEquals("Atualização automática", i.notes)
    }

    @Test
    fun sha256OfFile() {
        val f = File.createTempFile("sintonia", ".bin").apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Updater.sha256(f))
        f.delete()
    }
}
