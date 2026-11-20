package io.github.pedrubik2000.kumapie.ui

import androidx.compose.ui.graphics.Color

/** The app's colours. The bear's brown is the accent (focus rings, progress). The TV sets its own (its `KumapieTheme`). */
object Colors {
    var background = Color(0xFF1D1B26)
    var surface = Color(0xFF2A2734)
    var accent = Color(0xFFC98A4B)
    /** The picked word's background; null = the word's own colour (the phone). */
    var picked: Color? = null
    /** The picked word keeps its colour on a soft white box (the phone/tablet Glass look); false = black on [picked]. */
    var pickedSoft = false
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

fun wordColor(state: String?, known: Color = Colors.text): Color = when (state) {
    "u", "l" -> Colors.unknown
    "d" -> Colors.due
    "b" -> Colors.mined
    else -> known
}

/** A Stats heatmap day ([seconds] watched): no time grey, then brighter with the minutes (5, 15, 30, 60). Both apps. */
fun heat(seconds: Double): Color {
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

/** The tapped word in a line or a definition: see [Colors.pickedSoft]. */
fun pickedStyle(color: Color) = if (Colors.pickedSoft) androidx.compose.ui.text.SpanStyle(color = color, background = Color.White.copy(alpha = 0.22f))
    else androidx.compose.ui.text.SpanStyle(color = Color.Black, background = Colors.picked ?: color)
