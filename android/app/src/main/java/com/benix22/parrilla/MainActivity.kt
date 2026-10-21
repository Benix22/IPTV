package com.benix22.parrilla

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        const val ALL = "Todos"
        const val FAV = "★ Favoritos"
    }

    private lateinit var prefs: Prefs
    private lateinit var repo: PlaylistRepo
    private lateinit var playerView: PlayerView
    private lateinit var panel: View
    private lateinit var groupList: RecyclerView
    private lateinit var channelList: RecyclerView
    private lateinit var osd: TextView
    private lateinit var status: TextView
    private lateinit var info: TextView

    private var player: ExoPlayer? = null
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var all: List<Channel> = emptyList()
    private var shown: List<Channel> = emptyList()
    private var groups: List<String> = listOf(ALL, FAV)
    private var groupIndex = 0
    private var favs: MutableSet<String> = LinkedHashSet()
    private var current: Channel? = null
    private var retries = 0
    private var panelVisible = false

    private val hideOsd = Runnable { osd.visibility = View.GONE }

    private val groupAdapter = RowAdapter(
        onClick = { pos -> onGroupClick(pos) },
        onLong = { }
    )
    private val channelAdapter = RowAdapter(
        onClick = { pos -> onChannelClick(pos) },
        onLong = { pos -> toggleFav(pos) }
    )

    private val setupLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) refresh(false)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        repo = PlaylistRepo(this, prefs)
        favs = prefs.loadFavs()

        playerView = findViewById(R.id.player)
        playerView.useController = false
        panel = findViewById(R.id.panel)
        groupList = findViewById(R.id.groupList)
        channelList = findViewById(R.id.channelList)
        osd = findViewById(R.id.osd)
        status = findViewById(R.id.status)
        info = findViewById(R.id.info)

        // Control táctil (móvil o tableta): tocar la pantalla abre o cierra el menú.
        findViewById<View>(R.id.root).setOnClickListener {
            when {
                panelVisible -> closePanel()
                all.isEmpty() -> openSetup()
                else -> openPanel()
            }
        }

        groupAdapter.label = { i -> groups[i] }
        channelAdapter.label = { i ->
            val c = shown[i]
            if (favs.contains(c.url)) "★ " + c.name else c.name
        }

        groupList.layoutManager = LinearLayoutManager(this)
        groupList.adapter = groupAdapter
        channelList.layoutManager = LinearLayoutManager(this)
        channelList.adapter = channelAdapter

        findViewById<Button>(R.id.btnSettings).setOnClickListener { openSetup() }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener {
            closePanel()
            refresh(false)
        }

        refreshLists()

        if (!repo.hasSource()) {
            openSetup()
        } else {
            val cached = repo.cachedText()
            if (cached != null) {
                io.execute {
                    val list = M3uParser.parse(cached)
                    ui.post { setChannels(list) }
                }
                refresh(true)
            } else {
                refresh(false)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        startPlayer()
        current?.let { play(it) }
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        io.shutdownNow()
    }

    // ---------------------------------------------------------------- reproductor

    private fun startPlayer() {
        if (player != null) return
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(prefs.userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(sources).build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> if (current != null) showStatus("Cargando…")
                    Player.STATE_READY -> {
                        retries = 0
                        hideStatus()
                    }
                    else -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                handlePlayerError(error)
            }
        })
        playerView.player = p
        player = p
    }

    private fun releasePlayer() {
        playerView.player = null
        player?.release()
        player = null
    }

    private fun play(ch: Channel) {
        current = ch
        retries = 0
        prefs.lastUrl = ch.url
        showOsd(ch.name)
        val p = player ?: return
        p.setMediaItem(MediaItem.fromUri(ch.url))
        p.prepare()
        p.playWhenReady = true
        showStatus("Cargando…")
        channelAdapter.selected = shown.indexOfFirst { it.url == ch.url }
        channelAdapter.notifyDataSetChanged()
    }

    private fun handlePlayerError(error: PlaybackException) {
        val ch = current ?: return
        if (retries < 3) {
            retries++
            showStatus("Reintentando ($retries/3)…")
            ui.postDelayed({
                if (current === ch) player?.prepare()
            }, 2000)
            return
        }
        val cause = error.cause
        val http = if (cause is HttpDataSource.InvalidResponseCodeException) {
            " (HTTP ${cause.responseCode})"
        } else ""
        showStatus(
            "No se pudo reproducir «${ch.name}»\n${error.errorCodeName}$http\n\n" +
                "Toca la pantalla o pulsa OK para elegir otro canal."
        )
    }

    private fun step(delta: Int) {
        if (shown.isEmpty()) return
        val i = shown.indexOfFirst { it.url == current?.url }
        val n = if (i < 0) {
            if (delta > 0) 0 else shown.size - 1
        } else {
            (i + delta + shown.size) % shown.size
        }
        play(shown[n])
    }

    // ---------------------------------------------------------------- lista

    private fun setChannels(list: List<Channel>) {
        all = list
        val names = list.map { it.group }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        groups = listOf(ALL, FAV) + names
        val idx = groups.indexOf(prefs.lastGroup)
        groupIndex = if (idx < 0) 0 else idx
        applyFilter()
        info.text = "${list.size} canales"
        hideStatus()

        if (current == null) {
            val last = prefs.lastUrl
            val ch = list.firstOrNull { it.url == last }
            if (ch != null) {
                play(ch)
            } else if (list.isNotEmpty()) {
                openPanel()
            }
        }
    }

    private fun applyFilter() {
        val g = groups.getOrElse(groupIndex) { ALL }
        shown = when (g) {
            ALL -> all
            FAV -> all.filter { favs.contains(it.url) }
            else -> all.filter { it.group == g }
        }
        refreshLists()
    }

    private fun refreshLists() {
        groupAdapter.size = groups.size
        groupAdapter.selected = groupIndex
        groupAdapter.notifyDataSetChanged()
        channelAdapter.size = shown.size
        channelAdapter.selected = shown.indexOfFirst { it.url == current?.url }
        channelAdapter.notifyDataSetChanged()
    }

    private fun refresh(silent: Boolean) {
        if (!silent) showStatus("Descargando lista…")
        io.execute {
            try {
                val loaded = repo.download()
                val list = M3uParser.parse(loaded.text)
                ui.post {
                    loaded.favs?.let {
                        favs = LinkedHashSet(it)
                        prefs.saveFavs(favs)
                    }
                    setChannels(list)
                }
            } catch (e: Exception) {
                ui.post {
                    if (all.isEmpty()) {
                        showStatus(
                            "No se pudo cargar la lista\n${e.message ?: e.javaClass.simpleName}\n\n" +
                                "Toca la pantalla o pulsa OK para abrir los ajustes."
                        )
                    } else if (!silent) {
                        showStatus("No se pudo actualizar: ${e.message ?: e.javaClass.simpleName}")
                        ui.postDelayed({ hideStatus() }, 4000)
                    }
                }
            }
        }
    }

    private fun onGroupClick(pos: Int) {
        groupIndex = pos
        prefs.lastGroup = groups[pos]
        applyFilter()
        focusChannel(0)
    }

    private fun onChannelClick(pos: Int) {
        val ch = shown.getOrNull(pos) ?: return
        play(ch)
        closePanel()
    }

    private fun toggleFav(pos: Int) {
        val ch = shown.getOrNull(pos) ?: return
        if (!favs.add(ch.url)) favs.remove(ch.url)
        prefs.saveFavs(favs)
        val copy = LinkedHashSet(favs)
        io.execute {
            try {
                repo.pushFavs(copy)
            } catch (e: Exception) {
                // sin servidor o sin red: los favoritos siguen guardados en la tele
            }
        }
        if (groups.getOrElse(groupIndex) { ALL } == FAV) {
            applyFilter()
            focusChannel(pos.coerceAtMost(shown.size - 1))
        } else {
            channelAdapter.notifyItemChanged(pos)
        }
    }

    private fun favFocused() {
        val f = currentFocus ?: return
        if (f.parent === channelList) {
            val pos = channelList.getChildAdapterPosition(f)
            if (pos >= 0) toggleFav(pos)
        }
    }

    // ---------------------------------------------------------------- interfaz

    private fun openSetup() {
        closePanel()
        setupLauncher.launch(Intent(this, SetupActivity::class.java))
    }

    private fun openPanel() {
        panel.visibility = View.VISIBLE
        panelVisible = true
        val idx = shown.indexOfFirst { it.url == current?.url }
        if (shown.isEmpty()) {
            groupList.postDelayed({
                groupList.findViewHolderForAdapterPosition(groupIndex)?.itemView?.requestFocus()
            }, 60)
        } else {
            focusChannel(if (idx < 0) 0 else idx)
        }
    }

    private fun closePanel() {
        panel.visibility = View.GONE
        panelVisible = false
    }

    private fun focusChannel(pos: Int) {
        if (shown.isEmpty()) return
        channelList.scrollToPosition(pos)
        channelList.postDelayed({
            val holder = channelList.findViewHolderForAdapterPosition(pos)
            if (holder != null) holder.itemView.requestFocus() else channelList.requestFocus()
        }, 60)
    }

    private fun showOsd(text: String) {
        osd.text = text
        osd.visibility = View.VISIBLE
        ui.removeCallbacks(hideOsd)
        ui.postDelayed(hideOsd, 3000)
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        status.visibility = View.GONE
    }

    // ---------------------------------------------------------------- mando

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0

        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (down) player?.let { it.playWhenReady = !it.playWhenReady }
            return true
        }

        if (panelVisible) {
            if (code == KeyEvent.KEYCODE_MENU) {
                if (down) favFocused()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (down) step(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (down) step(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MENU -> {
                if (down) {
                    if (all.isEmpty()) openSetup() else openPanel()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (panelVisible) closePanel() else super.onBackPressed()
    }
}
