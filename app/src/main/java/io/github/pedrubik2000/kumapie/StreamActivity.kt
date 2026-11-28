package io.github.pedrubik2000.kumapie

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import io.github.pedrubik2000.kumapie.player.LiveLines
import io.github.pedrubik2000.kumapie.ui.rememberPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.common.Player
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.StreamStart
import io.github.pedrubik2000.kumapie.data.next
import io.github.pedrubik2000.kumapie.data.nextQuery
import io.github.pedrubik2000.kumapie.ui.TopNote
import io.github.pedrubik2000.kumapie.data.notAnEpisode
import io.github.pedrubik2000.kumapie.data.notReady
import io.github.pedrubik2000.kumapie.data.EPISODE_MIN_MS
import io.github.pedrubik2000.kumapie.data.watchReady
import io.github.pedrubik2000.kumapie.data.EpisodeDetail
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.Tr
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.ui.Colors
import io.github.pedrubik2000.kumapie.ui.KumapieTheme
import io.github.pedrubik2000.kumapie.ui.ScenePlayer
import io.github.pedrubik2000.kumapie.ui.joinNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class StreamActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var watchFile = ""
    private var alone: LiveLines? = null
    private var aloneView: PlayerView? = null
    private var chained = false
    private var leaving = false

    override fun onStart() {
        super.onStart()
        io.github.pedrubik2000.kumapie.data.Playing.on = true
    }

    override fun onStop() {
        io.github.pedrubik2000.kumapie.data.Playing.on = false
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = Settings(this)
        if (savedInstanceState?.getBoolean(LEAVING) == true) return restart(settings)
        if (intent.hasExtra(RESTART)) return if (intent.getStringExtra(RESTART) == token && Restart.wanted(settings)) restart(settings) else finish()
        if (Restart.owed && intent.getBooleanExtra(Restart.INSIDE, false) && Restart.wanted(settings)) { leaving = true; return Restart.pay(this, settings) }
        val video = intent.data?.toString()
        val start = video?.let(Uri::parse)?.takeIf { it.scheme == "kumapie" && it.host in setOf("play", "watch") }?.let { StreamStart.of(query(it), settings.prefs) }
        if (start is StreamStart.Alone) {
            Tr.use(if (settings.server.isEmpty()) settings.menuLanguage else settings.speaks)
            return setContent { KumapieTheme { Alone(settings, Uri.parse(video), start) } }
        }
        if (video == null || settings.server.isEmpty()) {
            Toast.makeText(this, tr("Open kumapie once and set the PC's address first"), Toast.LENGTH_LONG).show()
            return finish()
        }
        Tr.use(settings.speaks)
        setContent { KumapieTheme { Stream(settings, video, start as? StreamStart.Pc) } }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        io.github.pedrubik2000.kumapie.ui.Pad.seen(event)
        val live = alone
        val by = when (event.keyCode) { android.view.KeyEvent.KEYCODE_DPAD_UP -> -0.5; android.view.KeyEvent.KEYCODE_DPAD_DOWN -> 0.5; else -> null }
        if (live != null && by != null && live.source == "jimaku" && aloneView?.isControllerFullyVisible != true) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) live.offset += by
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Composable
    private fun Stream(settings: Settings, video: String, pc: StreamStart.Pc?) {
        val api = remember { Api(settings.server, settings.profile) }
        var detail by remember { mutableStateOf<EpisodeDetail?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var step by remember { mutableStateOf("") }
        var url by remember { mutableStateOf<String?>(null) }
        var next by remember { mutableStateOf("") }
        val link = Uri.parse(video)
        val title = intent.getStringExtra("title") ?: link.lastPathSegment.orEmpty()
        val personal = remember { io.github.pedrubik2000.kumapie.lang.PersonalDictionary.get(this) }
        LaunchedEffect(Unit) {
            personal.sync(api::personalDictionary)
            runCatching {
                if (pc != null) return@runCatching watched(api, pc.query, { step = it }, { if (url == null) url = it }, { next = it })
                if (link.scheme?.startsWith("kumapie") == true) return@runCatching prepared(api, link) { step = it }
                val subs = subtitles()
                val (lang, spoken) = SPOKEN.firstNotNullOfOrNull { l -> subs[l]?.let { l to it } }
                    ?: error(tr("NuvioTV sent no Japanese, German or English subtitles: pick an add-on that has them"))
                val under = subs[settings.speaks]?.takeIf { settings.speaks != lang }
                val id = api.stream(lang, title, read(spoken), under?.let { read(it) })
                val resume = intent.getIntExtra("position", 0) / 1000.0
                api.episode(id).copy(video = video, show = title, resume = resume.takeIf { it > 0 })
            }.mapCatching { personal.join(joinNames(this@StreamActivity, settings, it)) }
                .onSuccess { detail = it }.onFailure { error = it.message ?: it.toString() }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val shown = detail
            val playing = url
            val failure = error
            when {
                playing != null -> Watching(api, settings, playing, shown, error ?: tr("Words coming: %1\$s", step), next, link)
                failure != null -> Message(failure)
                shown == null && link.scheme?.startsWith("kumapie") == true -> Message(tr("The PC is making the episode: %1\$s", step.ifEmpty { "…" }))
                shown == null -> Message(tr("%1\$s: getting its words from the PC (a new subtitle file: about a minute)…", title))
                else -> ScenePlayer(api, settings, shown, onClose = ::finish, onPlayer = { player = it })
            }
        }
    }

    @Composable
    private fun Message(text: String) = Box(Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Colors.dim, fontSize = 22.sp)
    }

    private suspend fun prepared(api: Api, link: Uri, shown: (String) -> Unit): EpisodeDetail {
        val query = query(link)
        while (true) {
            val r = api.prepare(query)
            when (r.optString("state")) {
                "ready" -> return api.episode(r.getString("id"))
                "failed" -> error(tr("The PC couldn't make it: %1\$s", r.optString("error")))
                else -> shown(r.optString("step").ifEmpty { tr("waiting for the job before it") })
            }
            delay(3_000)
        }
    }

    private suspend fun watched(api: Api, query: Map<String, String>, shown: (String) -> Unit, video: (String) -> Unit, onNext: (String) -> Unit): EpisodeDetail {
        watchFile = query["file"].orEmpty()
        val r = api.watchReady(query, shown, video, onNext) { watchFile = it }
        val d = api.episode(r.getString("id"))
        val at = withContext(Dispatchers.Main) { player?.currentPosition ?: 0L } / 1000.0
        return d.copy(video = r.getString("url"), show = query["title"].orEmpty(), resume = io.github.pedrubik2000.kumapie.data.resumeAt(at, d.resume))
    }

    @Composable
    private fun Watching(api: Api, settings: Settings, video: String, shown: EpisodeDetail?, note: String, next: String, link: Uri) {
        val p = rememberPlayer(video, null, link.getQueryParameter("lang") ?: "ja")
        remember(p) { player = p; p.playWhenReady = true }
        val upcoming by rememberUpdatedState(next)
        var failed by remember { mutableStateOf<String?>(null) }
        DisposableEffect(p) {
            val ended = object : Player.Listener {
                override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                    if (!io.github.pedrubik2000.kumapie.player.DecodeRetry.recovered(p, e)) failed = tr("The video stopped: %1\$s", e.cause?.message ?: e.message ?: e.errorCodeName)
                    else if (io.github.pedrubik2000.kumapie.player.DecodeRetry.reopening(e)) failed = tr("Reconnecting…")
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY && failed == tr("Reconnecting…")) failed = null
                    if (state != Player.STATE_ENDED) return
                    if (notAnEpisode(p.duration)) { failed = notReady(); return }
                    nextQuery(query(link), upcoming)?.let { chained = true; startActivity(intent(this@StreamActivity, it)); finish() }
                }
            }
            p.addListener(ended)
            onDispose { p.removeListener(ended) }
        }
        val live = remember(p) { LiveLines(p) { api.discoverSubs(link.getQueryParameter("type").orEmpty(), link.getQueryParameter("id").orEmpty(), watchFile) } }
        LaunchedEffect(live) { live.run() }
        if (shown != null) {
            remember(shown) { live.stop() }
            return Box(Modifier.fillMaxSize()) { ScenePlayer(api, settings, shown, onClose = ::finish, player = p); TopNote(failed?.takeIf { it == notReady() }) }
        }
        Box(Modifier.fillMaxSize()) {
            AndroidView({ PlayerView(it).apply { player = p; useController = true; subtitleView?.visibility = android.view.View.GONE; requestFocus() } },
                Modifier.fillMaxSize(), onRelease = { it.player = null })
            live.line?.let {
                Text(it, color = Color.White, fontSize = 30.sp, lineHeight = 40.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp).background(Color(0x99000000)).padding(16.dp, 8.dp))
            }
            Text(failed ?: note, color = Color.White, fontSize = 16.sp, modifier = Modifier.align(Alignment.TopCenter).padding(24.dp)
                .background(Color(0x99000000)).padding(12.dp, 6.dp))
        }
    }

    @Composable
    private fun Alone(settings: Settings, link: Uri, start: StreamStart.Alone) {
        val id = link.getQueryParameter("id").orEmpty()
        val show = link.getQueryParameter("show").orEmpty()
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val p = rememberPlayer(start.url, start.resumeAt, "ja")
        remember(p) { player = p }
        val torrent = start.url.startsWith("${io.github.pedrubik2000.kumapie.torrent.TorrentRef.SCHEME}://")
        var shown by remember { mutableStateOf<io.github.pedrubik2000.kumapie.data.EpisodeDetail?>(null) }
        var note by remember { mutableStateOf<String?>(null) }
        var reconnecting by remember { mutableStateOf(false) }
        var buffering by remember { mutableStateOf(false) }
        val s = remember(p) {
            io.github.pedrubik2000.kumapie.lang.AloneStream(settings.prefs, io.github.pedrubik2000.kumapie.data.Addons(settings.addons, settings.prefs), p, id,
                link.getQueryParameter("type") ?: "series", show, link.getQueryParameter("title") ?: show,
                start.url, link.getQueryParameter("file").orEmpty()) { note = it }
        }
        val live = s.live
        val started = io.github.pedrubik2000.kumapie.ui.rememberReady(p, torrent, shown != null || live.noLines, note != null)
        val startedNow by rememberUpdatedState(started)
        androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_PAUSE) { s.leave() }
        DisposableEffect(p) {
            val listener = object : Player.Listener {
                override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                    reconnecting = false
                    if (!io.github.pedrubik2000.kumapie.player.DecodeRetry.recovered(p, e)) {
                        note = tr("The video stopped: %1\$s", e.cause?.message ?: e.message ?: e.errorCodeName)
                        if (torrent && !startedNow) io.github.pedrubik2000.kumapie.torrent.TorrentEngine.playerFailed(note.orEmpty())
                    } else reconnecting = io.github.pedrubik2000.kumapie.player.DecodeRetry.reopening(e)
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY || state == Player.STATE_ENDED) reconnecting = false
                    if (state == Player.STATE_ENDED) scope.launch { playNext(settings, query(link), p.duration) { note = it } }
                }
            }
            p.addListener(listener)
            onDispose {
                p.removeListener(listener)
                s.leave()
                io.github.pedrubik2000.kumapie.lang.DeviceSync.soon(applicationContext)
                alone = null; aloneView = null
            }
        }
        LaunchedEffect(s) { s.keepPlace() }
        LaunchedEffect(live) { live.run() }
        LaunchedEffect(live.noLines) { if (live.noLines && note == null) note = tr("No Japanese subtitles: none in this file, none on Jimaku") }
        val label = shownLabel(live)
        val context = androidx.compose.ui.platform.LocalContext.current
        LaunchedEffect(live.lines) {
            if (live.lines.isEmpty()) return@LaunchedEffect
            shown = runCatching {
                s.build(context, io.github.pedrubik2000.kumapie.ui.TvMining.languages(context, settings), (p.duration.takeIf { it > 0 } ?: 0L) / 1000.0, { p.currentPosition / 1000.0 }) {
                    note = tr("Words: downloading the Japanese dictionary (once)…")
                }
            }.getOrElse { note = wordsFailed(it); return@LaunchedEffect }
        }
        var ctl by remember { mutableStateOf<io.github.pedrubik2000.kumapie.player.SceneController?>(null) }
        LaunchedEffect(live.fitted, live.pieces, live.english, live.englishOpen, ctl, ctl?.scenes) { ctl?.let { s.fitLanded(it::shift); s.piecesLanded(it::shift); s.englishLanded(it) } }
        val ready = shown?.takeIf { started }
        remember(live, started, buffering, ready) { alone = live.takeIf { started && !buffering && ready == null } }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val view = io.github.pedrubik2000.kumapie.ui.StreamVideo(p, ready == null, started, buffering, s.frameRate.value)
            remember(view, ready) { aloneView = view.takeIf { ready == null } }
            if (ready != null && view != null) {
                remember(ready) { alone = null; note = null }
                val backend = remember { io.github.pedrubik2000.kumapie.ui.TvMining.backend(context, settings, ready.lang) }
                androidx.compose.runtime.key(ready) { io.github.pedrubik2000.kumapie.ui.ScenePlayer(backend, settings, ready, onClose = ::finish, player = p, onController = { ctl = it }, video = view) }
                TopNote(note?.takeIf { it == notReady() })
            } else AlonePlain(live.line, if (reconnecting) tr("Reconnecting…") else note ?: label, buffering, io.github.pedrubik2000.kumapie.ui.rememberSubtitleGap(p), settings.japaneseFont)
            io.github.pedrubik2000.kumapie.ui.TorrentWait(p, started, remember(link) { waitInfo(link, id, show) }, settings, shown, live.noLines, ::finish, torrent, live) { buffering = it }
        }
    }

    private fun wordsFailed(e: Throwable): String {
        if (e is kotlinx.coroutines.CancellationException) throw e
        android.util.Log.w("kumapie", "words failed", e)
        return tr("Words couldn't load (%1\$s). The video plays on.", e.message ?: e.javaClass.simpleName)
    }

    private fun waitInfo(link: Uri, id: String, show: String): io.github.pedrubik2000.kumapie.ui.WaitInfo {
        val settings = Settings(this)
        val hide = io.github.pedrubik2000.kumapie.lang.WaitPick.hideTitle(settings.blurUnwatched, settings.prefs.getStringSet("alone_seen", emptySet()).orEmpty(), id)
        return io.github.pedrubik2000.kumapie.ui.WaitInfo(
            id, show.ifEmpty { link.getQueryParameter("title").orEmpty() }, io.github.pedrubik2000.kumapie.lang.AloneStream.episodeNumber(id),
            if (hide) "" else link.getQueryParameter("title")?.takeIf { it != show }.orEmpty(), link.getQueryParameter("file").orEmpty(),
            io.github.pedrubik2000.kumapie.data.Backdrops.saved(this, io.github.pedrubik2000.kumapie.data.Addons.titleOf(id)), hide)
    }

    @Composable
    private fun shownLabel(live: LiveLines): String? {
        val label = shiftLabel(live)
        var show by remember { mutableStateOf(false) }
        LaunchedEffect(label) { show = label != null; delay(5000); show = false }
        return label?.takeIf { show }
    }

    private fun shiftLabel(live: LiveLines): String? = when {
        live.source != "jimaku" -> null
        else -> tr("Jimaku's subtitles") + (if (live.offset == 0.0) "" else " · " + "%+.1f s".format(live.offset)) +
            (io.github.pedrubik2000.kumapie.lang.AloneStream.fitNote(live) ?: if (live.offset == live.fitted) tr("fitted") else null).let { if (it == null) "" else " · $it" } + " · ▲▼"
    }

    @Composable
    private fun AlonePlain(line: String?, top: String?, buffering: Boolean, gap: androidx.compose.ui.unit.Dp, font: String) {
        Box(Modifier.fillMaxSize()) {
            line?.let {
                Text(it, color = Color.White, fontSize = 34.sp, lineHeight = 41.5.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    fontFamily = io.github.pedrubik2000.kumapie.ui.JapaneseFonts.family(font), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = gap).widthIn(max = 900.dp).background(Color(0xB3000000), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(28.dp, 14.dp))
            }
            top?.takeIf { !buffering }?.let {
                Text(it, color = Color.White, fontSize = 16.sp, modifier = Modifier.align(Alignment.TopCenter).padding(24.dp)
                    .background(Color(0x99000000)).padding(12.dp, 6.dp))
            }
        }
    }

    private suspend fun playNext(settings: Settings, query: Map<String, String>, durationMs: Long, say: (String) -> Unit) {
        val addons = io.github.pedrubik2000.kumapie.data.Addons(settings.addons, settings.prefs)
        val next = runCatching { addons.next(query, durationMs) { say(tr("Next: episode %1\$d…", it)) } }.getOrElse { return say(it.message ?: it.toString()) } ?: return
        chained = true
        startActivity(intent(this, next))
        finish()
    }

    private fun restart(settings: Settings) {
        leaving = true
        Restart.begin()
        setContent { Box(Modifier.fillMaxSize().background(Color.Black)) }
        Restart.now(settings)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(LEAVING, leaving)
    }

    override fun finish() {
        if (leaving) return
        val settings = Settings(this)
        if (!chained && player != null && callingActivity == null && Restart.wanted(settings)) Restart.owed = true
        player?.let { p ->
            val pos = p.currentPosition
            val duration = p.duration.takeIf { it > 0 } ?: 0L
            setResult(RESULT_OK, Intent().putExtra("position", pos.toInt()).putExtra("duration", duration.toInt())
                .putExtra("end_by", if (duration >= EPISODE_MIN_MS && pos >= duration - 2_000) "playback_completion" else "user"))
        }
        super.finish()
    }

    private fun subtitles(): Map<String, Uri> {
        @Suppress("DEPRECATION")
        val uris = intent.getParcelableArrayExtra("subs")?.filterIsInstance<Uri>()
            ?: intent.getStringArrayExtra("subs")?.map(Uri::parse).orEmpty()
        val files = intent.getStringArrayExtra("subs.filename").orEmpty()
        val names = intent.getStringArrayExtra("subs.name").orEmpty()
        val out = LinkedHashMap<String, Uri>()
        uris.forEachIndexed { i, uri ->
            val said = (files.getOrNull(i)?.substringBefore('_') ?: names.getOrNull(i).orEmpty()).lowercase()
            CODES.entries.firstOrNull { said in it.value }?.let { out.putIfAbsent(it.key, uri) }
        }
        return out
    }

    private suspend fun read(uri: Uri): String = withContext(Dispatchers.IO) {
        val bytes = if (uri.scheme == "content" || uri.scheme == "file") requireNotNull(contentResolver.openInputStream(uri)) { "no stream for $uri" }.use { it.readBytes() }
        else URL(uri.toString()).readBytes()
        String(bytes, Charsets.UTF_8)
    }

    companion object {
        fun intent(context: android.content.Context, query: Map<String, String>): Intent = Intent(context, StreamActivity::class.java)
            .setData(query.entries.fold(Uri.parse("kumapie://watch").buildUpon()) { b, (k, v) -> b.appendQueryParameter(k, v) }.build()).putExtra(Restart.INSIDE, true)

        private const val RESTART = "restart"
        private const val LEAVING = "leaving"
        private val token = java.util.UUID.randomUUID().toString()

        fun restartIntent(context: android.content.Context): Intent = Intent(context, StreamActivity::class.java).putExtra(RESTART, token)

        private fun query(link: Uri) = link.queryParameterNames.associateWith { link.getQueryParameter(it).orEmpty() }
        private val SPOKEN = listOf("ja", "de", "en")
        private val CODES = mapOf(
            "ja" to setOf("ja", "jp", "jpn", "japanese"), "de" to setOf("de", "ger", "deu", "german"),
            "en" to setOf("en", "eng", "english"), "es" to setOf("es", "spa", "spanish"),
        )
    }
}
