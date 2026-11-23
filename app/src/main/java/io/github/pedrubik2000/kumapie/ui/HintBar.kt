package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr

internal enum class HintKind { PLAY, EPISODE, EPISODE_FILES, VALUE, ROW, CHECK, COVER, DUEL, PICK, CHOOSE, NEXT, HERO, SELECT }

internal fun hintOf(kind: HintKind): List<Pair<String, String>> = when (kind) {
    HintKind.PLAY -> listOf("OK" to tr("Play"))
    HintKind.EPISODE -> listOf("OK" to tr("Play"), tr("Hold") + " OK" to tr("Mark watched"))
    HintKind.EPISODE_FILES -> hintOf(HintKind.EPISODE) + ("▼" to tr("Other files"))
    HintKind.VALUE -> listOf("◀▶" to tr("Change"), "▲▼" to tr("Leave"))
    HintKind.ROW -> listOf("OK" to tr("Select"), "▶" to tr("Titles"))
    HintKind.CHECK -> listOf("OK" to tr("On / off"))
    HintKind.COVER -> listOf("OK" to tr("Open"))
    HintKind.DUEL -> listOf("◀▶" to tr("Choose"), "OK" to tr("Keep"))
    HintKind.PICK -> listOf("OK" to tr("Choose"), "▲" to tr("Title"))
    HintKind.CHOOSE -> listOf("OK" to tr("Choose"))
    HintKind.NEXT -> listOf("OK" to tr("Show this one"))
    HintKind.HERO -> listOf("OK" to tr("Choose"), "▼" to tr("Rows"))
    HintKind.SELECT -> listOf("OK" to tr("Select"))
}

internal fun hintText(kind: HintKind): String = hintOf(kind).joinToString(" · ") { "${it.first} ${it.second}" }

internal object Hints {
    var shown by mutableStateOf<HintKind?>(null)
    private var owner: Any? = null

    fun focus(by: Any, kind: HintKind) {
        owner = by
        shown = kind
    }

    fun refresh(by: Any, kind: HintKind) {
        if (owner === by) shown = kind
    }

    fun blur(by: Any) {
        if (owner === by) {
            owner = null
            shown = null
        }
    }

    fun line(): String? = shown?.let(::hintText)
}

@Composable
internal fun Modifier.hint(kind: HintKind): Modifier {
    val by = remember { Any() }
    var focused by remember { mutableStateOf(false) }
    DisposableEffect(by) { onDispose { Hints.blur(by) } }
    LaunchedEffect(kind, focused) { if (focused) Hints.refresh(by, kind) }
    return onFocusChanged {
        focused = it.isFocused
        if (it.isFocused) Hints.focus(by, kind) else Hints.blur(by)
    }
}

internal val HINT_ROOM = 52.dp
internal val HINT_PILL_WIDTH = 380.dp

@Composable
internal fun rememberHintRoom(): androidx.compose.ui.unit.Dp {
    val context = LocalContext.current
    return remember { if (Settings(context).showHelp) HINT_ROOM else 0.dp }
}

@Composable
internal fun BoxScope.HintBar(top: androidx.compose.ui.unit.Dp? = null) {
    val kind = Hints.shown ?: return
    val context = LocalContext.current
    if (!remember { Settings(context).showHelp }) return
    Row((if (top == null) Modifier.align(Alignment.BottomEnd).padding(end = 32.dp, bottom = 10.dp) else Modifier.align(Alignment.TopEnd).padding(end = 32.dp, top = top)).background(Arcade.band.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
        .padding(horizontal = 14.dp, vertical = 6.dp).testTag("hints"), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        hintOf(kind).forEach { (keys, word) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                KeyCap(keys, WHITE_DIM)
                Text(word, color = Colors.dim, fontSize = 14.sp)
            }
        }
    }
}
