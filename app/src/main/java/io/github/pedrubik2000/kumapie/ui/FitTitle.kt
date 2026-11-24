package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

private const val FIT_STEP = 2
private const val FIT_FLOOR_PERCENT = 60

private fun Char.latin() = this.code < 128 && isLetterOrDigit()

internal fun splitsAWord(text: String, lineEnds: List<Int>): Boolean = lineEnds.any { end ->
    end in 1 until text.length && text[end - 1].latin() && text[end].latin()
}

@Composable
internal fun FitTitle(text: String, size: Int, maxLines: Int, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val px = remember(text, width, size, maxLines) {
            val floor = size * FIT_FLOOR_PERCENT / 100
            (size downTo floor step FIT_STEP).firstOrNull { s ->
                val r = measurer.measure(text, titleStyle(s), overflow = TextOverflow.Ellipsis, softWrap = maxLines > 1, maxLines = maxLines, constraints = Constraints(maxWidth = width))
                !r.hasVisualOverflow && !splitsAWord(text, (0 until r.lineCount - 1).map { r.getLineEnd(it, visibleEnd = true) })
            } ?: floor
        }
        Text(text, color = Color.White, style = titleStyle(px), maxLines = maxLines, softWrap = maxLines > 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun titleStyle(px: Int): TextStyle = Arcade.label.copy(fontSize = px.sp, lineHeight = (px * 9 / 8).sp)
