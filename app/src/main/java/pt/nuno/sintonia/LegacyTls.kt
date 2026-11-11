package pt.nuno.sintonia

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Android antigo (API < 29: tablets Android 6, Fire TV Stick com Fire OS 5…) tem um repositório de CA que
 * nunca foi atualizado, e certificados atuais (ex.: raw.githubusercontent.com) falham com
 * "Trust anchor for certification path not found".
 *
 * Solução: as CA do sistema continuam a ser as primeiras; só se o sistema recusar um certificado é que se
 * tenta com o pacote atual da Mozilla incluído no APK (res/raw/cacert.pem, ferramentas/atualizar-ca.sh).
 * A validação nunca é enfraquecida: se ambos recusarem, a exceção original do sistema segue.
 * O HostnameVerifier não é tocado.
 */
object LegacyTls {
    private const val TAG = "Sintonia/TLS"

    /** Preenchidos só em API < 29 e se tudo correu bem (usados também pelo Coil/OkHttp). */
    @Volatile var trustManager: X509TrustManager? = null
        private set
    @Volatile var socketFactory: SSLSocketFactory? = null
        private set

    /**
     * Chamar em Application.onCreate, antes de qualquer pedido. É rápido: o pacote (~200 KB) só é lido
     * quando o sistema recusar um certificado, ou numa thread à parte logo a seguir ao arranque.
     */
    fun install(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 29) return
        val app = ctx.applicationContext
        try {
            val tm = CompositeTrustManager(systemTrustManager(), {
                app.resources.openRawResource(R.raw.cacert).use { trustManagerFromPem(it) }
            }) { e -> Log.e(TAG, "Pacote de CA incluído não carregou; fica só o do sistema", e) }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
            HttpsURLConnection.setDefaultSSLSocketFactory(ssl.socketFactory)
            trustManager = tm
            socketFactory = ssl.socketFactory
            // Ler o pacote já, fora da thread principal, para o primeiro pedido que precise dele não esperar
            Thread({ tm.bundleOrNull() }, "sintonia-ca").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Não foi possível instalar o TrustManager composto; fica o do sistema", e)
        }
    }

    fun systemTrustManager(): X509TrustManager {
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        f.init(null as KeyStore?)
        return f.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** Certificados de um ficheiro PEM com vários blocos (texto à volta é ignorado, como no cacert.pem). */
    fun parsePem(input: InputStream): List<X509Certificate> {
        val text = input.bufferedReader(Charsets.US_ASCII).readText()
        val cf = CertificateFactory.getInstance("X.509")
        return Regex("-----BEGIN CERTIFICATE-----[^-]+-----END CERTIFICATE-----").findAll(text).map {
            cf.generateCertificate(ByteArrayInputStream(it.value.toByteArray(Charsets.US_ASCII))) as X509Certificate
        }.toList()
    }

    fun trustManagerFromPem(input: InputStream): X509TrustManager {
        val certs = parsePem(input)
        require(certs.isNotEmpty()) { "pacote de CA vazio" }
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        certs.forEachIndexed { i, c -> ks.setCertificateEntry("ca$i", c) }
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        f.init(ks)
        return f.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}

/**
 * Valida primeiro com [system]; só se esse recusar, com o pacote incluído (carregado uma vez, à primeira
 * necessidade). Se o pacote também recusar — ou não tiver carregado — lança a exceção ORIGINAL do sistema.
 */
class CompositeTrustManager(
    private val system: X509TrustManager,
    private val loadBundle: () -> X509TrustManager,
    private val onLoadError: (Throwable) -> Unit = {},
) : X509TrustManager {

    private val bundle: X509TrustManager? by lazy {
        try { loadBundle() } catch (t: Throwable) { onLoadError(t); null }
    }
    @Volatile private var bundleLoaded = false

    fun bundleOrNull(): X509TrustManager? = bundle.also { bundleLoaded = true }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        system.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        try {
            system.checkServerTrusted(chain, authType)
        } catch (original: CertificateException) {
            val extra = bundleOrNull() ?: throw original
            try {
                extra.checkServerTrusted(chain, authType)
            } catch (_: CertificateException) {
                throw original
            }
        }
    }

    /** Não obriga a ler o pacote (pode ser chamado na thread principal); junta-o se já estiver lido. */
    override fun getAcceptedIssuers(): Array<X509Certificate> {
        val sys = system.acceptedIssuers
        val extra = if (bundleLoaded) bundle?.acceptedIssuers.orEmpty() else emptyArray()
        return if (extra.isEmpty()) sys else (sys.toList() + extra).distinct().toTypedArray()
    }
}
