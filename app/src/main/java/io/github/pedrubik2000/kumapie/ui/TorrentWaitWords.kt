package io.github.pedrubik2000.kumapie.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.EpisodeDetail
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.WaitPick
import io.github.pedrubik2000.kumapie.lang.WaitState
import io.github.pedrubik2000.kumapie.lang.WaitWord
import io.github.pedrubik2000.kumapie.lang.WaitWords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val DEBUG_FILE = "debug_wait_words.txt"

private fun stateOf(code: String?): WaitState? = when (code) {
    "u" -> WaitState.NEW
    "d" -> WaitState.DUE
    "b" -> WaitState.MINED
    else -> null
}

internal fun debugWords(context: Context): WaitWords? {
    if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
    val rows = File(context.cacheDir, DEBUG_FILE).takeIf { it.exists() }?.readLines().orEmpty().mapNotNull { line ->
        val p = line.split('|')
        val state = stateOf(p.getOrNull(3))
        if (state == null) null else WaitWord(p[0], state, reading = p[1], gloss = p[2])
    }
    return rows.takeIf { it.isNotEmpty() }?.let { WaitWords(it, it.count { r -> r.state == WaitState.NEW }, it.count { r -> r.state == WaitState.DUE }) }
}

@Composable
internal fun rememberWaitWords(episode: EpisodeDetail?, settings: Settings, off: Boolean): WaitWords? {
    val context = LocalContext.current
    var words by remember { mutableStateOf(debugWords(context)) }
    LaunchedEffect(episode, off) {
        val ep = episode?.takeUnless { off } ?: return@LaunchedEffect
        val langs = withContext(Dispatchers.Default) { TvMining.languages(context, settings) }
        val named = named(langs)
        val picked = withContext(Dispatchers.Default) { WaitPick.pick(ep, isName = named) }
        words = picked
        words = withContext(Dispatchers.Default) { WaitPick.fill(picked, langs.japanese, TvMining.yomitan) }
    }
    return words
}

private fun named(langs: io.github.pedrubik2000.kumapie.lang.Languages) =
    { w: String -> langs.japanese.autoKnown(w) || (WaitPick.katakanaOnly(w) && runCatching { TvMining.yomitan.query(Lang.JAPANESE, w).isEmpty() }.getOrDefault(false)) }

internal class WaitExtras(val total: io.github.pedrubik2000.kumapie.data.LangTotal?, val number: Int?, val rows: List<WaitWord>)

@Composable
internal fun rememberWaitExtras(info: WaitInfo, settings: Settings, episode: EpisodeDetail?): WaitExtras? {
    val context = LocalContext.current
    var extras by remember { mutableStateOf<WaitExtras?>(null) }
    val last = io.github.pedrubik2000.kumapie.lang.WaitLast
    val file = remember(info.id) { last.file(context.filesDir, info.id) }
    LaunchedEffect(file) {
        extras = withContext(Dispatchers.IO) {
            runCatching {
                val seen = settings.prefs.getStringSet("alone_seen", emptySet()).orEmpty()
                val kept = file.takeIf { it.exists() }?.let { last.decode(it.readText()) }?.takeIf { it.id in seen }
                val known = tvLanguages(context, settings).of(Lang.JAPANESE.code).known
                if (!known.ready) known.refreshDue()
                val p = io.github.pedrubik2000.kumapie.lang.Progress
                val total = p.homeTotal(Lang.JAPANESE.code, p.of(context).merged, emptyList(), known.knownCount()).takeIf { it.total > 0 }
                val still = last.still(kept?.rows.orEmpty(), shown = known::shown)
                android.util.Log.i("kumapie", "wait words: ${still.size} still to learn of ${kept?.rows?.size ?: 0} kept from ${kept?.id} (${file.name})")
                WaitExtras(total, kept?.number, still)
            }.onFailure { android.util.Log.w("kumapie", "wait words: not read", it) }.getOrNull()
        }
    }
    LaunchedEffect(episode) {
        val ep = episode ?: return@LaunchedEffect
        kotlinx.coroutines.delay(KEEP_AFTER_MS)
        withContext(Dispatchers.Default) {
            runCatching {
                val langs = TvMining.languages(context, settings)
                val rows = WaitPick.fill(WaitPick.pick(ep, last.KEEP, named(langs)), langs.japanese, TvMining.yomitan).rows
                file.parentFile?.mkdirs()
                file.writeText(last.encode(info.number, info.id, rows))
                android.util.Log.i("kumapie", "wait words: kept ${rows.size} of ${info.id} (${file.name})")
            }.onFailure { android.util.Log.w("kumapie", "wait words: not kept", it) }
        }
    }
    return extras
}

private const val KEEP_AFTER_MS = 20_000L

@Composable
internal fun WhilePanel(extras: WaitExtras, current: Int?, modifier: Modifier) {
    Column(modifier.background(GLASS, RoundedCornerShape(16.dp)).padding(horizontal = 20.dp, vertical = 18.dp)) {
        Text(tr("WHILE YOU WAIT"), color = Colors.dim, fontSize = 12.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Medium)
        extras.total?.let { t ->
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Stat(hoursMinutes(t.today), tr("Today").lowercase())
                Stat(hoursMinutes(t.week), tr("Last 7 days").lowercase())
                Stat("%,d".format(t.known), tr("Words known").lowercase())
            }
        }
        if (extras.rows.isNotEmpty()) {
            val from = extras.number
            Text(if (from == null || from == current) tr("STILL TO LEARN") else tr("FROM EPISODE %d: STILL TO LEARN", from),
                color = Colors.dim, fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
            extras.rows.forEach { WordRow(it) }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, color = Colors.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = Colors.dim, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
internal fun WordsPanel(number: Int?, words: WaitWords?, noLines: Boolean, modifier: Modifier) {
    Column(modifier.background(GLASS, RoundedCornerShape(16.dp)).padding(horizontal = 18.dp, vertical = 16.dp)) {
        Text(if (number != null) tr("Words you'll meet in episode %d", number) else tr("Words you'll meet in this video"),
            color = Colors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(io.github.pedrubik2000.kumapie.lang.waitSummary(words, noLines), color = Colors.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp, bottom = 8.dp))
        words?.rows?.forEach { WordRow(it) }
    }
}

@Composable
private fun WordRow(w: WaitWord) {
    val color = wordColor(when (w.state) { WaitState.NEW -> "u"; WaitState.DUE -> "d"; WaitState.MINED -> "b" })
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(w.word, color = color, fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.width(92.dp))
        Column(Modifier.weight(1f)) {
            if (w.reading.isNotEmpty()) Text(w.reading, color = Colors.dim, fontSize = 11.sp, maxLines = 1)
            Text(w.gloss, color = Colors.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Pill(w.state, color)
    }
}

@Composable
private fun Pill(state: WaitState, color: Color) {
    val label = when (state) { WaitState.NEW -> tr("new"); WaitState.DUE -> tr("due today"); WaitState.MINED -> tr("mined") }
    Box(Modifier.border(1.dp, color, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(label, color = color, fontSize = 11.sp, maxLines = 1)
    }
}
