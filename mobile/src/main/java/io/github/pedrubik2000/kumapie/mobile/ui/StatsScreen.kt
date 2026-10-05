package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.data.Stats
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.ui.Colors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * Watch time and what was done, from the PC (TV and phone together): today / 7 days / total / streak, a year
 * heatmap, the last 30 days as bars, scenes, lookups, words marked known. A study day starts at 4 am.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(library: Library, onBack: () -> Unit) {
    var stats by remember { mutableStateOf<Stats?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        runCatching { library.pending.flush(library.api); library.api.stats() }
            .onSuccess { stats = it }.onFailure { error = it.message ?: it.toString() }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Stats") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val s = stats
            when {
                error != null -> Column(Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Stats come from the PC, and it doesn't answer.")
                    Text(error ?: "", color = Colors.dim, fontSize = 13.sp)
                    Button(onClick = { attempt++ }) { Text("Try again") }
                }
                s == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> StatsContent(s)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatsContent(s: Stats) {
    val today = LocalDateTime.now().minusHours(4).toLocalDate()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("Today", minutes(s.today))
                Tile("Last 7 days", minutes(s.week))
                Tile("Total", minutes(s.total))
                Tile("Streak", if (s.streak == 1) "1 day" else "${s.streak} days")
            }
        }
        item {
            Column {
                Heading("The last year")
                // Scrolled to today (the right end) at first.
                val scroll = rememberScrollState(Int.MAX_VALUE)
                Box(Modifier.horizontalScroll(scroll)) { Heatmap(s.days, today, Modifier.padding(top = 8.dp)) }
            }
        }
        item {
            Column {
                Heading("The last 30 days")
                Bars(s.days, today, Modifier.padding(top = 8.dp))
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Heading("Scenes and words")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tile("Scenes to the end", "${s.scenesSeen} / ${s.scenesTotal}")
                    Tile("Words looked up", "${s.wordsLookedUp} (${s.lookups}×)")
                    Tile("Marked known", "${s.markedKnown}")
                }
                s.shows.forEach { sh ->
                    Column {
                        Row {
                            Text(sh.title, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text("${sh.seen} / ${sh.scenes}", color = Colors.dim, fontSize = 13.sp)
                        }
                        ProgressBar(if (sh.scenes > 0) sh.seen.toFloat() / sh.scenes else 0f, Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                }
            }
        }
        if (s.topLookups.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Heading("Looked up most")
                s.topLookups.forEach { t ->
                    Row {
                        Text("${t.times}×", color = Colors.learning, fontSize = 14.sp, modifier = Modifier.width(44.dp))
                        Text(t.meaning.ifBlank { t.word }, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String) = Text(text, color = Colors.accent, fontSize = 17.sp)

@Composable
private fun Tile(label: String, value: String) {
    Column(Modifier.background(Colors.surface, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(value, fontSize = 20.sp)
        Text(label, fontSize = 12.sp, color = Colors.dim)
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
    val cell = 11.dp
    val gap = 3.dp
    Column(modifier) {
        Box(Modifier.width((cell + gap) * 53).height(16.dp)) {
            months.forEach { (w, name) ->
                Text(name, fontSize = 10.sp, color = Colors.dim, modifier = Modifier.padding(start = (cell + gap) * w))
            }
        }
        Canvas(Modifier.width((cell + gap) * 53).height((cell + gap) * 7)) {
            val c = cell.toPx()
            val g = gap.toPx()
            for (w in 0..52) for (d in 0..6) {
                val day = start.plusDays((w * 7 + d).toLong())
                if (day.isAfter(today)) continue
                drawRoundRect(heat(days[day.toString()] ?: 0.0), Offset(w * (c + g), d * (c + g)), Size(c, c), CornerRadius(2.dp.toPx()))
            }
        }
    }
}

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

/** Minutes per day for the last 30 days, the longest day's minutes on top. */
@Composable
private fun Bars(days: Map<String, Double>, today: LocalDate, modifier: Modifier = Modifier) {
    val values = (29 downTo 0).map { (days[today.minusDays(it.toLong()).toString()] ?: 0.0) / 60 }
    val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    Column(modifier.fillMaxWidth()) {
        Text("most: ${minutes(max * 60)}", fontSize = 11.sp, color = Colors.dim)
        Canvas(Modifier.fillMaxWidth().height(100.dp)) {
            val w = size.width / 30
            values.forEachIndexed { i, v ->
                val h = (v / max * size.height).toFloat()
                drawRoundRect(if (i == 29) Colors.learning else Colors.accent,
                    Offset(i * w + w * 0.15f, size.height - h), Size(w * 0.7f, h.coerceAtLeast(if (v > 0) 2f else 0f)),
                    CornerRadius(3.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(today.minusDays(29).let { "${it.dayOfMonth} ${it.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}" },
                fontSize = 11.sp, color = Colors.dim)
            Box(Modifier.weight(1f))
            Text("today", fontSize = 11.sp, color = Colors.learning)
        }
    }
}
