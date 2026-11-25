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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
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

@Composable
fun KumapieTheme(content: @Composable () -> Unit) {
    // The TV's Glass look (Arcade.kt); core's Colors is shared with the phone, which keeps its own.
    Colors.accent = Arcade.accent
    Colors.background = Arcade.background
    Colors.surface = Arcade.slab
    Colors.picked = androidx.compose.ui.graphics.Color.White // the picked word: black on white
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Colors.accent,
            background = Colors.background,
            surface = Colors.surface,
            surfaceVariant = Arcade.slab, // tv-material cards (rows, episodes, files): glass, not grey
            onSurfaceVariant = Colors.text,
            onSurface = Colors.text,
            onBackground = Colors.text,
            border = androidx.compose.ui.graphics.Color.White,
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun imeVisible() = WindowInsets.isImeVisible

/** A one-line text field that shows a ring when the D-pad is on it (OK opens the TV keyboard). */
@Composable
fun TvTextField(value: String, onValueChange: (String) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier, placeholder: String = "") {
    var focused by remember { mutableStateOf(false) }
    // Read-only until OK: a TV opens its keyboard on any focused editable field, so scrolling Settings past the
    // server field popped the keyboard up and took the D-pad.
    var editing by remember { mutableStateOf(false) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val imeOpen = imeVisible()
    var imeWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(imeOpen) { if (imeOpen) imeWasOpen = true else if (imeWasOpen) { imeWasOpen = false; editing = false } }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        readOnly = !editing,
        singleLine = true,
        textStyle = TextStyle(color = Colors.text, fontSize = 20.sp),
        cursorBrush = SolidColor(Colors.accent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { editing = false; onDone() }, onGo = { editing = false; onDone() }),
        modifier = modifier
            .onFocusChanged { focused = it.isFocused; if (!it.isFocused) editing = false }
            // A one-line field has no use for up/down: let the D-pad leave it (it would trap focus otherwise).
            .onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (it.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> focusManager.moveFocus(FocusDirection.Down)
                    KeyEvent.KEYCODE_DPAD_UP -> focusManager.moveFocus(FocusDirection.Up)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (editing) false else focusManager.moveFocus(FocusDirection.Right)
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (editing) false else focusManager.moveFocus(FocusDirection.Left)
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (editing) false else { editing = true; keyboard?.show(); true }
                    else -> false
                }
            }
            .background(Colors.surface, RoundedCornerShape(8.dp))
            .border(2.dp, if (focused) Colors.accent else Colors.surface, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        decorationBox = { inner ->
            androidx.compose.foundation.layout.Box {
                if (value.isEmpty()) androidx.tv.material3.Text(placeholder, color = Colors.dim, fontSize = 20.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                inner()
            }
        },
    )
}

object DifficultyColours {
    val blue = Color(0xFF3F7FD6)
    val green = Color(0xFF2E9E5B)
    val lime = Color(0xFF6E9A1C)
    val orange = Color(0xFFD9741A)
    val red = Color(0xFFD33B3B)
    val purple = Color(0xFF8A47C9)
}
