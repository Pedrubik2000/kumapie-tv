package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.mobile.local.Gemma
import io.github.pedrubik2000.kumapie.mobile.local.ProcessWorker
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.mobile.lang.JapaneseModel
import io.github.pedrubik2000.kumapie.mobile.local.englishSource
import io.github.pedrubik2000.kumapie.mobile.local.jimakuKey
import io.github.pedrubik2000.kumapie.mobile.local.rdToken
import io.github.pedrubik2000.kumapie.mobile.local.sonioxKey
import io.github.pedrubik2000.kumapie.mobile.local.transcriber
import io.github.pedrubik2000.kumapie.mobile.local.videoHeight
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.ui.Colors

/**
 * "Add an episode": a YouTube link, a magnet or Real-Debrid link (or one shared to kumapie), or video files on the
 * tablet become episodes here (download, Soniox, subtitles, English, scenes; ProcessWorker), under a show name (blank:
 * the channel / the file name's). Below: every job's progress.
 */
@Composable
fun AddEpisodeSheet(library: Library, sharedLink: String?) {
    val context = LocalContext.current
    var link by remember { mutableStateOf(sharedLink?.let { Regex("""(https?://|magnet:)\S+""").find(it)?.value } ?: "") }
    var show by remember { mutableStateOf("") }
    var height by remember { mutableStateOf(library.settings.videoHeight) }
    var lang by remember { mutableStateOf(Lang.GERMAN) }
    val japanese = lang == Lang.JAPANESE
    val jobs by remember { ProcessWorker.states(context) }.collectAsState(initial = emptyList())
    val parakeet = !japanese && library.settings.transcriber == "parakeet"
    val gemma = library.settings.englishSource == "gemma"
    val gemmaReady = !gemma || Gemma(context).isReady
    val needsRd = link.isNotBlank() && !ProcessWorker.isYouTube(link)
    // Picked files: kumapie keeps the right to read them, as the job may run after the app is closed.
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            ProcessWorker.start(context, uri.toString(), show.takeIf { it.isNotBlank() }, height, lang)
        }
    }
    val ready = gemmaReady && when {
        japanese -> JapaneseModel(context).isReady && library.settings.jimakuKey.isNotBlank()
        parakeet -> library.known.model.isReady && io.github.pedrubik2000.kumapie.mobile.local.Parakeet(context).isReady
        else -> library.known.model.isReady && library.settings.sonioxKey.isNotBlank()
    }

    // Scrolls: in landscape the sheet is taller than the screen (the button ended up under the navigation bar).
    Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 24.dp)
        .navigationBarsPadding().padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Add an episode", color = Colors.accent, fontSize = 18.sp)
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Lang.GERMAN, Lang.JAPANESE).forEach { l ->
                androidx.compose.material3.FilterChip(selected = lang == l, label = { Text(l.name) }, onClick = { lang = l })
            }
        }
        OutlinedTextField(link, { link = it }, label = { Text("YouTube, magnet or Real-Debrid link") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(show, { show = it }, label = { Text("Show (blank: the channel's / file's name)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Text("Video quality", fontSize = 14.sp, color = Colors.dim)
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(360, 480, 720, 1080).forEach { h ->
                androidx.compose.material3.FilterChip(selected = height == h, label = { Text("${h}p") },
                    onClick = { height = h; library.settings.videoHeight = h })
            }
        }
        Text("For YouTube. Higher takes more space (roughly 5 / 8 / 15 / 30 MB a minute); YouTube has H.264 up to 1080p.",
            fontSize = 12.sp, color = Colors.dim)
        if (!gemmaReady) Text("First: Settings > download the translation model (Gemma), or pick another English source.",
            color = Colors.unknown, fontSize = 14.sp)
        else if (!ready && japanese) Text("First: Settings > the Japanese words dictionary, and your Jimaku API key.",
            color = Colors.unknown, fontSize = 14.sp)
        else if (!ready) Text(if (parakeet) "First: Settings > the German model and the speech model (Parakeet)."
            else "First: Settings > the German model, and your Soniox key.", color = Colors.unknown, fontSize = 14.sp)
        if (needsRd && library.settings.rdToken.isBlank()) Text("For magnets and Real-Debrid links: Settings > your Real-Debrid token.",
            color = Colors.unknown, fontSize = 14.sp)
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = ready && (link.startsWith("http") || link.startsWith("magnet:")) && !(needsRd && library.settings.rdToken.isBlank()),
                onClick = {
                    ProcessWorker.start(context, link, show.takeIf { it.isNotBlank() }, height, lang)
                    link = ""
                }) { Text("Make it an episode") }
            androidx.compose.material3.OutlinedButton(enabled = ready, onClick = { pick.launch(arrayOf("video/*")) }) { Text("Video files…") }
        }
        val noEnglish = library.settings.englishSource == "none"
        if (japanese) Text("Download → Japanese subtitles from Jimaku, fitted to the audio → " + when {
                noEnglish -> "no English (Settings)"
                gemma -> "English (Gemma on the tablet, about 3 s a line)"
                else -> "English (the device's translator)"
            } + " → scenes. Show blank: its " +
            "AniList name. MKV files keep their Japanese audio. Jobs run one after another in the background.",
            color = Colors.dim, fontSize = 13.sp)
        else Text("Download → " + (if (parakeet) "Parakeet on the tablet (free)" else "Soniox (paid, about \$0.10 an hour)") +
            " → German subtitles → " + when {
                noEnglish -> "no English (Settings)"
                gemma -> "English (Gemma on the tablet, about 3 s a line)"
                library.settings.englishSource == "soniox" && !parakeet -> "English (Soniox)"
                else -> "English (the device's translator)"
            } + " → scenes. A season (magnet) becomes one episode per file; MKV files keep their German audio. Jobs run " +
            "one after another in the background; each episode appears on the home screen when done.", color = Colors.dim, fontSize = 13.sp)
        jobs.forEach { Text(it, fontSize = 14.sp) }
    }
}
