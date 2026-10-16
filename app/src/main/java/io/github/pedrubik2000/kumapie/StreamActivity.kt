package io.github.pedrubik2000.kumapie

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
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

    @Composable
    private fun Stream(settings: Settings, video: String) {
        val api = remember { Api(settings.server, settings.profile) }
        var detail by remember { mutableStateOf<EpisodeDetail?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var step by remember { mutableStateOf("") }
        val link = Uri.parse(video)
        val title = intent.getStringExtra("title") ?: link.lastPathSegment.orEmpty()
        LaunchedEffect(Unit) {
            runCatching {
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
            when {
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
