package pt.nuno.sintonia

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import okhttp3.OkHttpClient

class SintoniaApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // Antes de qualquer pedido HTTPS (lista, guia, atualizações, verificação, leitor)
        LegacyTls.install(this)
    }

    /** Logótipos (Coil usa OkHttp, não o HttpsURLConnection): mesmas CA em Android antigo. */
    override fun newImageLoader(): ImageLoader {
        val b = ImageLoader.Builder(this)
        val sf = LegacyTls.socketFactory
        val tm = LegacyTls.trustManager
        if (sf != null && tm != null) b.okHttpClient { OkHttpClient.Builder().sslSocketFactory(sf, tm).build() }
        return b.build()
    }
}
