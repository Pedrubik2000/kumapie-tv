package io.github.pedrubik2000.kumapie.ui

import io.github.pedrubik2000.kumapie.i18n.tr
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Stats
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * Watch time and what was done: today / 7 days / total / streak, a year heatmap, the last 30 days as bars,
 * scenes, lookups and words marked known. Each section can be focused, so ↑↓ scroll through them.
 * A study day starts at 4 am (as in Anki); the server counts the days.
 */
@Composable
fun StatsScreen(api: Api, onSettings: () -> Unit) {
    var stats by remember { mutableStateOf<Stats?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        runCatching { api.stats() }.onSuccess { stats = it }.onFailure { error = it.message ?: it.toString() }
    }
    when {
        error != null -> ErrorBox(error!!, onRetry = { attempt++ }, onSettings = onSettings)
        stats == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(tr("Loading…"), color = Colors.dim) }
        else -> StatsContent(stats!!)
    }
}

@Composable
private fun StatsContent(s: Stats) {
    val first = remember { FocusRequester() }
    val today = LocalDateTime.now().minusHours(4).toLocalDate() // the study day, as the server counts it
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 56.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Section(Modifier.focusRequester(first)) {
                Text(tr("Stats"), fontSize = 30.sp, color = Colors.text)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Tile(tr("Today"), minutes(s.today))
                    Tile(tr("Last 7 days"), minutes(s.week))
                    Tile(tr("Total"), minutes(s.total))
                    Tile(tr("Streak"), if (s.streak == 1) tr("1 day") else tr("%d days", s.streak))
                }
            }
        }
        item {
            Section {
                Text(tr("The last year"), fontSize = 20.sp, color = Colors.accent)
                Heatmap(s.days, today, Modifier.padding(top = 10.dp))
                Text(tr("A square is a day: the more minutes, the brighter. Weeks start on Monday."), fontSize = 13.sp,
                    color = Colors.dim, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item {
            Section {
                Text(tr("The last 30 days"), fontSize = 20.sp, color = Colors.accent)
                Bars(s.days, today, Modifier.padding(top = 10.dp))
            }
        }
        item {
            Section {
                Text(tr("Scenes and words"), fontSize = 20.sp, color = Colors.accent)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Tile(tr("Scenes watched to the end"), tr("%1\$d of %2\$d", s.scenesSeen, s.scenesTotal))
                    Tile(tr("Words looked up"), "${s.wordsLookedUp} (${s.lookups}×)")
                    Tile(tr("Marked known"), "${s.markedKnown}")
                }
                if (s.shows.isNotEmpty()) {
                    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        s.shows.forEach { sh ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(sh.title, color = Colors.text, fontSize = 16.sp, modifier = Modifier.width(360.dp))
                                ProgressBar(if (sh.scenes > 0) sh.seen.toFloat() / sh.scenes else 0f, Modifier.width(300.dp))
                                Text("  ${sh.seen} / ${sh.scenes} scenes", color = Colors.dim, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
        if (s.topLookups.isNotEmpty()) {
            item {
                Section {
                    Text(tr("Looked up most"), fontSize = 20.sp, color = Colors.accent)
                    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        s.topLookups.forEach { t ->
                            Row {
                                Text("${t.times}×", color = Colors.learning, fontSize = 16.sp, modifier = Modifier.width(56.dp))
                                Text(t.meaning.ifBlank { t.word }, color = Colors.text, fontSize = 16.sp)
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/** A focusable block, so the D-pad can scroll the page; a thin ring shows where you are. */
@Composable
private fun Section(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .border(2.dp, if (focused) Colors.accent.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) { content() }
}

@Composable
private fun Tile(label: String, value: String) {
    Column(Modifier.background(Colors.surface, RoundedCornerShape(12.dp)).padding(horizontal = 20.dp, vertical = 14.dp)) {
        Text(value, fontSize = 26.sp, color = Colors.text)
        Text(label, fontSize = 14.sp, color = Colors.dim)
    }
}

/** 53 weeks × 7 days, Monday on top, today in the last column. */
@Composable
private fun Heatmap(days: Map<String, Double>, today: LocalDate, modifier: Modifier = Modifier) {
    val start = today.minusWeeks(52).with(DayOfWeek.MONDAY)
    val months = remember(start) {
        (0..52).mapNotNull { w ->
            val d = start.plusWeeks(w.toLong())
            if (d.dayOfMonth <= 7) w to d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) else null
        }
    }
    val cell = 12.dp
    val gap = 3.dp
    Column(modifier) {
        Box(Modifier.width((cell + gap) * 53).height(18.dp)) {
            months.forEach { (w, name) ->
                Text(name, fontSize = 11.sp, color = Colors.dim, modifier = Modifier.padding(start = (cell + gap) * w))
            }
        }
        Canvas(Modifier.width((cell + gap) * 53).height((cell + gap) * 7)) {
            val c = cell.toPx()
            val g = gap.toPx()
            for (w in 0..52) for (d in 0..6) {
                val day = start.plusDays((w * 7 + d).toLong())
                if (day.isAfter(today)) continue
                val secs = days[day.toString()] ?: 0.0
                drawRoundRect(heat(secs), Offset(w * (c + g), d * (c + g)), Size(c, c), CornerRadius(2.dp.toPx()))
            }
        }
    }
}

/** No time: grey; then brighter with the minutes (5, 15, 30, 60). */
private fun heat(seconds: Double): Color {
    val m = seconds / 60
    return when {
        m < 1 -> Colors.surface
        m < 5 -> Colors.accent.copy(alpha = 0.3f)
        m < 15 -> Colors.accent.copy(alpha = 0.5f)
        m < 30 -> Colors.accent.copy(alpha = 0.7f)
        m < 60 -> Colors.accent.copy(alpha = 0.85f)
        else -> Colors.accent
    }
}

/** Minutes per day for the last 30 days, with the longest day's minutes on top. */
@Composable
private fun Bars(days: Map<String, Double>, today: LocalDate, modifier: Modifier = Modifier) {
    val values = (29 downTo 0).map { (days[today.minusDays(it.toLong()).toString()] ?: 0.0) / 60 }
    val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    Column(modifier) {
        Text("most: ${minutes(max * 60)}", fontSize = 12.sp, color = Colors.dim)
        Canvas(Modifier.width(840.dp).height(120.dp)) {
            val w = size.width / 30
            values.forEachIndexed { i, v ->
                val h = (v / max * size.height).toFloat()
                drawRoundRect(if (i == 29) Colors.learning else Colors.accent,
                    Offset(i * w + w * 0.15f, size.height - h), Size(w * 0.7f, h.coerceAtLeast(if (v > 0) 2f else 0f)),
                    CornerRadius(3.dp.toPx()))
            }
        }
        Row(Modifier.width(840.dp)) {
            Text(today.minusDays(29).let { "${it.dayOfMonth} ${it.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}" },
                fontSize = 12.sp, color = Colors.dim)
            Box(Modifier.weight(1f))
            Text("today", fontSize = 12.sp, color = Colors.learning)
        }
    }
}

private fun minutes(seconds: Double): String {
    val m = (seconds / 60).toInt()
    return when {
        m < 60 -> "$m min"
        m % 60 == 0 -> "${m / 60} h"
        else -> "${m / 60} h ${m % 60} min"
    }
}
