package pt.nuno.sintonia

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * Verificação automática de canais, feita na própria TV (com o IP real — a mesma resposta que o leitor
 * vai ter, incluindo bloqueios geográficos).
 *
 * Por canal só se lê o "índice" do stream (alguns KB): HLS → lista principal + 1.ª variante,
 * DASH → manifesto, áudio/vídeo direto → os primeiros 2 KB. Nunca descarrega vídeo.
 */
object ChannelCheck {

    enum class State { OK, DEAD, BLOCKED }

    data class Status(val state: State, val checkedAt: Long, val reason: String = "")

    private val statuses = ConcurrentHashMap<String, Status>()

    data class Progress(val running: Boolean, val done: Int, val total: Int)

    private val _progress = MutableStateFlow(Progress(false, 0, 0))
    val progress: StateFlow<Progress> = _progress

    /** Muda quando há resultados novos (a lista redesenha as marcas). */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var loaded = false

    private const val OK_TTL = 12 * 60 * 60 * 1000L
    private const val FAIL_TTL = 60 * 60 * 1000L
    private const val PARALLEL = 4

    fun status(ch: Channel): Status? = statuses[ch.key]
    fun isDead(ch: Channel): Boolean = statuses[ch.key]?.state == State.DEAD

    // ---------- Resultados vindos do leitor (os mais fiáveis) ----------

    fun markOk(ctx: Context, ch: Channel) = put(ctx, ch, Status(State.OK, System.currentTimeMillis()))

    fun markFailed(ctx: Context, ch: Channel, reason: String) =
        put(ctx, ch, Status(State.DEAD, System.currentTimeMillis(), reason))

    private fun put(ctx: Context, ch: Channel, s: Status) {
        val old = statuses.put(ch.key, s)
        if (old?.state != s.state) { _version.value++; save(ctx.applicationContext) }
    }

    // ---------- Verificação em segundo plano ----------

