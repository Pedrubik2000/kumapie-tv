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

/**
 * kumapie as NuvioTV's external player (Stremio streams): NuvioTV sends the stream (ACTION_VIEW, any video type) with its
 * subtitle files (MX Player's extras: "subs" Uris, "subs.filename" "<lang>_<name>.srt", "position" ms). The spoken
 * language's file (and the menu language's, for the line under it) goes to the PC, which makes it a kumapie episode
 * (POST /api/tv/stream); kumapie's player then plays the stream with it: word colours, the picker, mining. Leaving
 * hands NuvioTV the position back (MX Player's result), so its Continue watching and Trakt stay right.
 */
class StreamActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var watchFile = "" // the Discover file playing: its Jimaku lines are asked by its name
    private var alone: LiveLines? = null // the no-PC player's lines: UP / DOWN shift Jimaku's
    private var aloneView: PlayerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = Settings(this)
        val video = intent.data?.toString()
        if (video != null && Uri.parse(video).let { it.scheme == "kumapie" && it.host == "play" }) {
            // Without a PC: the menu language picked in the TV's first steps, as MainActivity.
            Tr.use(if (settings.server.isEmpty()) settings.prefs.getString(io.github.pedrubik2000.kumapie.ui.ALONE_LANG, "").orEmpty() else settings.speaks)
            return setContent { KumapieTheme { Alone(settings, Uri.parse(video)) } }
        }
        if (video == null || settings.server.isEmpty()) {
            Toast.makeText(this, tr("Open kumapie once and set the PC's address first"), Toast.LENGTH_LONG).show()
            return finish()
        }
        Tr.use(settings.speaks)
        setContent { KumapieTheme { Stream(settings, video) } }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        io.github.pedrubik2000.kumapie.ui.Pad.seen(event)
        // Jimaku's lines early or late (another release's timing): UP = 0.5 s earlier, DOWN = later, with the controls hidden.
        val live = alone
        val by = when (event.keyCode) { android.view.KeyEvent.KEYCODE_DPAD_UP -> -0.5; android.view.KeyEvent.KEYCODE_DPAD_DOWN -> 0.5; else -> null }
        if (live != null && by != null && live.source == "jimaku" && aloneView?.isControllerFullyVisible != true) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) live.offset += by
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Composable
    private fun Stream(settings: Settings, video: String) {
        val api = remember { Api(settings.server, settings.profile) }
        var detail by remember { mutableStateOf<EpisodeDetail?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var step by remember { mutableStateOf("") }
        var url by remember { mutableStateOf<String?>(null) } // Discover: plays before the words are ready
        var next by remember { mutableStateOf("") }
        val link = Uri.parse(video)
        val title = intent.getStringExtra("title") ?: link.lastPathSegment.orEmpty()
        val personal = remember { io.github.pedrubik2000.kumapie.lang.PersonalDictionary.get(this) }
        LaunchedEffect(Unit) {
            personal.sync(api::personalDictionary)
            runCatching {
                if (link.host == "watch") return@runCatching watched(api, link, { step = it }, { if (url == null) url = it }, { next = it })
                if (link.scheme?.startsWith("kumapie") == true) return@runCatching prepared(api, link) { step = it }
                val subs = subtitles()
                val (lang, spoken) = SPOKEN.firstNotNullOfOrNull { l -> subs[l]?.let { l to it } }
                    ?: error(tr("NuvioTV sent no Japanese, German or English subtitles: pick an add-on that has them"))
                val under = subs[settings.speaks]?.takeIf { settings.speaks != lang }
                val id = api.stream(lang, title, read(spoken), under?.let { read(it) })
                val resume = intent.getIntExtra("position", 0) / 1000.0
                api.episode(id).copy(video = video, show = title, resume = resume.takeIf { it > 0 })
            }.mapCatching { personal.join(joinNames(this@StreamActivity, settings, it)) } // names as one word + faces, as PlayerScreen
                .onSuccess { detail = it }.onFailure { error = it.message ?: it.toString() }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val shown = detail
            val playing = url
            when {
                playing != null -> Watching(api, settings, playing, shown, error ?: tr("Words coming: %1\$s", step), next, link)
                error != null -> Message(error!!)
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

    /**
     * kumapie://prepare?person&lang&type&id (the kumapie add-on's stream in NuvioTV): the PC downloads the title and
     * makes it a library episode (services\feed\stremio.py); asks every 3 s until it's ready, [shown] gets the step.
     */
    private suspend fun prepared(api: Api, link: Uri, shown: (String) -> Unit): EpisodeDetail {
        val query = link.queryParameterNames.associateWith { link.getQueryParameter(it).orEmpty() }
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

    /**
     * kumapie://watch?lang&type&id&hash&file&idx&title&show (kumapie's Discover page; no hash/file/idx: the file the PC
     * got ready for that episode): the PC gets Real-Debrid's link and makes only the words (kumapie-server
     * discover.py); the TV streams the link. Asks every 3 s: [shown] gets the step, [video] the link as soon as it
     * exists (it plays at once), [onNext] the next episode's id; answers the episode once its words are ready.
     */
    private suspend fun watched(api: Api, link: Uri, shown: (String) -> Unit, video: (String) -> Unit, onNext: (String) -> Unit): EpisodeDetail {
        val query = link.queryParameterNames.associateWith { link.getQueryParameter(it).orEmpty() }
        while (true) {
            val r = api.watch(query)
            onNext(r.optString("next"))
            watchFile = r.optString("file").ifEmpty { query["file"].orEmpty() }
            if (r.has("url")) video(r.getString("url"))
            when (r.optString("state")) {
                "ready" -> {
                    val d = api.episode(r.getString("id"))
                    val at = withContext(Dispatchers.Main) { player?.currentPosition ?: 0L } / 1000.0
                    // Where it was left when that is further in than the plain video got while its words loaded (it starts at 0:00).
                    return d.copy(video = r.getString("url"), show = query["title"].orEmpty(), resume = if (at > 1 && at >= (d.resume ?: 0.0)) at else d.resume)
                }
                "none" -> error(tr("No file is ready for this episode yet: pick one in its files"))
                "failed" -> error(tr("The PC couldn't get it: %1\$s", r.optString("error")))
                else -> shown(r.optString("step"))
            }
            delay(3_000)
        }
    }

    /**
     * A Discover stream playing: plain (the player's own controls, [note] on top) until its words are ready ([shown]),
     * then the same player goes on in [ScenePlayer]. Until then its Japanese lines live, SubMiner's way ([LiveLines]:
     * the file's own track, else Jimaku's file). At the video's end: the next episode's StreamActivity.
     */
    @Composable
    private fun Watching(api: Api, settings: Settings, video: String, shown: EpisodeDetail?, note: String, next: String, link: Uri) {
        val p = rememberPlayer(video, null, link.getQueryParameter("lang") ?: "ja")
        remember(p) { player = p; p.playWhenReady = true }
        val upcoming by rememberUpdatedState(next)
        var failed by remember { mutableStateOf<String?>(null) } // plain playback had no word of it: it just stood still
        DisposableEffect(p) {
            val ended = object : Player.Listener {
                override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                    failed = tr("The video stopped: %1\$s", e.cause?.message ?: e.message ?: e.errorCodeName)
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state != Player.STATE_ENDED || upcoming.isEmpty()) return
                    val show = link.getQueryParameter("show").orEmpty()
                    startActivity(Intent(this@StreamActivity, StreamActivity::class.java).setData(Uri.parse("kumapie://watch").buildUpon()
                        .appendQueryParameter("lang", link.getQueryParameter("lang") ?: "ja").appendQueryParameter("type", link.getQueryParameter("type"))
                        .appendQueryParameter("id", upcoming).appendQueryParameter("show", show)
                        .appendQueryParameter("title", "$show ${upcoming.substringAfterLast(':')}").build()))
                    finish()
                }
            }
            p.addListener(ended)
            onDispose { p.removeListener(ended) }
        }
        val live = remember(p) { LiveLines(p) { api.discoverSubs(link.getQueryParameter("type").orEmpty(), link.getQueryParameter("id").orEmpty(), watchFile) } }
        LaunchedEffect(live) { live.run() }
        if (shown != null) {
            remember(shown) { live.stop() } // the scenes draw the lines now
            return ScenePlayer(api, settings, shown, onClose = ::finish, player = p)
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

    /**
     * An addon's stream without a PC (kumapie://play?url&id&title&show&file): plain, with its Japanese lines live
     * ([LiveLines]: the file's own track, else Jimaku's file for this episode with the user's key). It goes on where
     * it was left on this TV. No words yet: an episode is not built without the PC (kumapie_plan.md "for other people").
     */
    @Composable
    private fun Alone(settings: Settings, link: Uri) {
        val t0 = remember { android.os.SystemClock.elapsedRealtime() }
        val id = link.getQueryParameter("id").orEmpty()
        val resume = remember { settings.prefs.getFloat("alone_pos_$id", 0f).toDouble() }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val p = rememberPlayer(link.getQueryParameter("url").orEmpty(), resume.takeIf { it > 5 }, "ja")
        remember(p) { player = p; p.playWhenReady = true }
        var note by remember { mutableStateOf<String?>(null) }
        DisposableEffect(p) {
            val listener = object : Player.Listener {
                override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                    note = tr("The video stopped: %1\$s", e.cause?.message ?: e.message ?: e.errorCodeName)
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) scope.launch { playNext(settings, link) { note = it } }
                }
            }
            p.addListener(listener)
            onDispose {
                p.removeListener(listener)
                // Left near the end: from the start next time.
                val at = if (p.duration > 0 && p.currentPosition > p.duration - 120_000) 0f else p.currentPosition / 1000f
                io.github.pedrubik2000.kumapie.data.Addons(settings.addons, settings.prefs).played(id, at.toDouble(), (p.duration.coerceAtLeast(0) / 1000).toDouble()) // Home's Continue watching
                alone?.let { settings.prefs.edit().putFloat("alone_off_$id", it.offset.toFloat()).apply() } // after the switch: kept at it
                alone = null; aloneView = null
            }
        }
        val live = remember(p) {
            LiveLines(p) {
                val number = id.substringAfterLast(':').toIntOrNull()
                runCatching {
                    io.github.pedrubik2000.kumapie.data.Jimaku.lines(settings.prefs.getString("jimaku_key", "").orEmpty(),
                        link.getQueryParameter("show").orEmpty(), null, number, link.getQueryParameter("file").orEmpty())
                }.onFailure { note = it.message }.getOrDefault(emptyList()).map { Triple(it.start, it.end, it.text) }
                    // (empty: [LiveLines.noLines] says so once the file turned out to have no Japanese track either)
            }
        }
        remember(live) { alone = live; live.offset = settings.prefs.getFloat("alone_off_$id", 0f).toDouble() }
        LaunchedEffect(live) { live.run() }
        LaunchedEffect(live.noLines) { if (live.noLines && note == null) note = tr("No Japanese subtitles: none in this file, none on Jimaku") }
        // Which lines and how far moved, for a few seconds after it changes (it used to stay on the whole time).
        val label = when {
            live.source != "jimaku" -> null
            live.offset == 0.0 -> tr("Jimaku's subtitles") + " · ▲▼"
            else -> tr("Jimaku's subtitles") + " · " + "%+.1f s".format(live.offset) + (if (live.offset == live.fitted) " · " + tr("fitted") else "") + " · ▲▼"
        }
        var showLabel by remember { mutableStateOf(false) }
        LaunchedEffect(label) { showLabel = label != null; delay(5000); showLabel = false }
        // Jimaku's lines are all there: the episode is made on the TV (words, colours, cards) and the same player goes on
        // in ScenePlayer, after the fit had its chance (its timing goes into the scenes; a later shift doesn't).
        val context = androidx.compose.ui.platform.LocalContext.current
        var shown by remember { mutableStateOf<io.github.pedrubik2000.kumapie.data.EpisodeDetail?>(null) }
        LaunchedEffect(live.lines) {
            if (live.lines.isEmpty()) return@LaunchedEffect
            val tLines = android.os.SystemClock.elapsedRealtime() // "words ready" below: how long each stage took (tvbench)
            // The fit needs the file's own subtitle track (its line starts); a file without one waited the whole 30 s for
            // nothing (Frieren 3 4K, 2026-11-18: words 37 s after OK, 30 of them here). Lines are known after the tracks.
            if (live.canFit) kotlinx.coroutines.withTimeoutOrNull(30_000) { while (live.fitted == null) delay(500) }
            val tFit = android.os.SystemClock.elapsedRealtime()
            val show = link.getQueryParameter("show").orEmpty()
            // English under the lines, from the user's subtitle addon if they added one (OpenSubtitles v3).
            val english = runCatching {
                io.github.pedrubik2000.kumapie.data.Addons(settings.addons, settings.prefs)
                    .english(link.getQueryParameter("type") ?: "series", id, live.lines.map { it.first + live.offset })
            }.getOrDefault(emptyList())
            val tEnglish = android.os.SystemClock.elapsedRealtime()
            // Null while Sudachi's dictionary downloads (the first time): the lines go on meanwhile, then it is made.
            while (shown == null) {
                shown = runCatching {
                    io.github.pedrubik2000.kumapie.lang.EpisodeBuilder.fromLines(context, "alone:$id", show, link.getQueryParameter("title") ?: show,
                        link.getQueryParameter("url").orEmpty(), (p.duration.takeIf { it > 0 } ?: 0L) / 1000.0, live.lines, live.offset, p.currentPosition / 1000.0, english)
                }.getOrElse { note = it.message; return@LaunchedEffect }
                if (shown == null) { note = tr("Words: downloading the Japanese dictionary (once)…"); delay(5_000) }
            }
            val now = android.os.SystemClock.elapsedRealtime()
            android.util.Log.i("kumapie", "words ready: lines ${tLines - t0} ms after open, fit ${tFit - tLines}, english ${tEnglish - tFit}, build ${now - tEnglish} (${live.source}, ${live.lines.size} lines, fit ${if (live.canFit) "${live.fitted} from ${live.refCount} refs" else "no track"})")
        }
        shown?.let { ep ->
            remember(ep) { settings.prefs.edit().putFloat("alone_off_$id", live.offset.toFloat()).apply(); live.stop(); alone = null; note = null } // the scenes draw the lines now; UP / DOWN are theirs
            val backend = remember { io.github.pedrubik2000.kumapie.ui.AloneBackend(context, settings) }
            DisposableEffect(backend) { onDispose { backend.close() } }
            return io.github.pedrubik2000.kumapie.ui.ScenePlayer(backend, settings, ep, onClose = ::finish, player = p)
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView({ PlayerView(it).apply { player = p; useController = true; subtitleView?.visibility = android.view.View.GONE; requestFocus(); aloneView = this } },
                Modifier.fillMaxSize(), onRelease = { it.player = null })
            live.line?.let {
                Text(it, color = Color.White, fontSize = 30.sp, lineHeight = 40.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp).background(Color(0x99000000)).padding(16.dp, 8.dp))
            }
            (note ?: label?.takeIf { showLabel })?.let {
                Text(it, color = Color.White, fontSize = 16.sp, modifier = Modifier.align(Alignment.TopCenter).padding(24.dp)
                    .background(Color(0x99000000)).padding(12.dp, 6.dp))
            }
        }
    }

    /**
     * The end of a no-PC stream: the next episode from the user's addons (id …:N -> …:N+1), the file of the same release
     * group ([Group] at the start of the name) if there is one, else the first this device decodes ([Playable]).
     * This one is marked watched. [say] shows how it goes on screen.
     */
    private suspend fun playNext(settings: Settings, link: Uri, say: (String) -> Unit) {
        val id = link.getQueryParameter("id").orEmpty()
        val type = link.getQueryParameter("type") ?: "series"
        val addons = io.github.pedrubik2000.kumapie.data.Addons(settings.addons, settings.prefs)
        // A movie or a whole title (kitsu:12345) has no next: 12346 is another show.
        if (type == "movie" || id.count { it == ':' } < 2) { runCatching { addons.seen(id, true) }; return }
        val n = id.substringAfterLast(':').toIntOrNull() ?: return
        runCatching { addons.seen(id, true) }
        say(tr("Next: episode %1\$d…", n + 1))
        val (next, f) = runCatching { addons.next(type, id, link.getQueryParameter("file").orEmpty()) }.getOrElse { return say(it.message ?: it.toString()) }
            ?: return say(tr("No files found for episode %1\$d", n + 1))
        val show = link.getQueryParameter("show").orEmpty()
        startActivity(Intent(this, StreamActivity::class.java).setData(Uri.parse("kumapie://play").buildUpon().apply {
            listOf("url" to f.getString("url"), "type" to type, "id" to next, "title" to "$show ${n + 1}",
                "show" to show, "file" to f.optString("file")).forEach { (k, v) -> appendQueryParameter(k, v) }
        }.build()))
        finish()
    }

    /** Where it was, for NuvioTV (MX Player's result: position and duration in ms, end_by). */
    override fun finish() {
        player?.let { p ->
            val pos = p.currentPosition
            val duration = p.duration.takeIf { it > 0 } ?: 0L
            setResult(RESULT_OK, Intent().putExtra("position", pos.toInt()).putExtra("duration", duration.toInt())
                .putExtra("end_by", if (duration > 0 && pos >= duration - 2_000) "playback_completion" else "user"))
        }
        super.finish()
    }

    /** NuvioTV's subtitle files by language (the first of each). */
    private fun subtitles(): Map<String, Uri> {
        @Suppress("DEPRECATION")
        val uris = intent.getParcelableArrayExtra("subs")?.filterIsInstance<Uri>()
            ?: intent.getStringArrayExtra("subs")?.map(Uri::parse).orEmpty() // some launchers (and adb's am) send strings
        val files = intent.getStringArrayExtra("subs.filename").orEmpty()
        val names = intent.getStringArrayExtra("subs.name").orEmpty()
        val out = LinkedHashMap<String, Uri>()
        uris.forEachIndexed { i, uri ->
            val said = (files.getOrNull(i)?.substringBefore('_') ?: names.getOrNull(i).orEmpty()).lowercase()
            CODES.entries.firstOrNull { said in it.value }?.let { out.putIfAbsent(it.key, uri) }
        }
        return out
    }

    // ponytail: UTF-8 only; Stremio's subtitle add-ons serve UTF-8. Detect the charset if a Shift-JIS file shows up.
    private suspend fun read(uri: Uri): String = withContext(Dispatchers.IO) {
        val bytes = if (uri.scheme == "content" || uri.scheme == "file") contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        else URL(uri.toString()).readBytes()
        String(bytes, Charsets.UTF_8)
    }

    private companion object {
        /** The languages kumapie colours, first found first: the spoken one of a stream with several. */
        val SPOKEN = listOf("ja", "de", "en")
        val CODES = mapOf(
            "ja" to setOf("ja", "jp", "jpn", "japanese"), "de" to setOf("de", "ger", "deu", "german"),
            "en" to setOf("en", "eng", "english"), "es" to setOf("es", "spa", "spanish"),
        )
    }
}
