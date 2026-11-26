package io.github.pedrubik2000.kumapie.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.data.shortTitle
import io.github.pedrubik2000.kumapie.data.shown
import io.github.pedrubik2000.kumapie.i18n.tr
import kotlinx.coroutines.delay

internal class ContinueMenu(val show: Show, val episode: Episode, val index: Int, val x: Float, val y: Float)

internal class Removed(val show: Show, val episode: Episode, val index: Int)

private val MENU_WIDTH = 340.dp
private const val REMOVED_TOAST_MS = 5_000L

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun ContinueMenuView(m: ContinueMenu, onRemove: () -> Unit, onOpen: () -> Unit, onCancel: () -> Unit) {
    val first = remember { FocusRequester() }
    val density = LocalDensity.current
    val screen = LocalConfiguration.current.screenWidthDp.dp
    val left = with(density) { m.x.toDp() }.coerceIn(8.dp, (screen - MENU_WIDTH - 8.dp).coerceAtLeast(8.dp))
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().swallowHeldOk().onPreviewKeyEvent { e ->
        if (e.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_BACK) false
        else { if (e.type == KeyEventType.KeyUp) onCancel(); true }
    }) {
        Column(Modifier.offset(left, with(density) { m.y.toDp() } + 24.dp).width(MENU_WIDTH)
            .background(Arcade.band.copy(alpha = 0.96f), RoundedCornerShape(14.dp)).padding(8.dp)
            .focusProperties { exit = { FocusRequester.Cancel } }.focusGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = onRemove, modifier = Modifier.focusRequester(first).width(MENU_WIDTH - 16.dp)) {
                Text("✕  " + tr("Remove from Continue watching"), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(onClick = onOpen, modifier = Modifier.width(MENU_WIDTH - 16.dp)) { Text("▶  " + tr("Open title page"), maxLines = 1) }
            Button(onClick = onCancel, modifier = Modifier.width(MENU_WIDTH - 16.dp)) { Text(tr("Cancel"), maxLines = 1) }
        }
    }
}

@Composable
internal fun BoxScope.RemovedToast(r: Removed, onExpire: () -> Unit) {
    LaunchedEffect(r) { delay(REMOVED_TOAST_MS); onExpire() }
    Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp).background(Arcade.band.copy(alpha = 0.95f), RoundedCornerShape(14.dp))
        .padding(horizontal = 18.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("%s removed from Continue watching", shortTitle(r.show.shown)), color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(420.dp))
        KeyCap("OK", WHITE_DIM)
        Text(tr("Undo"), color = Colors.dim, fontSize = 16.sp)
    }
}

internal fun undoKey(e: androidx.compose.ui.input.key.KeyEvent): Boolean =
    e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER

private fun KeyEvent.isEnter() = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

/**
 * For a menu opened by holding OK: the key is still down when the menu takes focus, and its repeats and release would press the focused
 * button (tv-material fires the long press on the first repeat). Swallow OK/Enter until a fresh press (repeatCount 0).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun Modifier.swallowHeldOk(): Modifier {
    val fresh = remember { BooleanArray(1) }
    return onPreviewKeyEvent { e ->
        val k = e.nativeKeyEvent
        if (!k.isEnter()) false
        else {
            if (e.type == KeyEventType.KeyDown && k.repeatCount == 0) fresh[0] = true
            !fresh[0]
        }
    }
}

/**
 * Open a menu when the held OK is RELEASED, not when the long press fires: the card's Surface keeps its "long click" flag until it sees the
 * UP, so a menu opened mid-hold leaves the card ignoring the next OK. [arm] is the card's onLongClick; [key] goes on the card and returns
 * false so the Surface still gets its UP.
 */
internal class OpenOnRelease(private val open: () -> Unit) {
    private var armed = false
    fun arm() { armed = true }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    val key: Modifier = Modifier.onPreviewKeyEvent { e ->
        val k = e.nativeKeyEvent
        if (k.isEnter()) {
            if (e.type == KeyEventType.KeyDown && k.repeatCount == 0) armed = false
            else if (e.type == KeyEventType.KeyUp && armed) { armed = false; open() }
        }
        false
    }
}

@Composable
internal fun rememberOpenOnRelease(open: () -> Unit): OpenOnRelease {
    val latest = androidx.compose.runtime.rememberUpdatedState(open)
    return remember { OpenOnRelease { latest.value() } }
}
