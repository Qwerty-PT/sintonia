package pt.nuno.sintonia

import android.content.Context
import android.util.Log
import android.util.Xml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Guia de programação: transferido em segundo plano, filtrado e guardado em cache. */
object EpgRepo {

    enum class State { IDLE, LOADING, READY, EMPTY, ERROR }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    /** Muda sempre que há dados novos (para a interface se atualizar). */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    @Volatile private var data: Map<String, List<Programme>> = emptyMap()
    @Volatile private var nameToId: Map<String, String> = emptyMap()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private const val MAX_AGE = 12 * 60 * 60 * 1000L
    private const val KEEP_BEFORE = 2 * 60 * 60 * 1000L
    private const val KEEP_AFTER = 48 * 60 * 60 * 1000L

    val channelsWithGuide: Int get() = data.size

    // ---------- Consulta ----------

    private fun listFor(ch: Channel): List<Programme>? {
        val d = data
        ch.tvgId?.let { id -> d[id]?.let { return it } }
        return nameToId[ch.name.lowercase()]?.let { d[it] }
    }

    fun nowAndNext(ch: Channel, t: Long = System.currentTimeMillis()): Pair<Programme?, Programme?> {
        val list = listFor(ch) ?: return null to null
        val i = list.indexOfFirst { t < it.stop }
        if (i < 0) return null to null
        val p = list[i]
        return if (p.start <= t) p to list.getOrNull(i + 1) else null to p
    }

    /** Programa atual (se houver) seguido dos próximos, até [count] no total. */
    fun upcoming(ch: Channel, t: Long = System.currentTimeMillis(), count: Int = 5): List<Programme> {
        val list = listFor(ch) ?: return emptyList()
        val i = list.indexOfFirst { t < it.stop }
        if (i < 0) return emptyList()
        return list.subList(i, minOf(list.size, i + count))
    }

    // ---------- Carregar ----------

    /** Carrega a cache e, se estiver velha (ou [force]), transfere de novo. */
    fun update(ctx: Context, channels: List<Channel>, urls: List<String>, force: Boolean = false) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        job = scope.launch {
            val prefs = Prefs(app)
            val key = urls.joinToString(",")
            if (data.isEmpty()) loadCache(app)
            if (urls.isEmpty()) {
                if (data.isEmpty()) _state.value = State.EMPTY
                return@launch
            }
            val fresh = !force && prefs.epgSource == key &&
                System.currentTimeMillis() - prefs.epgUpdate < MAX_AGE && data.isNotEmpty()
            if (fresh) { _state.value = State.READY; return@launch }

            _state.value = State.LOADING
            val now = System.currentTimeMillis()
            val ids = channels.mapNotNull { it.tvgId }.toSet()
            val names = channels.map { it.name.lowercase() }.toSet()
            val progs = HashMap<String, List<Programme>>()
            val n2i = HashMap<String, String>()
            var ok = 0
            for (u in urls) {
                try {
                    Http.decompress(Http.open(u, 60_000)).use { input ->
                        val xpp = Xml.newPullParser()
                        xpp.setInput(input, null)
                        val r = EpgParser.parse(xpp, ids, names, now - KEEP_BEFORE, now + KEEP_AFTER)
                        r.programmes.forEach { (k, v) -> if (k !in progs) progs[k] = v }
                        r.nameToId.forEach { (k, v) -> n2i.putIfAbsent(k, v) }
                        ok++
                    }
                } catch (e: Exception) {
                    Log.w("Sintonia", "EPG falhou: $u", e)
                }
            }
            if (ok == 0) {
                _state.value = if (data.isNotEmpty()) State.READY else State.ERROR
                return@launch
            }
            data = progs
            nameToId = n2i
            saveCache(app)
            prefs.epgSource = key
            prefs.epgUpdate = System.currentTimeMillis()
            _state.value = if (progs.isEmpty()) State.EMPTY else State.READY
            _version.value++
        }
    }

    fun clear(ctx: Context) {
        job?.cancel()
        data = emptyMap(); nameToId = emptyMap()
        cacheFile(ctx).delete()
        Prefs(ctx).epgUpdate = 0L
        _state.value = State.IDLE
        _version.value++
    }

    // ---------- Cache (TSV simples) ----------

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "epg.tsv")

    private fun saveCache(ctx: Context) {
        runCatching {
            cacheFile(ctx).bufferedWriter().use { w ->
                nameToId.forEach { (n, id) -> w.write("N\t$n\t$id\n") }
                data.forEach { (id, list) ->
                    list.forEach { p -> w.write("P\t$id\t${p.start}\t${p.stop}\t${p.title}\t${p.desc.orEmpty()}\n") }
                }
            }
        }
    }

    private fun loadCache(ctx: Context) {
        val f = cacheFile(ctx)
        if (!f.exists()) return
        val now = System.currentTimeMillis()
        val progs = HashMap<String, MutableList<Programme>>()
        val n2i = HashMap<String, String>()
        runCatching {
            f.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val p = line.split('\t')
                    when {
                        p[0] == "N" && p.size >= 3 -> n2i[p[1]] = p[2]
                        p[0] == "P" && p.size >= 5 -> {
                            val stop = p[3].toLongOrNull() ?: return@forEach
                            if (stop < now) return@forEach
                            progs.getOrPut(p[1]) { ArrayList() } +=
                                Programme(p[2].toLongOrNull() ?: 0, stop, p[4], p.getOrNull(5)?.ifEmpty { null })
                        }
                    }
                }
            }
        }
        if (progs.isNotEmpty()) {
            data = progs
            nameToId = n2i
            _state.value = State.READY
            _version.value++
        }
    }
}
