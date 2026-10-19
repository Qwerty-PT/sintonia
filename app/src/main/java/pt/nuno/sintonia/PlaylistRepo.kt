package pt.nuno.sintonia

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Lista em memória + cache em disco (abre logo, mesmo sem rede). */
object PlaylistRepo {

    @Volatile
    var channels: List<Channel> = emptyList()

    /** Guia indicado no cabeçalho da lista (url-tvg). */
    @Volatile
    var epgUrls: List<String> = emptyList()

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "playlist.m3u")

    suspend fun loadCache(ctx: Context): List<Channel>? = withContext(Dispatchers.IO) {
        val f = cacheFile(ctx)
        if (!f.exists()) return@withContext null
        runCatching {
            val text = f.readText()
            epgUrls = M3uParser.epgUrls(text)
            M3uParser.parse(text)
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    suspend fun download(ctx: Context, url: String): List<Channel> = withContext(Dispatchers.IO) {
        val text = Http.getText(url)
        val list = M3uParser.parse(text)
        if (list.isEmpty()) throw IOException("A lista não tem canais reconhecíveis")
        epgUrls = M3uParser.epgUrls(text)
        cacheFile(ctx).writeText(text)
        Prefs(ctx).lastUpdate = System.currentTimeMillis()
        list
    }

    fun clearCache(ctx: Context) {
        cacheFile(ctx).delete()
        Prefs(ctx).lastUpdate = 0L
    }

}

/** Lista que o leitor está a percorrer (canal anterior/seguinte). */
object Session {
    var list: List<Channel> = emptyList()
    var index: Int = 0
}
