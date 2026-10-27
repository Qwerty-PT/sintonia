package pt.nuno.sintonia

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var groupsRv: RecyclerView
    private lateinit var channelsRv: RecyclerView
    private lateinit var loading: ProgressBar
    private lateinit var empty: TextView
    private lateinit var status: TextView
    private lateinit var groupAdapter: GroupAdapter
    private lateinit var channelAdapter: ChannelAdapter
    /** Painel do guia (só existe em paisagem / TV). */
    private var guide: GuidePanel? = null

    private var isTv = false
    private var groupsVertical = false
    private var groups: List<String> = emptyList()
    private var byGroup: Map<String, List<Channel>> = emptyMap()
    private var refreshing = false
    private var autoplayDone = false

    private val favLabel by lazy { getString(R.string.favourites) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        isTv = Device.isTv(this)
        groupsVertical = resources.getBoolean(R.bool.groups_vertical)
        // Só arranca sozinho no 1.º arranque (não ao rodar o ecrã nem ao voltar do leitor)
        autoplayDone = savedInstanceState != null || startedOnce
        startedOnce = true

        groupsRv = findViewById(R.id.groups)
        channelsRv = findViewById(R.id.channels)
        loading = findViewById(R.id.loading)
        empty = findViewById(R.id.empty)
        status = findViewById(R.id.status)

        groupAdapter = GroupAdapter(groupsVertical, onLongClick = { hideGroup(it) }) { showGroup(it) }
        groupsRv.layoutManager = LinearLayoutManager(
            this, if (groupsVertical) RecyclerView.VERTICAL else RecyclerView.HORIZONTAL, false
        )
        groupsRv.adapter = groupAdapter

        channelAdapter = ChannelAdapter(
            onClick = { play(channelAdapter.items, it) },
            onLongClick = { toggleFavourite(it) },
            onLeft = if (groupsVertical) ({ focusSelectedGroup() }) else null,
            onFocus = { pos -> guide?.bind(channelAdapter.items.getOrNull(pos)) },
        )
        guide = findViewById<View>(R.id.guidePanel)?.let { GuidePanel(it) }
        channelsRv.layoutManager = LinearLayoutManager(this)
        channelsRv.adapter = channelAdapter

        findViewById<ImageButton>(R.id.btnReload).setOnClickListener { refresh(userAsked = true) }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { openSettings() }
        empty.setOnClickListener { refresh(userAsked = true) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isTv && groupsVertical && channelsRv.hasFocus()) focusSelectedGroup() else finish()
            }
        })

        // Guia: atualiza as linhas "▶ programa" e o estado no topo
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { EpgRepo.version.collect { refreshEpgRows() } }
                launch { EpgRepo.state.collect { updateStatus() } }
                launch { while (true) { delay(60_000); refreshEpgRows() } }
            }
        }

        if (PlaylistRepo.channels.isNotEmpty()) {
            applyChannels()
            if (isStale()) refresh(userAsked = false)
        } else {
            lifecycleScope.launch {
                PlaylistRepo.loadCache(this@MainActivity)?.let {
                    PlaylistRepo.channels = it
                    applyChannels()
                }
                refresh(userAsked = false)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // As listas com URLs temporários (ex.: TVI) envelhecem — atualiza em segundo plano
        if (PlaylistRepo.channels.isNotEmpty() && isStale()) refresh(userAsked = false)
        resumeUpdate()
    }

    // ---------- Atualização automática (GitHub Releases) ----------

    private var updateDialog: AlertDialog? = null
    /** Carregou "Instalar" mas faltava a autorização: ao voltar das definições, instala logo. */
    private var installAfterPermission = false

    private fun resumeUpdate() {
        val ready = Updater.pending
        if (ready != null && installAfterPermission) {
            installAfterPermission = false
            if (Updater.canInstall(this)) startInstall(ready)
            return
        }
        if (ready != null) { offerUpdate(ready, userAsked = false); return }
        if (Updater.shouldCheck(this)) {
            lifecycleScope.launch {
                val r = Updater.check(this@MainActivity)
                if (r is Updater.Result.Available && !isFinishing) offerUpdate(r.ready, userAsked = false)
            }
        }
    }

    private fun checkUpdatesNow() {
        toast(getString(R.string.update_checking))
        lifecycleScope.launch {
            when (val r = Updater.check(this@MainActivity)) {
                is Updater.Result.Available -> offerUpdate(r.ready, userAsked = true)
                is Updater.Result.UpToDate -> toast(getString(R.string.update_none, BuildConfig.VERSION_NAME))
                is Updater.Result.Failed -> toast(getString(R.string.update_failed, r.message))
            }
        }
    }

    private fun offerUpdate(ready: Updater.Ready, userAsked: Boolean) {
        if (isFinishing || updateDialog?.isShowing == true) return
        val snoozed = prefs.updateSnoozedCode == ready.info.versionCode &&
            System.currentTimeMillis() - prefs.updateSnoozedAt < 24 * 60 * 60 * 1000L
        if (snoozed && !userAsked) return
        updateDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_title, ready.info.versionName))
            .setMessage(getString(R.string.update_msg, ready.info.versionName, ready.info.notes).trim())
            .setPositiveButton(R.string.update_install) { _, _ -> startInstall(ready) }
            .setNegativeButton(R.string.update_later) { _, _ ->
                prefs.updateSnoozedCode = ready.info.versionCode
                prefs.updateSnoozedAt = System.currentTimeMillis()
            }
            .show()
        updateDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
    }

    private fun startInstall(ready: Updater.Ready) {
        if (!Updater.canInstall(this)) {
            val fireTv = packageManager.hasSystemFeature("amazon.hardware.fire_tv")
            AlertDialog.Builder(this)
                .setTitle(R.string.update_perm_title)
                .setMessage(if (fireTv) R.string.update_perm_firetv else R.string.update_perm_msg)
                .setPositiveButton(R.string.update_perm_open) { _, _ ->
                    installAfterPermission = true
                    if (!Updater.openInstallPermission(this)) {
                        // Fire TV sem esse ecrã: abre as definições gerais
                        try { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        if (!Updater.install(this, ready)) toast(getString(R.string.update_install_failed))
    }

    private fun isStale() = System.currentTimeMillis() - prefs.lastUpdate > 2 * 60 * 60 * 1000L

    // ---------- Carregar lista ----------

    private fun refresh(userAsked: Boolean) {
        if (refreshing) return
        refreshing = true
        val hadChannels = PlaylistRepo.channels.isNotEmpty()
        if (!hadChannels) {
            loading.visibility = View.VISIBLE
            empty.visibility = View.GONE
        }
        lifecycleScope.launch {
            try {
                val fresh = PlaylistRepo.download(this@MainActivity, prefs.playlistUrl)
                if (userAsked) startEpg(force = true)
                if (fresh != PlaylistRepo.channels) {
                    PlaylistRepo.channels = fresh
                    applyChannels()
                } else updateStatus() // igual: não mexe na lista nem no foco
                if (userAsked) toast(getString(R.string.updated))
            } catch (e: Exception) {
                if (hadChannels || PlaylistRepo.channels.isNotEmpty()) {
                    if (userAsked) toast(getString(R.string.offline_using_cache))
                } else {
                    empty.text = getString(R.string.load_error, e.message ?: e.javaClass.simpleName)
                    empty.visibility = View.VISIBLE
                    if (isTv) empty.requestFocus()
                }
            } finally {
                refreshing = false
                loading.visibility = View.GONE
            }
        }
    }

    private fun epgUrls(): List<String> =
        prefs.epgUrl.split(',').map { it.trim() }.filter { it.startsWith("http", true) }
            .ifEmpty { PlaylistRepo.epgUrls }

    private fun startEpg(force: Boolean = false) {
        if (PlaylistRepo.channels.isEmpty()) return
        EpgRepo.update(this, PlaylistRepo.channels, epgUrls(), force)
    }

    private fun refreshEpgRows() {
        if (channelAdapter.itemCount > 0) {
            channelAdapter.notifyItemRangeChanged(0, channelAdapter.itemCount, ChannelAdapter.PAYLOAD_EPG)
        }
        guide?.refresh()
    }

    private fun applyChannels() {
        val all = PlaylistRepo.channels
        startEpg()
        byGroup = all.groupBy { it.group } // mantém a ordem da lista
        val favs = prefs.favourites
        channelAdapter.favourites = favs
        groups = buildGroups(favs)

        val wanted = prefs.lastGroup
        val sel = groups.indexOf(wanted).takeIf { it >= 0 } ?: groups.indexOfFirst { it != favLabel }.coerceAtLeast(0)
        groupAdapter.submit(groups, sel)
        showGroup(sel)
        empty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        updateStatus()

        if (!autoplayDone) {
            autoplayDone = true
            val last = prefs.lastKey
            if (prefs.autoplayLast && last != null) {
                val ch = all.firstOrNull { it.key == last }
                if (ch != null) {
                    val list = channelsFor(sel).takeIf { l -> l.any { it.key == last } } ?: byGroup[ch.group].orEmpty()
                    play(list, list.indexOfFirst { it.key == last })
                    return
                }
            }
        }
        focusLastChannel()
    }

    private fun buildGroups(favs: Set<String>): List<String> {
        val hasFav = PlaylistRepo.channels.any { it.key in favs }
        val hidden = prefs.hiddenGroups
        return (if (hasFav) listOf(favLabel) else emptyList()) + byGroup.keys.filter { it !in hidden }
    }

    private fun channelsFor(pos: Int): List<Channel> {
        val g = groups.getOrNull(pos) ?: return emptyList()
        return if (g == favLabel) {
            val favs = prefs.favourites
            PlaylistRepo.channels.filter { it.key in favs }
        } else byGroup[g].orEmpty()
    }

    private fun showGroup(pos: Int) {
        if (pos !in groups.indices) return
        groupAdapter.select(pos, groupsRv)
        prefs.lastGroup = groups[pos]
        val items = channelsFor(pos)
        channelAdapter.submit(items, withGroup = groups[pos] == favLabel)
        channelsRv.scrollToPosition(0)
        // Sem foco (toque/arranque): mostra o último canal visto ou o primeiro do grupo
        if (!channelsRv.hasFocus()) guide?.bind(items.firstOrNull { it.key == prefs.lastKey } ?: items.firstOrNull())
    }

    private fun updateStatus() {
        val n = PlaylistRepo.channels.size
        if (n == 0) { status.text = ""; return }
        val t = prefs.lastUpdate
        val base = if (t > 0) {
            getString(R.string.status_fmt, n, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t)))
        } else getString(R.string.status_count, n)
        val guide = when (EpgRepo.state.value) {
            EpgRepo.State.LOADING -> getString(R.string.guide_loading)
            EpgRepo.State.READY -> getString(R.string.guide_ready, EpgRepo.channelsWithGuide)
            EpgRepo.State.ERROR -> getString(R.string.guide_error)
            EpgRepo.State.EMPTY -> getString(R.string.guide_none)
            EpgRepo.State.IDLE -> null
        }
        status.text = if (guide != null) "$base · $guide" else base
    }

    // ---------- Foco (comando da TV) ----------

    private fun focusLastChannel() {
        if (!isTv) return
        val idx = channelAdapter.items.indexOfFirst { it.key == prefs.lastKey }.coerceAtLeast(0)
        channelsRv.scrollToPosition(idx)
        channelsRv.post {
            channelsRv.findViewHolderForAdapterPosition(idx)?.itemView?.requestFocus()
                ?: channelsRv.requestFocus()
        }
    }

    private fun focusSelectedGroup() {
        val pos = groupAdapter.selected
        groupsRv.scrollToPosition(pos)
        groupsRv.post {
            groupsRv.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() ?: groupsRv.requestFocus()
        }
    }

    // ---------- Ações ----------

    private fun play(list: List<Channel>, index: Int) {
        if (list.isEmpty() || index !in list.indices) return
        Session.list = list
        Session.index = index
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun toggleFavourite(pos: Int) {
        val ch = channelAdapter.items.getOrNull(pos) ?: return
        val favs = prefs.favourites.toMutableSet()
        val added = favs.add(ch.key) || run { favs.remove(ch.key); false }
        prefs.favourites = favs
        channelAdapter.favourites = favs
        toast(getString(if (added) R.string.fav_added else R.string.fav_removed, ch.name))

        val current = groups.getOrNull(groupAdapter.selected)
        val newGroups = buildGroups(favs)
        if (newGroups != groups) {
            groups = newGroups
            val sel = groups.indexOf(current).coerceAtLeast(0)
            groupAdapter.submit(groups, sel)
            if (current != groups[sel]) showGroup(sel) else channelAdapter.notifyItemChanged(pos)
        } else if (current == favLabel) {
            channelAdapter.submit(channelsFor(groupAdapter.selected), withGroup = true)
        } else {
            channelAdapter.notifyItemChanged(pos)
        }
    }

    /** Manter OK premido num grupo: esconde-o (repõe-se em Definições → Grupos visíveis). */
    private fun hideGroup(pos: Int) {
        val g = groups.getOrNull(pos) ?: return
        if (g == favLabel) return
        if (groups.count { it != favLabel } <= 1) { toast(getString(R.string.groups_keep_one)); return }
        prefs.hiddenGroups = prefs.hiddenGroups + g
        toast(getString(R.string.group_hidden, g))
        applyChannels()
        focusSelectedGroup()
    }

    private fun chooseGroups() {
        val all = Catalog.allGroups()
        if (all.isEmpty()) return
        val hidden = prefs.hiddenGroups
        val checked = BooleanArray(all.size) { all[it] !in hidden }
        AlertDialog.Builder(this)
            .setTitle(R.string.visible_groups)
            .setMultiChoiceItems(all.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton(R.string.save) { _, _ ->
                if (checked.none { it }) { toast(getString(R.string.groups_keep_one)); return@setPositiveButton }
                // Só guarda os desmarcados: grupos novos da lista aparecem por omissão
                prefs.hiddenGroups = all.filterIndexed { i, _ -> !checked[i] }.toSet()
                applyChannels()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openSettings() {
        val v = layoutInflater.inflate(R.layout.dialog_settings, null)
        val url = v.findViewById<EditText>(R.id.url)
        val auto = v.findViewById<CheckBox>(R.id.autoplay)
        val autoPreview = v.findViewById<CheckBox>(R.id.autoPreview)
        v.findViewById<View>(R.id.btnGroups).setOnClickListener { chooseGroups() }
        autoPreview.isChecked = prefs.autoPreview
        val epg = v.findViewById<EditText>(R.id.epgUrl)
        url.setText(prefs.playlistUrl)
        epg.setText(prefs.epgUrl)
        auto.isChecked = prefs.autoplayLast
        v.findViewById<TextView>(R.id.version).text = getString(R.string.version_fmt, BuildConfig.VERSION_NAME)
        val autoUpdate = v.findViewById<CheckBox>(R.id.autoUpdate)
        autoUpdate.isChecked = prefs.autoUpdate
        autoUpdate.setOnCheckedChangeListener { _, on -> prefs.autoUpdate = on }
        v.findViewById<View>(R.id.btnCheckUpdates).setOnClickListener { checkUpdatesNow() }

        AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setView(v)
            .setPositiveButton(R.string.save) { _, _ ->
                prefs.autoplayLast = auto.isChecked
                prefs.autoPreview = autoPreview.isChecked
                changeEpg(epg.text.toString())
                changeUrl(url.text.toString())
            }
            .setNeutralButton(R.string.reset_default) { _, _ ->
                prefs.autoplayLast = auto.isChecked
                prefs.autoPreview = autoPreview.isChecked
                changeEpg("")
                changeUrl(Prefs.DEFAULT_URL)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun changeEpg(newUrl: String) {
        if (newUrl.trim() == prefs.epgUrl) return
        prefs.epgUrl = newUrl
        EpgRepo.clear(this)
        startEpg(force = true)
    }

    private fun changeUrl(newUrl: String) {
        val u = newUrl.trim().ifEmpty { Prefs.DEFAULT_URL }
        if (u == prefs.playlistUrl && PlaylistRepo.channels.isNotEmpty()) return
        prefs.playlistUrl = u
        prefs.lastGroup = null
        PlaylistRepo.clearCache(this)
        PlaylistRepo.channels = emptyList()
        EpgRepo.clear(this)
        groupAdapter.submit(emptyList(), 0)
        channelAdapter.submit(emptyList(), false)
        status.text = ""
        refresh(userAsked = true)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private var startedOnce = false
    }
}
