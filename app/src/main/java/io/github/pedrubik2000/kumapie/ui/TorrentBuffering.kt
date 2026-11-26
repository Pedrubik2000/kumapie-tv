package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.torrent.TorrentStatus
import io.github.pedrubik2000.kumapie.torrent.bufferText
import kotlinx.coroutines.delay

private const val TICK_MS = 250L
private const val MIN_SWEEP = 14f

@Composable
internal fun BufferingOverlay(player: Player, status: TorrentStatus, release: String) {
    val (title, stats) = bufferText(status)
    var position by remember { mutableLongStateOf(player.currentPosition) }
    var duration by remember { mutableLongStateOf(player.duration) }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration
            delay(TICK_MS)
        }
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Black.copy(alpha = 0.75f), Color.Black)))) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Ring(status.bufferPercent / 100f)
            Text(title, color = Colors.text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 48.dp, vertical = 28.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stats, color = Colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Box(Modifier.weight(1f))
                if (release.isNotEmpty()) Text(release, color = Colors.dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(2f))
            }
            Timeline(if (duration > 0) position.toFloat() / duration else 0f, status.chunk)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(clock(position), color = Colors.dim, fontSize = 13.sp)
                Text(if (duration == C.TIME_UNSET) "" else clock(duration), color = Colors.dim, fontSize = 13.sp)
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                KeyCap("BACK")
                Text(tr("Cancel"), color = Colors.dim, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun Ring(fraction: Float) {
    val accent = Colors.accent
    Canvas(Modifier.size(54.dp)) {
        val stroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
        val inset = stroke.width / 2
        val box = Size(size.width - stroke.width, size.height - stroke.width)
        drawArc(Color.White.copy(alpha = 0.18f), 0f, 360f, false, Offset(inset, inset), box, style = stroke)
        drawArc(accent, -90f, maxOf(MIN_SWEEP, fraction * 360f), false, Offset(inset, inset), box, style = stroke)
    }
}

@Composable
private fun Timeline(at: Float, chunk: Float) {
    val accent = Colors.accent
    Canvas(Modifier.fillMaxWidth().height(22.dp).padding(vertical = 6.dp)) {
        val bar = 6.dp.toPx()
        val top = (size.height - bar) / 2
        val round = CornerRadius(bar / 2)
        val from = at.coerceIn(0f, 1f) * size.width
        drawRoundRect(Color.White.copy(alpha = 0.2f), Offset(0f, top), Size(size.width, bar), round)
        drawRoundRect(accent, Offset(from, top), Size(minOf(size.width - from, maxOf(bar, chunk * size.width)), bar), round)
        drawCircle(Color.White, 8.dp.toPx(), Offset(from, size.height / 2))
    }
}

private fun clock(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}
