package pt.nuno.sintonia

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("sintonia", Context.MODE_PRIVATE)

    var playlistUrl: String
        get() = sp.getString("url", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_URL
        set(v) = sp.edit().putString("url", v.trim()).apply()

    /** Ao abrir a app, ir diretamente para o último canal visto (por omissão abre o menu principal). */
    var autoplayLast: Boolean
        get() = sp.getBoolean("autoplay_v2", false) // chave nova: até à 1.9 a omissão era "ligado"
        set(v) = sp.edit().putBoolean("autoplay_v2", v).apply()

    /** Na lista sobre o vídeo: pré-visualizar o canal focado sem carregar em OK. */
    var autoPreview: Boolean
        get() = sp.getBoolean("auto_preview", false)
        set(v) = sp.edit().putBoolean("auto_preview", v).apply()

    var lastKey: String?
        get() = sp.getString("last_key", null)
        set(v) = sp.edit().putString("last_key", v).apply()

    var lastGroup: String?
        get() = sp.getString("last_group", null)
        set(v) = sp.edit().putString("last_group", v).apply()

    var lastUpdate: Long
        get() = sp.getLong("last_update", 0L)
        set(v) = sp.edit().putLong("last_update", v).apply()

    /** Endereço do guia definido à mão (vazio = o da lista, url-tvg). */
    var epgUrl: String
        get() = sp.getString("epg_url", "") ?: ""
        set(v) = sp.edit().putString("epg_url", v.trim()).apply()

    var epgUpdate: Long
        get() = sp.getLong("epg_update", 0L)
        set(v) = sp.edit().putLong("epg_update", v).apply()

    var epgSource: String
        get() = sp.getString("epg_source", "") ?: ""
        set(v) = sp.edit().putString("epg_source", v).apply()

    /** Grupos escondidos (os novos aparecem por omissão). */
    var hiddenGroups: Set<String>
        get() = sp.getStringSet("hidden_groups", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("hidden_groups", HashSet(v)).apply()

    /** Procurar versões novas no GitHub ao abrir a app (no máximo de 6 em 6 h). */
    var autoUpdate: Boolean
        get() = sp.getBoolean("auto_update", true)
        set(v) = sp.edit().putBoolean("auto_update", v).apply()

    var updateChecked: Long
        get() = sp.getLong("update_checked", 0L)
        set(v) = sp.edit().putLong("update_checked", v).apply()

    /** "Mais tarde": não voltar a perguntar por esta versão durante 24 h. */
    var updateSnoozedCode: Int
        get() = sp.getInt("update_snoozed_code", 0)
        set(v) = sp.edit().putInt("update_snoozed_code", v).apply()

    var updateSnoozedAt: Long
        get() = sp.getLong("update_snoozed_at", 0L)
        set(v) = sp.edit().putLong("update_snoozed_at", v).apply()

    /** Tempo até à imagem ao mudar de canal: último e média (para comparar melhorias). */
    val zapLastMs: Long get() = sp.getLong("zap_last", 0L)
    val zapAvgMs: Long get() = sp.getLong("zap_avg", 0L)
    val zapCount: Int get() = sp.getInt("zap_count", 0)

    fun recordZap(ms: Long) {
        val n = minOf(zapCount, 49) // média das últimas ~50
        val avg = if (n == 0) ms else (zapAvgMs * n + ms) / (n + 1)
        sp.edit().putLong("zap_last", ms).putLong("zap_avg", avg).putInt("zap_count", zapCount + 1).apply()
    }

    /** Verificação de canais: esconder os que estão em baixo (por omissão só ficam marcados). */
    var hideDead: Boolean
        get() = sp.getBoolean("hide_dead", false)
        set(v) = sp.edit().putBoolean("hide_dead", v).apply()

    var favourites: Set<String>
        get() = sp.getStringSet("fav", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("fav", HashSet(v)).apply()

    companion object {
        /** Lista M3UPT (LITUATUI) — TV e rádio portuguesas, emissões públicas e oficiais. */
        const val DEFAULT_URL = "https://raw.githubusercontent.com/LITUATUI/M3UPT/main/M3U/M3UPT.m3u"
    }
}
