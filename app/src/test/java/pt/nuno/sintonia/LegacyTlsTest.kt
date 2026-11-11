package pt.nuno.sintonia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.X509TrustManager

/**
 * TrustManager composto (LegacyTls): sistema primeiro, pacote incluído só se o sistema recusar,
 * e nunca aceita o que ambos recusam.
 *
 * Certificados de teste em src/test/resources/tls (só certificados públicos, sem chaves):
 *  - ca-sistema.pem: faz de "repositório antigo do sistema" (não assinou nada)
 *  - ca-nova.pem: CA "recente" que o sistema não conhece (faz de pacote incluído)
 *  - servidor-valido.pem / servidor-expirado.pem: assinados pela ca-nova
 */
class LegacyTlsTest {

    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream("tls/$name")!!
    private fun cert(name: String) = res(name).use { LegacyTls.parsePem(it).single() }
    private fun tm(vararg pems: String): X509TrustManager =
        pems.joinToString("\n") { n -> res(n).bufferedReader().readText() }.byteInputStream()
            .let { LegacyTls.trustManagerFromPem(it) }

    private val valido = arrayOf(cert("servidor-valido.pem"), cert("ca-nova.pem"))
    private val expirado = arrayOf(cert("servidor-expirado.pem"), cert("ca-nova.pem"))

    /** Guarda a exceção que o "sistema" lançou, para confirmar que é essa que sai. */
    private class Recording(private val inner: X509TrustManager) : X509TrustManager by inner {
        var thrown: CertificateException? = null
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            try { inner.checkServerTrusted(chain, authType) } catch (e: CertificateException) { thrown = e; throw e }
        }
    }

    @Test
    fun bundledMozillaCaParses() {
        val f = File("src/main/res/raw/cacert.pem")
        val certs = f.inputStream().use { LegacyTls.parsePem(it) }
        assertTrue("esperava mais de 100 CA, veio ${certs.size}", certs.size > 100)
        // raízes usadas hoje por GitHub / Let's Encrypt / Sectigo
        val names = certs.map { it.subjectX500Principal.name }
        for (cn in listOf("CN=ISRG Root X1", "CN=USERTrust RSA Certification Authority", "CN=DigiCert Global Root G2"))
            assertTrue("falta $cn", names.any { it.startsWith(cn) })
        f.inputStream().use { LegacyTls.trustManagerFromPem(it) } // constrói sem erro
    }

    @Test
    fun systemAcceptsBundleNeverLoaded() {
        val loads = AtomicInteger()
        val c = CompositeTrustManager(tm("ca-nova.pem"), { loads.incrementAndGet(); tm("ca-nova.pem") })
        c.checkServerTrusted(valido, "RSA")
        assertEquals(0, loads.get())
    }

    @Test
    fun systemRejectsBundleAccepts() {
        val c = CompositeTrustManager(tm("ca-sistema.pem"), { tm("ca-nova.pem") })
        c.checkServerTrusted(valido, "RSA") // não lança
    }

    @Test
    fun bothRejectThrowsOriginal() {
        val sys = Recording(tm("ca-sistema.pem"))
        val c = CompositeTrustManager(sys, { tm("ca-sistema.pem") })
        try { c.checkServerTrusted(valido, "RSA"); fail("devia recusar") } catch (e: CertificateException) {
            assertSame(sys.thrown, e)
        }
    }

    @Test
    fun expiredStillRejectedEvenWithBundle() {
        val sys = Recording(tm("ca-sistema.pem"))
        val c = CompositeTrustManager(sys, { tm("ca-nova.pem") })
        try { c.checkServerTrusted(expirado, "RSA"); fail("expirado devia ser recusado") } catch (e: CertificateException) {
            assertSame(sys.thrown, e)
        }
        // e o sistema "novo" (que conhece a CA) também o recusa — a validação de datas não é desligada
        try { CompositeTrustManager(tm("ca-nova.pem"), { tm("ca-nova.pem") }).checkServerTrusted(expirado, "RSA"); fail() }
        catch (_: CertificateException) { }
    }

    @Test
    fun bundleFailsToLoadSystemErrorAndLogged() {
        val sys = Recording(tm("ca-sistema.pem"))
        var logged: Throwable? = null
        val c = CompositeTrustManager(sys, { throw IllegalStateException("sem pacote") }, { logged = it })
        try { c.checkServerTrusted(valido, "RSA"); fail() } catch (e: CertificateException) { assertSame(sys.thrown, e) }
        assertTrue(logged is IllegalStateException)
    }

    @Test
    fun acceptedIssuersDoNotForceLoad() {
        val loads = AtomicInteger()
        val c = CompositeTrustManager(tm("ca-sistema.pem"), { loads.incrementAndGet(); tm("ca-nova.pem") })
        assertEquals(1, c.acceptedIssuers.size)
        assertEquals(0, loads.get())
        c.bundleOrNull()
        assertEquals(2, c.acceptedIssuers.size)
        assertEquals(1, loads.get())
    }
}
