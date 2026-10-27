package pt.nuno.sintonia

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Atualização automática a partir das GitHub Releases (repositório público).
 *
 * Cada release tem um `update.json` (gerado pelo GitHub Actions) com a versão, o endereço do APK e o SHA-256.
 * A app lê sempre a última release, descarrega o APK em segundo plano, confirma que é mesmo o Sintonia,
 * que é mais recente e que o SHA-256 bate certo, e só então propõe "Instalar" (o Android pede sempre 1 OK).
 */
object Updater {

    const val REPO = "Qwerty-PT/sintonia"
    const val UPDATE_URL = "https://github.com/$REPO/releases/latest/download/update.json"
    private const val CHECK_EVERY = 15 * 60 * 1000L // é só um ficheiro de ~1 KB

    data class Info(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val notes: String,
    )

    /** Atualização já descarregada e verificada, pronta a instalar. */
    data class Ready(val info: Info, val apk: File)

    sealed class Result {
        data class Available(val ready: Ready) : Result()
        object UpToDate : Result()
        data class Failed(val message: String) : Result()
    }

    @Volatile
    var pending: Ready? = null
        private set

    fun currentCode(ctx: Context): Int = BuildConfig.VERSION_CODE

    fun shouldCheck(ctx: Context): Boolean {
        val p = Prefs(ctx)
        return p.autoUpdate && System.currentTimeMillis() - p.updateChecked > CHECK_EVERY
    }

    /** Lê o update.json; se houver versão nova, descarrega e verifica o APK. */
    suspend fun check(ctx: Context): Result = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        try {
            val info = parseInfo(Http.getText(UPDATE_URL))
            Prefs(app).updateChecked = System.currentTimeMillis()
            if (info.versionCode <= currentCode(app)) {
                cleanup(app)
                return@withContext Result.UpToDate
            }
            pending?.let { if (it.info.versionCode == info.versionCode && it.apk.exists()) return@withContext Result.Available(it) }

            val apk = File(updateDir(app), "Sintonia-${info.versionName}.apk")
            if (!(apk.exists() && sha256(apk).equals(info.sha256, true))) {
                download(info.apkUrl, apk)
            }
            verify(app, info, apk)
            val ready = Ready(info, apk)
            pending = ready
            Result.Available(ready)
        } catch (e: Exception) {
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    fun parseInfo(json: String): Info {
        val o = JSONObject(json)
        return Info(
            versionCode = o.getInt("versionCode"),
            versionName = o.getString("versionName"),
            apkUrl = o.getString("apk"),
            sha256 = o.getString("sha256").lowercase(),
            notes = o.optString("notes", ""),
        )
    }

    private fun updateDir(ctx: Context): File {
        // Android 7+: content:// via FileProvider (cache interna). Android 5–6: o instalador só lê ficheiros
        // públicos, por isso usa-se a cache externa da app.
        val base = if (Build.VERSION.SDK_INT >= 24) ctx.cacheDir else (ctx.externalCacheDir ?: ctx.cacheDir)
        return File(base, "updates").apply { mkdirs() }
    }

    private fun cleanup(ctx: Context) {
        pending = null
        updateDir(ctx).listFiles()?.forEach { it.delete() }
    }

    private fun download(url: String, dest: File) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        Http.open(url, 60_000).use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true); tmp.delete()
        }
    }

    private fun verify(ctx: Context, info: Info, apk: File) {
        val hash = sha256(apk)
        if (!hash.equals(info.sha256, true)) {
            apk.delete()
            throw IOException("o ficheiro descarregado não corresponde ao publicado (SHA-256)")
        }
        @Suppress("DEPRECATION")
        val pi: PackageInfo = ctx.packageManager.getPackageArchiveInfo(apk.path, 0)
            ?: throw IOException("o ficheiro descarregado não é um APK válido")
        if (pi.packageName != ctx.packageName) throw IOException("o APK não é do Sintonia")
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
        if (code <= currentCode(ctx)) throw IOException("o APK não é mais recente")
        if (Build.VERSION.SDK_INT < 24) apk.setReadable(true, false)
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Android 8+: a app precisa de autorização para "instalar apps desconhecidas". */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Abre o ecrã do sistema para autorizar o Sintonia a instalar atualizações. Devolve false se não existir. */
    fun openInstallPermission(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
        return try { activity.startActivity(i); true } catch (e: Exception) { false }
    }

    /** Abre o instalador do Android com o APK descarregado. */
    fun install(activity: Activity, ready: Ready): Boolean {
        val apk = ready.apk
        if (!apk.exists()) return false
        val uri = if (Build.VERSION.SDK_INT >= 24) {
            FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
        } else Uri.fromFile(apk)
        @Suppress("DEPRECATION")
        val i = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, false)
        }
        return try {
            activity.startActivity(i); true
        } catch (e: Exception) {
            // alguns sistemas só aceitam ACTION_VIEW
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }); true
            } catch (e2: Exception) { false }
        }
    }
}
