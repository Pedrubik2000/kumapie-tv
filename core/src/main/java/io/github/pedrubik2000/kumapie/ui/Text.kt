package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.TextUnit

/** The locale [Text] draws in (null: the system's); JapaneseText sets Japanese for a Japanese episode's cards. */
val LocalTextLocale = staticCompositionLocalOf<LocaleList?> { null }

/** Plain text for the shared player parts, so they need neither the TV nor the phone Material library. */
@Composable
internal fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    style: TextStyle = TextStyle(localeList = LocalTextLocale.current),
    onTextLayout: (TextLayoutResult) -> Unit = {},
) = BasicText(text, modifier, style.merge(color = color.takeOrElse { style.color.takeOrElse { Colors.text } }, fontSize = fontSize),
    onTextLayout = onTextLayout)

@Composable
internal fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    style: TextStyle = TextStyle(localeList = LocalTextLocale.current),
) = BasicText(text, modifier, style.merge(color = color.takeOrElse { style.color.takeOrElse { Colors.text } }, fontSize = fontSize))
