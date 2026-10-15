package io.github.pedrubik2000.kumapie.ui

import androidx.compose.ui.graphics.Color

/** The app's colours. The bear's brown is the accent (focus rings, progress). */
object Colors {
    val background = Color(0xFF1D1B26)
    val surface = Color(0xFF2A2734)
    val accent = Color(0xFFC98A4B)
    val text = Color(0xFFF2F0F7)
    val dim = Color(0xFFA9A4B8)
    // Words in subtitles are only white, red, green or blue (no orange, user): white = known (FSRS stability >= 7
    // days, the server's rule), red = never studied or still learning ("u" and "l", see Subtitles.colored).
    // Orange is not a word colour: it marks counts and "today" in stats and help.
    val learning = Color(0xFFFFAA4D)
    val unknown = Color(0xFFFF6B6B)
    // A word whose own Anki card kuma3 would show today (Word.card "d"): rate it from the word card.
    val due = Color(0xFF7CC47F)
    // Mined and never reviewed (Word.card "b", user 2026-10-11): still unknown for levels, drawn blue.
    val mined = Color(0xFF6FA8FF)
    // Level badges: i+0 green, i+1 yellow, i+2 and up red.
    val levelZero = Color(0xFF7CC47F)
    val levelOne = Color(0xFFE6C463)
}
