package pt.nuno.sintonia

import android.content.Context

/** Grupos e canais por grupo (incluindo ★ Favoritos), partilhado entre ecrãs. */
object Catalog {

    fun favLabel(ctx: Context): String = ctx.getString(R.string.favourites)

    fun groups(ctx: Context): List<String> {
        val all = PlaylistRepo.channels
        val favs = Prefs(ctx).favourites
        val hasFav = all.any { it.key in favs }
        val hidden = Prefs(ctx).hiddenGroups
        val names = LinkedHashSet<String>()
        all.forEach { if (it.group !in hidden) names += it.group }
        return (if (hasFav) listOf(favLabel(ctx)) else emptyList()) + names
    }

    /** Todos os grupos da lista, pela ordem original (incluindo escondidos). */
    fun allGroups(): List<String> {
        val names = LinkedHashSet<String>()
        PlaylistRepo.channels.forEach { names += it.group }
        return names.toList()
    }

    fun channelsFor(ctx: Context, group: String): List<Channel> {
        val list = if (group == favLabel(ctx)) {
            val favs = Prefs(ctx).favourites
            PlaylistRepo.channels.filter { it.key in favs }
        } else PlaylistRepo.channels.filter { it.group == group }
        return if (Prefs(ctx).hideDead) list.filterNot { ChannelCheck.isDead(it) } else list
    }

    /** Liga/desliga favorito; devolve true se ficou favorito. */
    fun toggleFavourite(ctx: Context, ch: Channel): Boolean {
        val prefs = Prefs(ctx)
        val favs = prefs.favourites.toMutableSet()
        val added = favs.add(ch.key) || run { favs.remove(ch.key); false }
        prefs.favourites = favs
        return added
    }
}
