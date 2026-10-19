@file:OptIn(UnstableApi::class)

package pt.nuno.sintonia

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var info: View
    private lateinit var infoName: TextView
    private lateinit var infoMeta: TextView
    private lateinit var radioLogo: ImageView
    private lateinit var message: TextView
    private lateinit var zap: View
    private lateinit var epgBox: View
    private lateinit var epgNow: TextView
    private lateinit var epgRange: TextView
    private lateinit var epgProgress: ProgressBar
    private lateinit var epgDesc: TextView
    private lateinit var epgNext: TextView
    private val timeFmt by lazy { DateFormat.getTimeInstance(DateFormat.SHORT) }
    // Lista de canais sobre o vídeo
    private lateinit var root: FrameLayout
    private lateinit var overlay: View
    private lateinit var videoSlot: View
    private lateinit var ovGroupsRv: RecyclerView
    private lateinit var ovChannelsRv: RecyclerView
    private lateinit var ovGroupAdapter: GroupAdapter
    private lateinit var ovChannelAdapter: ChannelAdapter
    private lateinit var ovGuide: GuidePanel
    private var ovGroups: List<String> = emptyList()
    private val overlayOpen get() = overlay.visibility == View.VISIBLE

    /** Tecla cujo "premir" consumimos — o "largar" correspondente também não chega ao leitor. */
    private var swallowedKey = -1

    private val handler = Handler(Looper.getMainLooper())
    private val hideInfo = Runnable { info.visibility = View.GONE }
    private var retry: Runnable? = null
    private var retries = 0
    private var isTv = false
    private lateinit var prefs: Prefs

    private val current: Channel? get() = Session.list.getOrNull(Session.index)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Session.list.isEmpty()) { finish(); return }
        setContentView(R.layout.activity_player)
        prefs = Prefs(this)
        isTv = Device.isTv(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        playerView = findViewById(R.id.player)
        info = findViewById(R.id.info)
        infoName = findViewById(R.id.infoName)
        infoMeta = findViewById(R.id.infoMeta)
        radioLogo = findViewById(R.id.radioLogo)
        message = findViewById(R.id.message)
        zap = findViewById(R.id.zap)
        epgBox = findViewById(R.id.epgBox)
        epgNow = findViewById(R.id.epgNow)
        epgRange = findViewById(R.id.epgRange)
        epgProgress = findViewById(R.id.epgProgress)
        epgDesc = findViewById(R.id.epgDesc)
        epgNext = findViewById(R.id.epgNext)
        setupOverlay()

        findViewById<ImageButton>(R.id.zapUp).setOnClickListener { zap(+1) }
        findViewById<ImageButton>(R.id.zapDown).setOnClickListener { zap(-1) }
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { vis ->
            if (!isTv) zap.visibility = vis
            // O guia só aparece com os controlos (OK); ao fechá-los desaparece logo
            if (vis == View.VISIBLE) current?.let { showInfo(it, autoHide = false, withEpg = true) }
            else { handler.removeCallbacks(hideInfo); info.visibility = View.GONE }
        })

        // Guia chegou/atualizou, e a cada 30 s (barra de progresso / programa seguinte)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { EpgRepo.version.collect { refreshGuides() } }
                launch { while (true) { delay(30_000); refreshGuides() } }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    // Lista aberta: nos grupos (ou no telemóvel) sai; nos canais/janela volta a ecrã inteiro
                    overlayOpen ->
                        if (ovGroupsRv.hasFocus() || root.isInTouchMode) finish() else closeChannels()
                    playerView.isControllerFullyVisible -> playerView.hideController()
                    // Ecrã inteiro: abre a lista com o canal na janela
                    else -> if (!openChannels()) finish()
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        if (Session.list.isEmpty()) return
        player = ExoPlayer.Builder(this).build().also {
            it.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            it.setHandleAudioBecomingNoisy(true)
            it.addListener(listener)
            it.playWhenReady = true
            playerView.player = it
        }
        playCurrent()
    }

    /** Outro canal escolhido com o leitor já aberto (ex.: em Picture-in-Picture). */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (player != null) playCurrent()
    }

    override fun onStop() {
        super.onStop()
        cancelRetry()
        playerView.player = null
        player?.release()
        player = null
    }

    // ---------- Reprodução ----------

    private fun playCurrent() {
        val ch = current ?: return
        retries = 0
        cancelRetry()
        message.visibility = View.GONE
        message.text = ""
        prefs.lastKey = ch.key
        playerView.hideController()
        showInfo(ch)
        if (ch.isRadio) {
            radioLogo.visibility = if (overlayOpen) View.GONE else View.VISIBLE
            radioLogo.load(ch.logo) { error(R.drawable.ic_radio); fallback(R.drawable.ic_radio) }
        } else {
            radioLogo.visibility = View.GONE
        }
        start(ch)
    }

    private fun start(ch: Channel) {
        val p = player ?: return
        try {
            p.setMediaSource(StreamFactory.build(this, ch))
            p.prepare()
            p.play()
        } catch (e: Exception) {
            showMessage(getString(R.string.cannot_open, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun zap(delta: Int) {
        val n = Session.list.size
        if (n == 0) return
        Session.index = ((Session.index + delta) % n + n) % n
        playCurrent()
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                retries = 0
                message.visibility = View.GONE
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                p.seekToDefaultPosition(); p.prepare(); return
            }
            val ch = current ?: return
            if (retries < MAX_RETRIES) {
                retries++
                showMessage(getString(R.string.reconnecting, retries, MAX_RETRIES))
                val r = Runnable { start(ch) }
                retry = r
                handler.postDelayed(r, 2000L * retries)
            } else {
                showMessage(getString(R.string.cannot_open, error.errorCodeName))
            }
        }
    }

    private fun cancelRetry() {
        retry?.let { handler.removeCallbacks(it) }
        retry = null
    }

    // ---------- Interface ----------

    private fun refreshGuides() {
        if (info.visibility == View.VISIBLE && epgBox.tag == true) current?.let(::bindEpg)
        if (overlayOpen) {
            ovGuide.refresh()
            if (ovChannelAdapter.itemCount > 0) {
                ovChannelAdapter.notifyItemRangeChanged(0, ovChannelAdapter.itemCount, ChannelAdapter.PAYLOAD_EPG)
            }
        }
    }

    // ---------- Lista de canais sobre o vídeo ----------

    private fun setupOverlay() {
        root = findViewById(R.id.root)
        overlay = findViewById(R.id.channelsOverlay)
        videoSlot = findViewById(R.id.videoSlot)
        ovGroupsRv = findViewById(R.id.ovGroups)
        ovChannelsRv = findViewById(R.id.ovChannels)
        ovGuide = GuidePanel(overlay.findViewById(R.id.guidePanel))

        ovGroupAdapter = GroupAdapter(vertical = true) { showOverlayGroup(it, null) }
        ovGroupsRv.layoutManager = LinearLayoutManager(this)
        ovGroupsRv.adapter = ovGroupAdapter

        ovChannelAdapter = ChannelAdapter(
            onClick = { choose(it) },
            onLongClick = { pos ->
                ovChannelAdapter.items.getOrNull(pos)?.let { ch ->
                    val added = Catalog.toggleFavourite(this, ch)
                    ovChannelAdapter.favourites = prefs.favourites
                    ovChannelAdapter.notifyItemChanged(pos)
                    Toast.makeText(this, getString(if (added) R.string.fav_added else R.string.fav_removed, ch.name),
                        Toast.LENGTH_SHORT).show()
                }
            },
            onLeft = { focusOverlayGroup() },
            onFocus = { pos ->
                ovGuide.bind(ovChannelAdapter.items.getOrNull(pos))
                if (prefs.autoPreview) schedulePreview(pos)
            },
            onRight = { videoSlot.requestFocus() },
        )
        ovChannelsRv.layoutManager = LinearLayoutManager(this)
        ovChannelsRv.adapter = ovChannelAdapter

        // Janela do vídeo: OK/toque → ecrã inteiro; ◀ volta ao canal focado na lista
        videoSlot.setOnClickListener { closeChannels() }
        videoSlot.setOnKeyListener { _, keyCode, e ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && e.action == KeyEvent.ACTION_DOWN) {
                focusOverlayChannel(); true
            } else false
        }

        // Botão "Canais" no menu do OK (layout do controlador personalizado)
        playerView.findViewById<View>(R.id.btnChannels)?.setOnClickListener { openChannels() }
    }

    /** Abre a lista sobre o vídeo; devolve false se não for possível. */
    private fun openChannels(): Boolean {
        if (overlayOpen) return true
        if (isInPip() || PlaylistRepo.channels.isEmpty()) return false
        val cur = current
        playerView.hideController()
        playerView.useController = false
        handler.removeCallbacks(hideInfo)
        info.visibility = View.GONE
        zap.visibility = View.GONE
        radioLogo.visibility = View.GONE
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))

        ovGroups = Catalog.groups(this)
        ovChannelAdapter.favourites = prefs.favourites
        val wanted = prefs.lastGroup?.takeIf { g -> Catalog.channelsFor(this, g).any { it.key == cur?.key } }
        val gi = ovGroups.indexOf(wanted ?: cur?.group).takeIf { it >= 0 } ?: 0
        ovGroupAdapter.submit(ovGroups, gi)
        overlay.visibility = View.VISIBLE
        showOverlayGroup(gi, cur?.key)
        ovGuide.bind(cur)
        placeVideoInSlot()
        return true
    }

    /** Pré-visualização automática (opção): muda a janela para o canal focado após uma pausa curta. */
    private var previewTask: Runnable? = null

    private fun schedulePreview(pos: Int) {
        previewTask?.let { handler.removeCallbacks(it) }
        val items = ovChannelAdapter.items
        val ch = items.getOrNull(pos) ?: return
        if (ch.key == current?.key) return
        val group = ovGroups.getOrNull(ovGroupAdapter.selected)
        val r = Runnable {
            if (!overlayOpen) return@Runnable
            Session.list = items
            Session.index = pos
            prefs.lastGroup = group
            playCurrent()
        }
        previewTask = r
        handler.postDelayed(r, PREVIEW_DELAY)
    }

    private fun cancelPreview() {
        previewTask?.let { handler.removeCallbacks(it) }
        previewTask = null
    }

    private fun focusOverlayChannel() {
        val idx = ovChannelAdapter.items.indexOfFirst { it.key == current?.key }.coerceAtLeast(0)
        ovChannelsRv.scrollToPosition(idx)
        ovChannelsRv.post {
            ovChannelsRv.findViewHolderForAdapterPosition(idx)?.itemView?.requestFocus() ?: ovChannelsRv.requestFocus()
        }
    }

    /** Fecha a lista e volta a ecrã inteiro com o canal que está na janela. */
    private fun closeChannels() {
        if (!overlayOpen) return
        cancelPreview()
        overlay.visibility = View.GONE
        playerView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
        root.setBackgroundColor(ContextCompat.getColor(this, android.R.color.black))
        playerView.useController = true
        if (current?.isRadio == true) radioLogo.visibility = View.VISIBLE
        if (player?.playerError != null && message.text.isNotEmpty()) message.visibility = View.VISIBLE
        playerView.requestFocus()
        current?.let { showInfo(it) }
    }

    /** Encolhe o vídeo (que continua a tocar) para o espaço 16:9 no canto do painel. */
    private fun placeVideoInSlot() {
        if (!overlayOpen) return
        val w = videoSlot.width
        if (w == 0) { overlay.post { placeVideoInSlot() }; return }
        val h = w * 9 / 16
        if (videoSlot.layoutParams.height != h) {
            videoSlot.layoutParams.height = h
            videoSlot.requestLayout()
            overlay.post { placeVideoInSlot() }
            return
        }
        val slot = IntArray(2).also { videoSlot.getLocationInWindow(it) }
        val base = IntArray(2).also { root.getLocationInWindow(it) }
        playerView.layoutParams = FrameLayout.LayoutParams(w, h).apply {
            leftMargin = slot[0] - base[0]
            topMargin = slot[1] - base[1]
        }
    }

    private fun showOverlayGroup(pos: Int, focusKey: String?) {
        if (pos !in ovGroups.indices) return
        ovGroupAdapter.select(pos, ovGroupsRv)
        val items = Catalog.channelsFor(this, ovGroups[pos])
        ovChannelAdapter.submit(items, withGroup = ovGroups[pos] == Catalog.favLabel(this))
        if (focusKey != null) {
            val idx = items.indexOfFirst { it.key == focusKey }.coerceAtLeast(0)
            ovChannelsRv.scrollToPosition(idx)
            ovChannelsRv.post {
                ovChannelsRv.findViewHolderForAdapterPosition(idx)?.itemView?.requestFocus()
                    ?: ovChannelsRv.requestFocus()
            }
        } else {
            ovChannelsRv.scrollToPosition(0)
            if (!ovChannelsRv.hasFocus()) ovGuide.bind(items.firstOrNull())
        }
    }

    private fun focusOverlayGroup() {
        val pos = ovGroupAdapter.selected
        ovGroupsRv.scrollToPosition(pos)
        ovGroupsRv.post {
            ovGroupsRv.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() ?: ovGroupsRv.requestFocus()
        }
    }

    /**
     * OK num canal da lista: se ainda não está na janela, pré-visualiza-o lá;
     * se já está (2.º OK), passa a ecrã inteiro.
     */
    private fun choose(pos: Int) {
        val items = ovChannelAdapter.items
        if (pos !in items.indices) return
        cancelPreview()
        if (items[pos].key == current?.key) {
            closeChannels()
            return
        }
        prefs.lastGroup = ovGroups.getOrNull(ovGroupAdapter.selected)
        Session.list = items
        Session.index = pos
        playCurrent()
    }

    /**
     * Ao mudar de canal: só nome e número, pequeno, 3 s.
     * Com OK (controlos abertos): também o guia, enquanto os controlos estiverem visíveis.
     */
    private fun showInfo(ch: Channel, autoHide: Boolean = true, withEpg: Boolean = false) {
        if (isInPip() || overlayOpen) return
        infoName.text = ch.name
        infoMeta.text = getString(R.string.info_meta, Session.index + 1, Session.list.size, ch.group)
        epgBox.tag = withEpg
        if (withEpg) bindEpg(ch) else epgBox.visibility = View.GONE
        info.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        if (autoHide) handler.postDelayed(hideInfo, 3000)
    }

    private fun toggleInfo() {
        if (info.visibility == View.VISIBLE) { handler.removeCallbacks(hideInfo); info.visibility = View.GONE }
        else current?.let { showInfo(it) }
    }

    private fun bindEpg(ch: Channel) {
        val t = System.currentTimeMillis()
        val (now, next) = EpgRepo.nowAndNext(ch, t)
        if (now == null && next == null) { epgBox.visibility = View.GONE; return }
        epgBox.visibility = View.VISIBLE
        if (now != null) {
            epgNow.text = getString(R.string.epg_now, now.title)
            epgRange.text = getString(R.string.epg_range, timeFmt.format(Date(now.start)), timeFmt.format(Date(now.stop)))
            epgProgress.progress = (((t - now.start).toDouble() / (now.stop - now.start)) * 1000).toInt().coerceIn(0, 1000)
            epgNow.visibility = View.VISIBLE
            (epgRange.parent as View).visibility = View.VISIBLE
            epgDesc.text = now.desc.orEmpty()
            epgDesc.visibility = if (now.desc.isNullOrBlank()) View.GONE else View.VISIBLE
        } else {
            epgNow.visibility = View.GONE
            (epgRange.parent as View).visibility = View.GONE
            epgDesc.visibility = View.GONE
        }
        if (next != null) {
            epgNext.text = getString(R.string.epg_next, timeFmt.format(Date(next.start)), next.title)
            epgNext.visibility = View.VISIBLE
        } else epgNext.visibility = View.GONE
    }

    private fun showMessage(text: String) {
        message.text = text
        // Com a lista aberta não tapa o ecrã; aparece ao voltar a ecrã inteiro
        message.visibility = if (overlayOpen) View.GONE else View.VISIBLE
    }

    // ---------- Comando da TV ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN

        // "Largar" de uma tecla que já tratámos: não deixar chegar ao leitor (era isto que abria os controlos)
        if (event.action == KeyEvent.ACTION_UP && code == swallowedKey) {
            swallowedKey = -1
            return true
        }

        fun consume(action: () -> Unit): Boolean {
            if (down) {
                swallowedKey = code
                if (event.repeatCount == 0 || code in ZAP_KEYS) action()
            }
            return true
        }

        // Lista aberta: o comando navega na lista (Voltar fecha)
        if (overlayOpen) return super.dispatchKeyEvent(event)

        when (code) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> return consume { zap(+1) }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> return consume { zap(-1) }
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> return consume { toggleInfo() }
        }

        // Controlos fechados: só OK/Selecionar os abre; ▲▼ mudam de canal; ◀▶ mostram o guia
        if (!playerView.isControllerFullyVisible && !isInPip()) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> return consume { zap(+1) }
                KeyEvent.KEYCODE_DPAD_DOWN -> return consume { zap(-1) }
                KeyEvent.KEYCODE_DPAD_LEFT -> return consume { openChannels() }
                KeyEvent.KEYCODE_DPAD_RIGHT -> return consume { toggleInfo() }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_A ->
                    return consume { playerView.showController() }
                KeyEvent.KEYCODE_DPAD_UP_LEFT, KeyEvent.KEYCODE_DPAD_UP_RIGHT,
                KeyEvent.KEYCODE_DPAD_DOWN_LEFT, KeyEvent.KEYCODE_DPAD_DOWN_RIGHT -> return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------- Telemóvel: deslizar para mudar de canal ----------

    private val gestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val start = e1 ?: return false
                val dy = e2.y - start.y
                val dx = e2.x - start.x
                val minDist = 80 * resources.displayMetrics.density
                if (overlayOpen) return false
                if (dx > minDist && abs(dx) > abs(dy) * 1.5f && start.x < resources.displayMetrics.widthPixels * 0.2f) {
                    openChannels() // deslizar a partir da margem esquerda
                    return true
                }
                if (abs(dy) > abs(dx) * 1.5f && abs(dy) > minDist && abs(vy) > 600) {
                    zap(if (dy < 0) +1 else -1) // deslizar para cima = canal seguinte
                    return true
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!isInPip()) gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // ---------- Picture-in-Picture (telemóvel) ----------

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val ch = current ?: return
        if (isTv || ch.isRadio || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        if (player?.isPlaying != true) return
        closeChannels()
        runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            )
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        playerView.useController = !isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            info.visibility = View.GONE
            zap.visibility = View.GONE
            message.visibility = View.GONE
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (overlayOpen) overlay.post { placeVideoInSlot() }
    }

    private fun isInPip() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode

    companion object {
        private const val MAX_RETRIES = 3
        private const val PREVIEW_DELAY = 700L
        /** Teclas que repetem quando mantidas premidas (zapping rápido). */
        private val ZAP_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
        )
    }
}
