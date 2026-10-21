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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.appcompat.app.AlertDialog
import java.util.Locale
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
        const val BACK = "◀ Series"
        const val SERIES_SCHEME = "series://"
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
    private lateinit var sectionRow: View
    private lateinit var btnLive: Button
    private lateinit var btnMovies: Button
    private lateinit var btnSeries: Button

    private var player: ExoPlayer? = null
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var section = Section.LIVE
    private var liveList: List<Channel> = emptyList()
    private val lists = HashMap<Section, List<Channel>>()
    private var loadToken = 0

    private var all: List<Channel> = emptyList()
    private var shown: List<Channel> = emptyList()
    private var groups: List<String> = listOf(ALL, FAV)
    private var groupIndex = 0
    private var favs: MutableSet<String> = LinkedHashSet()

    // Episodios de una serie abierta (se guarda la lista de series para volver)
    private var inEpisodes = false
    private var savedAll: List<Channel> = emptyList()
    private var savedGroups: List<String> = emptyList()
    private var savedIndex = 0

    private var current: Channel? = null
    private var vodPlaying = false
    private var resumeMs = 0L
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
            if (result.resultCode == RESULT_OK) {
                // Puede haber cambiado la cuenta: se descartan películas y series guardadas.
                repo.clearVodCache()
                lists.clear()
                section = Section.LIVE
                inEpisodes = false
                updateSectionButtons()
                refresh(false)
            }
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
        sectionRow = findViewById(R.id.sectionRow)
        btnLive = findViewById(R.id.btnLive)
        btnMovies = findViewById(R.id.btnMovies)
        btnSeries = findViewById(R.id.btnSeries)

        // Control táctil (móvil o tableta): tocar la pantalla abre o cierra el menú.
        findViewById<View>(R.id.root).setOnClickListener {
            when {
                panelVisible -> closePanel()
                liveList.isEmpty() && all.isEmpty() -> openSetup()
                else -> openPanel()
            }
        }

        groupAdapter.label = { i -> groups[i] }
        channelAdapter.label = { i ->
            val c = shown[i]
            when {
                c.url.startsWith(SERIES_SCHEME) -> (if (favs.contains(c.url)) "★ " else "") + c.name + "  ›"
                favs.contains(c.url) -> "★ " + c.name
                else -> c.name
            }
        }

        groupList.layoutManager = LinearLayoutManager(this)
        groupList.adapter = groupAdapter
        channelList.layoutManager = LinearLayoutManager(this)
        channelList.adapter = channelAdapter

        btnLive.setOnClickListener { switchSection(Section.LIVE) }
        btnMovies.setOnClickListener { switchSection(Section.MOVIES) }
        btnSeries.setOnClickListener { switchSection(Section.SERIES) }
        findViewById<Button>(R.id.btnTracks).setOnClickListener { showTrackMenu() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { openSetup() }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener {
            if (section == Section.LIVE) {
                closePanel()
                refresh(false)
            } else {
                switchSection(section, true)
            }
        }

        updateSectionButtons()
        refreshLists()

        if (!repo.hasSource()) {
            openSetup()
        } else {
            val cached = repo.cachedText()
            if (cached != null) {
                io.execute {
                    val list = M3uParser.parse(cached)
                    ui.post {
                        liveList = list
                        if (section == Section.LIVE) setChannels(list, true)
                    }
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
        current?.let { play(it, resumeMs) }
        resumeMs = 0
    }

    override fun onStop() {
        super.onStop()
        resumeMs = if (vodPlaying) (player?.currentPosition ?: 0L) else 0L
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
                    Player.STATE_ENDED -> if (vodPlaying) playNextEpisode()
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

    private fun play(ch: Channel, startMs: Long = 0L) {
        current = ch
        vodPlaying = section != Section.LIVE
        retries = 0
        if (!vodPlaying) prefs.lastUrl = ch.url
        showOsd(ch.name)
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().clearOverrides().build()
        p.setMediaItem(MediaItem.fromUri(ch.url), if (startMs > 0) startMs else C.TIME_UNSET)
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
                "Toca la pantalla o pulsa OK para elegir otro."
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
        val ch = shown[n]
        if (!ch.url.startsWith(SERIES_SCHEME)) play(ch)
    }

    /** Al acabar un episodio pasa al siguiente de la misma temporada. */
    private fun playNextEpisode() {
        if (!inEpisodes) return
        val i = shown.indexOfFirst { it.url == current?.url }
        if (i >= 0 && i + 1 < shown.size) play(shown[i + 1])
    }

    // ---------------------------------------------------------------- audio y subtítulos

    private fun trackName(f: Format, n: Int): String {
        var name = f.label
        if (name.isNullOrBlank()) {
            val lang = f.language
            if (!lang.isNullOrBlank() && lang != "und") {
                name = Locale.forLanguageTag(lang).getDisplayLanguage(Locale.getDefault())
                    .replaceFirstChar { it.uppercase() }
            }
        }
        if (name.isNullOrBlank()) name = "Pista $n"
        if (f.channelCount > 2) name += " (${f.channelCount} canales)"
        return name
    }

    private fun showTrackMenu() {
        val p = player
        if (p == null || current == null) {
            showOsd("Primero reproduce algo")
            return
        }
        closePanel()
        AlertDialog.Builder(this)
            .setTitle("Audio y subtítulos")
            .setItems(arrayOf("Audio", "Subtítulos")) { _, which ->
                chooseTrack(if (which == 0) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT)
            }
            .show()
    }

    private fun chooseTrack(type: Int) {
        val p = player ?: return
        val isText = type == C.TRACK_TYPE_TEXT
        val groups = p.currentTracks.groups.filter { it.type == type && it.length > 0 }
        if (groups.isEmpty()) {
            showOsd(if (isText) "Este contenido no trae subtítulos" else "Este contenido no trae más audios")
            return
        }
        val names = ArrayList<String>()
        val picks = ArrayList<Pair<Tracks.Group, Int>?>()
        var checked = -1
        if (isText) {
            names.add("Desactivados")
            picks.add(null)
            if (p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) checked = 0
        }
        var n = 1
        for (g in groups) {
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                names.add(trackName(g.getTrackFormat(i), n++))
                picks.add(Pair(g, i))
                if (g.isTrackSelected(i) && (!isText || checked < 0)) checked = names.size - 1
            }
        }
        if (names.isEmpty()) {
            showOsd("No hay pistas compatibles")
            return
        }
        AlertDialog.Builder(this)
            .setTitle(if (isText) "Subtítulos" else "Audio")
            .setSingleChoiceItems(names.toTypedArray(), checked) { d, which ->
                val b = p.trackSelectionParameters.buildUpon()
                val pick = picks[which]
                if (pick == null) {
                    b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                } else {
                    b.setTrackTypeDisabled(type, false)
                    b.setOverrideForType(TrackSelectionOverride(pick.first.mediaTrackGroup, pick.second))
                }
                p.trackSelectionParameters = b.build()
                showOsd(names[which])
                d.dismiss()
            }
            .show()
    }

    private fun togglePause() {
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
        showOsd((if (p.playWhenReady) "▶ " else "⏸ ") + clock(p.currentPosition, p.duration))
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val dur = p.duration
        var pos = p.currentPosition + deltaMs
        if (pos < 0) pos = 0
        if (dur != C.TIME_UNSET && pos > dur) pos = dur
        p.seekTo(pos)
        showOsd(clock(pos, dur))
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    private fun clock(pos: Long, dur: Long): String =
        fmt(pos) + if (dur != C.TIME_UNSET && dur > 0) " / " + fmt(dur) else ""

    // ---------------------------------------------------------------- lista

    private fun setChannels(list: List<Channel>, auto: Boolean) {
        all = list
        val names = list.map { it.group }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        groups = listOf(ALL, FAV) + names
        val idx = if (section == Section.LIVE) groups.indexOf(prefs.lastGroup) else -1
        groupIndex = if (idx < 0) 0 else idx
        applyFilter()
        updateInfo()
        hideStatus()

        if (auto && current == null) {
            val last = prefs.lastUrl
            val ch = list.firstOrNull { it.url == last }
            if (ch != null) {
                play(ch)
            } else if (list.isNotEmpty()) {
                openPanel()
            }
        }
    }

    private fun updateInfo() {
        info.text = if (inEpisodes) {
            "${all.size} episodios"
        } else {
            "${all.size} ${section.noun}"
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

    /** Descarga de nuevo los canales en directo. */
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
                    liveList = list
                    if (section == Section.LIVE) setChannels(list, true) else hideStatus()
                    updateSectionButtons()
                }
            } catch (e: Exception) {
                ui.post {
                    if (liveList.isEmpty()) {
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

    // ---------------------------------------------------------------- secciones

    private fun updateSectionButtons() {
        sectionRow.visibility = if (repo.xtreamMode()) View.VISIBLE else View.GONE
        fun mark(b: Button, s: Section) {
            b.text = (if (section == s) "● " else "") + s.label
        }
        mark(btnLive, Section.LIVE)
        mark(btnMovies, Section.MOVIES)
        mark(btnSeries, Section.SERIES)
    }

    private fun switchSection(sec: Section, force: Boolean = false) {
        inEpisodes = false
        section = sec
        updateSectionButtons()
        val token = ++loadToken

        if (sec == Section.LIVE) {
            setChannels(liveList, false)
            focusChannel(0)
            return
        }

        val cached = if (force) null else lists[sec]
        if (cached != null) {
            setChannels(cached, false)
            focusChannel(0)
            return
        }

        all = emptyList()
        shown = emptyList()
        groups = listOf(ALL, FAV)
        groupIndex = 0
        refreshLists()
        info.text = ""
        showStatus("Cargando ${sec.noun}…\nLa primera vez puede tardar un poco.")
        io.execute {
            try {
                val list = repo.loadVodSection(sec, force)
                ui.post {
                    if (token != loadToken) return@post
                    lists[sec] = list
                    setChannels(list, false)
                    focusChannel(0)
                }
            } catch (e: Exception) {
                ui.post {
                    if (token != loadToken) return@post
                    section = Section.LIVE
                    updateSectionButtons()
                    setChannels(liveList, false)
                    showStatus(
                        "No se pudieron cargar las ${sec.noun}\n${e.message ?: e.javaClass.simpleName}"
                    )
                    ui.postDelayed({ hideStatus() }, 5000)
                }
            }
        }
    }

    private fun openSeries(ch: Channel) {
        val id = ch.url.removePrefix(SERIES_SCHEME)
        showStatus("Cargando episodios de «${ch.name}»…")
        val token = ++loadToken
        io.execute {
            try {
                val eps = repo.loadEpisodes(id)
                ui.post {
                    if (token != loadToken) return@post
                    enterEpisodes(ch.name, eps)
                }
            } catch (e: Exception) {
                ui.post {
                    if (token != loadToken) return@post
                    showStatus("No se pudo abrir la serie\n${e.message ?: e.javaClass.simpleName}")
                    ui.postDelayed({ hideStatus() }, 4000)
                }
            }
        }
    }

    private fun enterEpisodes(title: String, eps: List<Channel>) {
        if (!inEpisodes) {
            savedAll = all
            savedGroups = groups
            savedIndex = groupIndex
        }
        inEpisodes = true
        all = eps
        val seasons = eps.map { it.group }.distinct()
            .sortedBy { s -> s.filter { it.isDigit() }.toIntOrNull() ?: 0 }
        groups = listOf(BACK) + seasons
        groupIndex = 1.coerceAtMost(groups.size - 1)
        applyFilter()
        info.text = title
        hideStatus()
        focusChannel(0)
    }

    private fun leaveEpisodes() {
        if (!inEpisodes) return
        inEpisodes = false
        all = savedAll
        groups = savedGroups
        groupIndex = savedIndex
        applyFilter()
        updateInfo()
    }

    private fun onGroupClick(pos: Int) {
        if (inEpisodes && pos == 0) {
            leaveEpisodes()
            focusChannel(0)
            return
        }
        groupIndex = pos
        if (section == Section.LIVE) prefs.lastGroup = groups[pos]
        applyFilter()
        focusChannel(0)
    }

    private fun onChannelClick(pos: Int) {
        val ch = shown.getOrNull(pos) ?: return
        if (ch.url.startsWith(SERIES_SCHEME)) {
            openSeries(ch)
            return
        }
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
        val downRepeat = event.action == KeyEvent.ACTION_DOWN

        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (down) togglePause()
            return true
        }

        if (code == KeyEvent.KEYCODE_CAPTIONS || code == KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK) {
            if (down) showTrackMenu()
            return true
        }

        if (panelVisible) {
            if (code == KeyEvent.KEYCODE_MENU) {
                if (down) favFocused()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        if (vodPlaying) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (down) togglePause()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    if (downRepeat) seekBy(-15000)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    if (downRepeat) seekBy(30000)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> {
                    if (down) openPanel()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    if (down) step(1)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    if (down) step(-1)
                    return true
                }
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
                    if (liveList.isEmpty() && all.isEmpty()) openSetup() else openPanel()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        when {
            panelVisible -> closePanel()
            vodPlaying -> {
                // Atrás durante una película o serie: para y vuelve al menú.
                player?.stop()
                current = null
                vodPlaying = false
                hideStatus()
                openPanel()
            }
            else -> super.onBackPressed()
        }
    }
}
