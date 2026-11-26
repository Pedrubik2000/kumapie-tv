package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.data.Subtitles as SubtitleMode
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.player.SceneController
import io.github.pedrubik2000.kumapie.ui.Colors

/** The player's modes, saved on the device; and a reminder of the gestures. */
@Composable
internal fun Options(ctl: SceneController, seekStep: Int, onSeekStep: (Int) -> Unit, onUpright: (() -> Unit)? = null, uprightNow: Boolean = false,
                    onShift: ((Double) -> Unit)? = null, shifted: Double? = null, onReset: (() -> Unit)? = null,
                    onReport: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding()
        .padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OptionRow(tr("Primed Listening: wait before each line (read, then play)"), ctl.pauseAtSceneStart, ctl::togglePauseAtSceneStart)
        OptionRow(tr("Pause after each line"), ctl.pauseAtSceneEnd, ctl::togglePauseAtSceneEnd)
        OptionRow(tr("Wait only on lines with a new or due word"), ctl.playThroughKnown, ctl::togglePlayThroughKnown)
        OptionRow(tr("Lookup only: no word colours"), ctl.plainWords, ctl::togglePlainWords)
        OptionRow(tr("Slow (0.75x)"), ctl.slow, ctl::toggleSlow)
        if (onUpright != null) OptionRow(tr("Upright: line by line, swipe left"), uprightNow, onUpright)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("Back / forward buttons"), modifier = Modifier.weight(1f))
            listOf(5, 10, 30).forEach { s ->
                FilterChip(selected = seekStep == s, onClick = { onSeekStep(s) }, label = { Text("$s s", fontSize = 12.sp) })
            }
        }
        if (onShift != null) {
            Text(tr("Subtitle timing") + (shifted?.takeIf { it != 0.0 }?.let { tr(" (moved %+.1f s so far)").format(java.util.Locale.ROOT, it) } ?: ""),
                color = Colors.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(-1.0, -0.5, -0.1, 0.1, 0.5, 1.0).forEach { s ->
                    FilterChip(selected = false, onClick = { onShift(s) }, label = { Text("%+.1f s".format(java.util.Locale.ROOT, s), fontSize = 12.sp) })
                }
            }
            Text(tr("− shows the subtitles earlier, + later. Every line of the episode moves."), color = Colors.dim, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (onReset != null) FilterChip(selected = false, onClick = onReset, label = { Text(tr("Reset sync"), fontSize = 12.sp) })
                if (onReport != null) FilterChip(selected = false, onClick = onReport, label = { Text(tr("Report subtitle timing"), fontSize = 12.sp) })
            }
        }
        Text(tr("Subtitles"), color = Colors.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        // Scrolls: on a phone "German + English" was squeezed into three lines.
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(SubtitleMode.HIDDEN, SubtitleMode.BLURRED, SubtitleMode.BOTH_BLURRED, SubtitleMode.TARGET, SubtitleMode.BOTH).forEach { m ->
                FilterChip(selected = ctl.subtitles == m, onClick = { ctl.changeSubtitles(m) }, label = { Text(m.label(ctl.lang), fontSize = 12.sp) })
            }
        }
        Text(
            tr("Tap the line number at the top: all lines, to jump to one · tap the video: play / pause · swipe ← →: next / previous line · double-tap: replay the line" +
                " · ⟲ ⟳ in the middle: back / forward (seconds set here) · Skip intro / ending: shown in a long part without lines · tap the subtitles: play / pause (the mode is set here; blurred ones are readable while paused) · " +
                "tap a word: its meaning, then tap the words of the German definition too · " +
                "paused, long-press a subtitle and drag over a word: add it to your personal dictionary."),
            color = Colors.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun OptionRow(label: String, on: Boolean, toggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = { toggle() })
    }
}
