package io.github.pedrubik2000.kumapie

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = Settings(this)
        val video = intent.data?.toString()
        if (video == null || settings.server.isEmpty()) {
            Toast.makeText(this, tr("Open kumapie once and set the PC's address first"), Toast.LENGTH_LONG).show()
            return finish()
        }
        Tr.use(settings.speaks)
        setContent { KumapieTheme { Stream(settings, video) } }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        io.github.pedrubik2000.kumapie.ui.Pad.seen(event)
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
        LaunchedEffect(Unit) {
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
            }.onSuccess { detail = it }.onFailure { error = it.message ?: it.toString() }
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
            if (r.has("url")) video(r.getString("url"))
            when (r.optString("state")) {
                "ready" -> {
                    val d = api.episode(r.getString("id"))
                    val at = withContext(Dispatchers.Main) { player?.currentPosition ?: 0L } / 1000.0
                    return d.copy(video = r.getString("url"), show = query["title"].orEmpty(), resume = if (at > 1) at else d.resume)
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
     * then the same player goes on in [ScenePlayer]. At the video's end: the next episode's StreamActivity.
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
        if (shown != null) return ScenePlayer(api, settings, shown, onClose = ::finish, player = p)
        Box(Modifier.fillMaxSize()) {
            AndroidView({ PlayerView(it).apply { player = p; useController = true; requestFocus() } }, Modifier.fillMaxSize(),
                onRelease = { it.player = null })
            Text(failed ?: note, color = Color.White, fontSize = 16.sp, modifier = Modifier.align(Alignment.TopCenter).padding(24.dp)
                .background(Color(0x99000000)).padding(12.dp, 6.dp))
        }
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
