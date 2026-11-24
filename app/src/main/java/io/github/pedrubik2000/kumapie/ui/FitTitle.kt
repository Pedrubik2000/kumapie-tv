package io.github.pedrubik2000.kumapie.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

private const val FIT_STEP = 2
private const val FIT_FLOOR_PERCENT = 60

private fun TextLayoutResult.splitsAWord(text: String): Boolean = (0 until lineCount - 1).any { i ->
    val end = getLineEnd(i, visibleEnd = true)
    end in 1 until text.length && !text[end - 1].isWhitespace() && !text[end].isWhitespace()
}

@Composable
internal fun FitTitle(text: String, size: Int, maxLines: Int, modifier: Modifier = Modifier) {
    var step by remember(text) { mutableIntStateOf(0) }
    val px = (size - step * FIT_STEP).coerceAtLeast(size * FIT_FLOOR_PERCENT / 100)
    Text(text, color = Color.White, fontSize = px.sp, lineHeight = (px * 9 / 8).sp, style = Arcade.label, maxLines = maxLines, softWrap = maxLines > 1,
        overflow = TextOverflow.Ellipsis, modifier = modifier, onTextLayout = {
            if ((it.hasVisualOverflow || it.splitsAWord(text)) && size - (step + 1) * FIT_STEP >= size * FIT_FLOOR_PERCENT / 100) step++
        })
}
