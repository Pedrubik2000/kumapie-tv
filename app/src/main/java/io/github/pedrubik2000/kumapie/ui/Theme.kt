package io.github.pedrubik2000.kumapie.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** The app's colours. The bear's brown is the accent (focus rings, progress). */
object Colors {
    val background = Color(0xFF1D1B26)
    val surface = Color(0xFF2A2734)
    val accent = Color(0xFFC98A4B)
    val text = Color(0xFFF2F0F7)
    val dim = Color(0xFFA9A4B8)
    val known = Color(0xFF7CC47F)
    val learning = Color(0xFFE6C463)
    val unknown = Color(0xFFE57373)
}

@Composable
fun KumapieTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Colors.accent,
            background = Colors.background,
            surface = Colors.surface,
            onSurface = Colors.text,
            onBackground = Colors.text,
            border = Colors.accent,
        ),
        content = content,
    )
}

/**
 * Back (remote Back, gamepad B) for this part of the screen. Compose on TV first uses Back to move focus out of
 * the focused group, so a BackHandler only hears the second press; catching the key as it bubbles up from the
 * focused item avoids that. The innermost enabled one wins (a dialog before the screen under it).
 */
fun Modifier.onBackKey(enabled: Boolean = true, onBack: () -> Unit): Modifier = onKeyEvent {
    if (!enabled || it.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_BACK) return@onKeyEvent false
    if (it.type == KeyEventType.KeyUp) onBack()
    true
}

/** A thin bar: how much of an episode was watched. */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(4.dp).background(Colors.surface, RoundedCornerShape(2.dp))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(Colors.accent, RoundedCornerShape(2.dp)))
    }
}

/** A one-line text field that shows a ring when the D-pad is on it (OK opens the TV keyboard). */
@Composable
fun TvTextField(value: String, onValueChange: (String) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Colors.text, fontSize = 20.sp),
        cursorBrush = SolidColor(Colors.accent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .background(Colors.surface, RoundedCornerShape(8.dp))
            .border(2.dp, if (focused) Colors.accent else Colors.surface, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
