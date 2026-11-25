package io.github.pedrubik2000.kumapie.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import io.github.pedrubik2000.kumapie.i18n.tr

object ColourSample {
    fun line(coloured: Boolean): AnnotatedString = buildAnnotatedString {
        fun part(text: String, colour: Color?) =
            if (coloured && colour != null) withStyle(SpanStyle(color = colour)) { append(text) } else append(text)
        part("「この魔法学校に", null)
        part("入学", Colors.mined)
        part("するには、\n", null)
        part("試験", Colors.unknown)
        part("に", null)
        part("合格", Colors.due)
        part("する必要がある」", null)
    }

    fun legend(): List<Pair<Color, String>> = listOf(
        Colors.unknown to tr("New"),
        Colors.mined to tr("Mined, not reviewed yet"),
        Colors.due to tr("Due"),
        Colors.text to tr("Known: plain text"),
    )
}