    /**
     * Verifica os canais que ainda não foram vistos (ou cujo resultado envelheceu), pela ordem dada
     * (favoritos e grupo atual primeiro). [force] verifica todos.
     */
    fun start(ctx: Context, channels: List<Channel>, force: Boolean = false) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        job = scope.launch {
            load(app)
            val now = System.currentTimeMillis()
            val todo = channels.distinctBy { it.key }.filter { ch ->
                val s = statuses[ch.key]
                force || s == null || now - s.checkedAt > (if (s.state == State.OK) OK_TTL else FAIL_TTL)
            }
            if (todo.isEmpty()) return@launch
            var done = 0
            _progress.value = Progress(true, 0, todo.size)
            val gate = Semaphore(PARALLEL)
            try {
                todo.chunked(40).forEach { batch ->
                    if (!isActive) return@forEach
                    batch.map { ch ->
                        async {
                            gate.withPermit {
                                if (!isActive) return@withPermit
                                val s = check(ch)
                                val old = statuses.put(ch.key, s)
                                synchronized(this@ChannelCheck) { done++ }
                                _progress.value = Progress(true, done, todo.size)
                            }
                        }
                    }.awaitAll()
                    _version.value++ // a lista redesenha as marcas uma vez por lote, não por canal
                    save(app)
                }
            } finally {
                _progress.value = Progress(false, done, todo.size)
                save(app)
            }
        }
    }

    /** Pára a verificação (ex.: ao começar a ver um canal, para não gastar rede). */
    fun stop() { job?.cancel() }

    fun check(ch: Channel): Status {
        val first = checkOnce(ch)
        // erros de rede podem ser passageiros: só conta como morto se falhar duas vezes
        if (first.state != State.DEAD || first.reason.startsWith("já não existe")) return first
        Thread.sleep(1_500)
        return checkOnce(ch)
    }

    private fun checkOnce(ch: Channel): Status {
        val now = System.currentTimeMillis()
        return try {
            val r = fetch(ch, ch.url, if (kindOf(ch.url) == Kind.DIRECT) 2048 else 256 * 1024)
            var verdict = classify(r.code, r.contentType, r.body, kindOf(ch.url))
            // HLS principal: confirmar também a 1.ª variante (é aí que os canais "meio mortos" falham)
            if (verdict.first == State.OK && r.body.contains("#EXT-X-STREAM-INF")) {
                firstVariant(r.finalUrl, r.body)?.let { v ->
                    val rv = fetch(ch, v, 256 * 1024)
                    verdict = classify(rv.code, rv.contentType, rv.body, Kind.HLS)
                }
            }
            Status(verdict.first, now, verdict.second)
        } catch (e: UnknownHostException) {
            Status(State.DEAD, now, "servidor não existe")
        } catch (e: SocketTimeoutException) {
            Status(State.DEAD, now, "sem resposta")
        } catch (e: IOException) {
            Status(State.DEAD, now, e.message ?: "erro de rede")
        }
    }

    enum class Kind { HLS, DASH, DIRECT }

    fun kindOf(url: String): Kind {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".mpd") -> Kind.DASH
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> Kind.HLS
            else -> Kind.DIRECT
        }
    }

    /** Decide pelo código HTTP e pelo início do conteúdo. Função pura (testada). */
    fun classify(code: Int, contentType: String?, body: String, kind: Kind): Pair<State, String> {
        if (code == 403 || code == 451) return State.BLOCKED to "bloqueado ($code) — talvez só com IP de outro país"
        // RTP e outros respondem 204 (vazio) a IPs de fora de Portugal
        if (code == 204) return State.BLOCKED to "sem conteúdo para este país (204)"
        if (code == 401) return State.BLOCKED to "pede autenticação (401)"
        if (code == 404 || code == 410) return State.DEAD to "já não existe ($code)"
        if (code !in 200..299) return State.DEAD to "o servidor respondeu $code"
        val head = body.trimStart().take(64)
        val html = contentType?.contains("text/html", true) == true || head.startsWith("<!doctype", true) ||
            head.startsWith("<html", true)
        return when (kind) {
            Kind.HLS -> when {
                head.startsWith("#EXTM3U") && (body.contains("#EXTINF") || body.contains("#EXT-X-STREAM-INF") ||
                    body.contains("#EXT-X-TARGETDURATION")) -> State.OK to ""
                head.startsWith("#EXTM3U") -> State.DEAD to "lista de vídeo vazia"
                else -> State.DEAD to "resposta não é um stream"
            }
            Kind.DASH -> if (body.contains("<MPD")) State.OK to "" else State.DEAD to "resposta não é um stream"
            Kind.DIRECT -> if (html) State.DEAD to "resposta é uma página web" else State.OK to ""
        }
    }

    /** URL absoluto da primeira variante de uma lista HLS principal. */
    fun firstVariant(base: String, body: String): String? {
        val lines = body.lineSequence().map { it.trim() }.toList()
        val i = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
        if (i < 0) return null
        val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
        return runCatching { URL(URL(base), uri).toString() }.getOrNull()
    }

    private class Resp(val code: Int, val contentType: String?, val body: String, val finalUrl: String)

    private fun fetch(ch: Channel, start: String, maxBytes: Int): Resp {
        var url = start
        repeat(5) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 6_000
                c.readTimeout = 8_000
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", ch.userAgent ?: StreamFactory.DEFAULT_UA)
                ch.headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (maxBytes <= 4096) c.setRequestProperty("Range", "bytes=0-${maxBytes - 1}")
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: return Resp(code, null, "", url)
                    url = URL(URL(url), loc).toString()
                    return@repeat
                }
                val stream = if (code >= 400) c.errorStream else c.inputStream
                val bytes = stream?.use { input ->
                    val buf = ByteArray(maxBytes)
                    var n = 0
                    while (n < maxBytes) {
                        val r = input.read(buf, n, maxBytes - n)
                        if (r < 0) break
                        n += r
                    }
                    buf.copyOf(n)
                } ?: ByteArray(0)
                return Resp(if (code == 206) 200 else code, c.contentType, String(gunzipIfNeeded(bytes), Charsets.UTF_8), url)
            } finally {
                c.disconnect()
            }
        }
        throw IOException("demasiadas redireções")
    }

    /** Alguns servidores (ex.: RTP) mandam a lista em gzip sem o dizer no cabeçalho. */
    internal fun gunzipIfNeeded(b: ByteArray): ByteArray {
        if (b.size < 2 || b[0] != 0x1f.toByte() || b[1] != 0x8b.toByte()) return b
        return runCatching {
            java.util.zip.GZIPInputStream(b.inputStream()).use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (out.size() < 256 * 1024) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        }.getOrElse { b }
    }

    // ---------- Pré-aquecer (zapping mais rápido) ----------

    /**
     * Resolve o DNS e abre a ligação TLS aos canais vizinhos, para que ▲▼ arranque mais depressa.
     * Lê só o índice do stream (KB) e deixa a ligação pronta a reutilizar.
     */
    fun warm(channels: List<Channel>) {
        scope.launch {
            channels.distinctBy { it.url }.forEach { ch ->
                runCatching {
                    if (kindOf(ch.url) == Kind.DIRECT) InetAddress.getByName(URL(ch.url).host)
                    else fetch(ch, ch.url, 64 * 1024)
                }
            }
        }
    }

    /** Só DNS (barato) para muitos canais de uma vez — ex.: o grupo aberto. */
    fun warmDns(channels: List<Channel>) {
        scope.launch {
            channels.mapNotNull { runCatching { URL(it.url).host }.getOrNull() }.distinct().take(60)
                .forEach { h -> runCatching { InetAddress.getByName(h) } }
        }
    }

    // ---------- Guardar ----------

    private fun file(ctx: Context) = File(ctx.filesDir, "canais.tsv")

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            file(ctx).takeIf { it.exists() }?.forEachLine { line ->
                val p = line.split('\t')
                if (p.size >= 3) {
                    val st = runCatching { State.valueOf(p[1]) }.getOrNull() ?: return@forEachLine
                    statuses[p[0]] = Status(st, p[2].toLongOrNull() ?: 0, p.getOrNull(3).orEmpty())
                }
            }
        }
        _version.value++
    }

    @Synchronized
    private fun save(ctx: Context) {
        runCatching {
            file(ctx).bufferedWriter().use { w ->
                statuses.forEach { (k, s) ->
                    w.write("${k.replace('\t', ' ')}\t${s.state}\t${s.checkedAt}\t${s.reason.replace('\t', ' ')}\n")
                }
            }
        }
    }
}
