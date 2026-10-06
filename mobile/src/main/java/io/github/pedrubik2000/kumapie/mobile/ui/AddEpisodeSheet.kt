package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import io.github.pedrubik2000.kumapie.mobile.local.ProcessWorker
import io.github.pedrubik2000.kumapie.mobile.local.sonioxKey
import io.github.pedrubik2000.kumapie.mobile.local.videoHeight
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.ui.Colors

/**
 * "Add an episode": a YouTube link (or one shared to kumapie) becomes an episode on this tablet (download, Soniox,
 * subtitles, English, scenes; ProcessWorker), under a show name (blank: the channel). Below: every job's progress.
 */
@Composable
fun AddEpisodeSheet(library: Library, sharedLink: String?) {
    val context = LocalContext.current
    var link by remember { mutableStateOf(sharedLink?.let { Regex("""https?://\S+""").find(it)?.value } ?: "") }
    var show by remember { mutableStateOf("") }
    var height by remember { mutableStateOf(library.settings.videoHeight) }
    val jobs by remember { ProcessWorker.states(context) }.collectAsState(initial = emptyList())
    val ready = library.settings.sonioxKey.isNotBlank() && library.known.model.isReady

    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Add an episode", color = Colors.accent, fontSize = 18.sp)
        OutlinedTextField(link, { link = it }, label = { Text("YouTube link") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(show, { show = it }, label = { Text("Show (blank: the channel's name)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Text("Video quality", fontSize = 14.sp, color = Colors.dim)
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(360, 480, 720, 1080).forEach { h ->
                androidx.compose.material3.FilterChip(selected = height == h, label = { Text("${h}p") },
                    onClick = { height = h; library.settings.videoHeight = h })
            }
        }
        Text("Higher takes more space (roughly 5 / 8 / 15 / 30 MB a minute); YouTube has H.264 up to 1080p.",
            fontSize = 12.sp, color = Colors.dim)
        if (!ready) Text("First: Settings > the German model, and your Soniox key.", color = Colors.unknown, fontSize = 14.sp)
        Button(enabled = ready && link.startsWith("http"), onClick = {
            ProcessWorker.start(context, link, show.takeIf { it.isNotBlank() }, height)
            link = ""
        }) { Text("Make it an episode") }
        Text("Download → Soniox (paid, about \$0.10 an hour) → German and English subtitles → scenes. It runs in the " +
            "background; the episode appears on the home screen when done.", color = Colors.dim, fontSize = 13.sp)
        jobs.forEach { Text(it, fontSize = 14.sp) }
    }
}
