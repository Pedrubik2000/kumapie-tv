package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.Word
import io.github.pedrubik2000.kumapie.player.SceneController
import io.github.pedrubik2000.kumapie.player.WordPicker
import kotlin.math.roundToInt

/** The picked word's meaning, dictionary form, status and lookups, in a card right above the word. */
@Composable
fun MeaningCard(ctl: SceneController, picker: WordPicker, anchor: Rect) {
    val segment = picker.selected ?: return
    val key = segment.word ?: return
    val word = ctl.words[key] ?: Word("u", "")
    val meaning = withoutSameWord(ctl.scene.meanings[key] ?: word.meaning, segment.text)
    AboveAnchor(anchor) {
        Column(
            Modifier.widthIn(max = 620.dp).background(Colors.surface, RoundedCornerShape(12.dp))
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (meaning.isNotBlank()) {
                Text(meaning, color = Colors.text, fontSize = 22.sp)
            } else {
                Text(segment.text, color = Colors.text, fontSize = 22.sp)
                Text("no meaning yet", color = Colors.dim, fontSize = 15.sp)
            }
            if (word.lemma.isNotBlank() && word.lemma != key && !word.lemma.equals(segment.text, ignoreCase = true)) {
                Text("dictionary form: ${word.lemma}", color = Colors.dim, fontSize = 15.sp)
            }
            val (status, color) = statusLine(word)
            Text(status + if (word.lookups > 0) "  ·  looked up ${word.lookups}×" else "", color = color, fontSize = 15.sp)
        }
    }
}

/**
 * "echt = really" under a highlighted "echt" says the word twice: show "really". The "word =" part stays when
 * it differs from the selected word (a split verb: "sehen aus = to look" while "sehen" is selected).
 */
private fun withoutSameWord(meaning: String, selected: String): String {
    val head = meaning.substringBefore(" = ", missingDelimiterValue = "")
    return if (head.isNotEmpty() && head.equals(selected, ignoreCase = true)) meaning.substringAfter(" = ") else meaning
}

private fun statusLine(w: Word) = when {
    w.marked -> "marked known" to Colors.dim
    w.status == "k" -> (w.stability?.let { "known · ${days(it)}" } ?: "known") to Colors.dim
    w.status == "l" -> "learning · ${w.stability?.let(::days) ?: "in Anki"}" to Colors.learning
    else -> "never studied" to Colors.unknown
}

private fun days(d: Double) = if (d < 1) "under a day" else "${d.roundToInt()} day${if (d.roundToInt() == 1) "" else "s"}"

/** Places [content] centred above [anchor] (screen coordinates), 10 dp up, kept inside the screen. */
@Composable
private fun AboveAnchor(anchor: Rect, content: @Composable () -> Unit) {
    val gap = with(LocalDensity.current) { 10.dp.roundToPx() }
    val margin = with(LocalDensity.current) { 24.dp.roundToPx() }
    Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val card = measurables.first().measure(Constraints(maxWidth = constraints.maxWidth - 2 * margin))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val x = (anchor.center.x - card.width / 2f).roundToInt()
                .coerceIn(margin, (constraints.maxWidth - margin - card.width).coerceAtLeast(margin))
            val y = (anchor.top - gap - card.height).roundToInt().coerceAtLeast(margin)
            card.place(x, y)
        }
    }
}
