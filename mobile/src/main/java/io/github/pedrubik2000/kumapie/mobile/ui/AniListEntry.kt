package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.data.AniList
import io.github.pedrubik2000.kumapie.data.AniListSync
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.ui.Colors
import kotlinx.coroutines.launch

val aniListLogins = mutableIntStateOf(0)

fun statusName(status: String?) = when (status) {
    "CURRENT" -> tr("Currently watching"); "PLANNING" -> tr("Planning"); "COMPLETED" -> tr("Completed")
    "REPEATING" -> tr("Rewatching"); "PAUSED" -> tr("Paused"); "DROPPED" -> tr("Dropped"); else -> tr("Not on your list")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AniListPill(settings: Settings, media: Int?, titleId: String? = null) {
    val login by aniListLogins
    var entry by remember { mutableStateOf<AniList.Entry?>(null) }
    var editing by remember { mutableStateOf(false) }
    val sync = remember { AniListSync(settings.prefs) }
    // Through the sync: the episode just watched may still be waiting to be sent, and it counts.
    LaunchedEffect(media, login) {
        entry = null
        if (media != null) entry = runCatching { settings.withAniList { t -> titleId?.let { sync.read(t, it).entry } ?: AniList.entry(t, media) } }.getOrNull()
    }
    val e = entry ?: return
    val format = settings.anilistFormat
    val label = "AniList · " + statusName(e.status) +
        (if (e.status != null) " · ${e.progress}" + (e.episodes?.let { "/$it" } ?: "") else "") +
        (if (e.score > 0) " · ★ " + AniList.showScore(e.score, format) else "")
    Pill(label, { editing = true })
    if (editing) ModalBottomSheet(properties = DarkSheet, onDismissRequest = { editing = false }, containerColor = Colors.surface) {
        EntryEditor(settings, media!!, e) { saved -> saved?.let { entry = it }; editing = false }
    }
}

@Composable
fun AniListLogin(settings: Settings) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val login by aniListLogins
    var token by remember(login) { mutableStateOf(settings.anilistToken) }
    if (token.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(tr("✓ AniList: %s", settings.anilist), modifier = Modifier.weight(1f))
        androidx.compose.material3.OutlinedButton(onClick = { settings.logOutAniList(); token = "" }) { Text(tr("Log out")) }
    } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (settings.anilistLoggedOut) tr("AniList stopped accepting your login (it lasts a year): log in again.")
            else tr("Optional: log in with AniList to rate each anime and keep your list up to date as you watch."),
            color = if (settings.anilistLoggedOut) Colors.accent else Colors.dim, fontSize = 14.sp)
        androidx.compose.material3.OutlinedButton(onClick = {
            runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(AniList.loginUrl()))) }
        }) { Text(tr("Log in with AniList")) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntryEditor(settings: Settings, media: Int, e: AniList.Entry, title: String? = null, onDone: (AniList.Entry?) -> Unit) {
    val format = settings.anilistFormat
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(e.status ?: "CURRENT") }
    var score by remember { mutableStateOf(if (e.score > 0) AniList.showScore(e.score, format) else "") }
    var progress by remember { mutableStateOf(e.progress.toString()) }
    var repeat by remember { mutableStateOf(e.repeat.toString()) }
    var started by remember { mutableStateOf(e.started) }
    var completed by remember { mutableStateOf(e.completed) }
    var said by remember { mutableStateOf<String?>(null) }
    val today = java.time.LocalDate.now().toString()
    val date = Regex("""\d{4}-\d{2}-\d{2}""")
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title ?: "AniList", fontSize = 20.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AniList.STATUSES.forEach { s ->
                FilterChip(status == s, {
                    status = s
                    if (s == "COMPLETED") { e.episodes?.let { progress = it.toString() }; if (completed.isEmpty()) completed = today }
                }, label = { Text(statusName(s)) })
            }
        }
        val number = KeyboardOptions(keyboardType = KeyboardType.Number)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(score, { score = it }, label = { Text(tr("Score (0-%d)", AniList.maxScore(format))) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = if (format == "POINT_10_DECIMAL") KeyboardType.Decimal else KeyboardType.Number),
                modifier = Modifier.weight(1f))
            OutlinedTextField(progress, { progress = it }, label = { Text(tr("Episodes") + (e.episodes?.let { " / $it" } ?: "")) },
                singleLine = true, keyboardOptions = number, modifier = Modifier.weight(1f))
            OutlinedTextField(repeat, { repeat = it }, label = { Text(tr("Rewatches")) }, singleLine = true, keyboardOptions = number,
                modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(started, { started = it }, label = { Text(tr("Started on")) }, placeholder = { Text("2026-11-20") },
                singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(completed, { completed = it }, label = { Text(tr("Finished on")) }, placeholder = { Text("2026-11-20") },
                singleLine = true, modifier = Modifier.weight(1f))
        }
        said?.let { Text(it, color = Colors.dim, fontSize = 14.sp) }
        Button(onClick = {
            val sc = AniList.parseScore(score, format)
            val p = progress.trim().ifEmpty { "0" }.toIntOrNull()?.takeIf { it >= 0 }
            val r = repeat.trim().ifEmpty { "0" }.toIntOrNull()?.takeIf { it >= 0 }
            said = when {
                sc == null -> tr("Score: a number from 0 to %d", AniList.maxScore(format))
                p == null || r == null -> tr("Episodes and rewatches: whole numbers")
                listOf(started, completed).any { it.isNotBlank() && !date.matches(it.trim()) } -> tr("Dates: year-month-day, e.g. %s", today)
                else -> null
            }
            if (said != null) return@Button
            said = tr("Saving…")
            val next = AniList.Entry(status, sc!!, p!!, r!!, started.trim(), completed.trim(), e.episodes)
            scope.launch {
                runCatching { settings.withAniList { AniList.save(it, media, next) } }
                    .onSuccess { onDone(it) }.onFailure { said = it.message ?: it.toString() }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(tr("Save")) }
    }
}
