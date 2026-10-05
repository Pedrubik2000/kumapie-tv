package io.github.pedrubik2000.kumapie.ui

import androidx.compose.ui.graphics.Color

/** The app's colours. The bear's brown is the accent (focus rings, progress). */
object Colors {
    val background = Color(0xFF1D1B26)
    val surface = Color(0xFF2A2734)
    val accent = Color(0xFFC98A4B)
    val text = Color(0xFFF2F0F7)
    val dim = Color(0xFFA9A4B8)
    // Words: white = known (FSRS stability >= 7 days, the server's rule), orange = in Anki but below that,
    // red = never studied (the only ones a scene's level counts). Same colours as the phone feed.
    val learning = Color(0xFFFFAA4D)
    val unknown = Color(0xFFFF6B6B)
    // Level badges: i+0 green, i+1 yellow, i+2 and up red.
    val levelZero = Color(0xFF7CC47F)
    val levelOne = Color(0xFFE6C463)
}
