package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr

internal enum class HintKind { PLAY, EPISODE, EPISODE_FILES, VALUE, ROW, CHECK, COVER, DUEL, PICK, CHOOSE, NEXT, HERO, SELECT, RAIL, RAIL_HERE, TICK, CONTINUE }

internal val HintKind.isRail: Boolean get() = this == HintKind.RAIL || this == HintKind.RAIL_HERE

internal fun hintOf(kind: HintKind): List<Pair<String, String>> = when (kind) {
    HintKind.PLAY -> listOf("OK" to tr("Play"))
    HintKind.EPISODE -> listOf("OK" to tr("Play"), tr("Hold") + " OK" to tr("Mark watched"))
    HintKind.EPISODE_FILES -> hintOf(HintKind.EPISODE) + ("▼" to tr("Other files"))
    HintKind.VALUE -> listOf("◀▶" to tr("Change"), "▲▼" to tr("Leave"))
    HintKind.ROW -> listOf("OK" to tr("Select"), "▶" to tr("Titles"))
    HintKind.CHECK -> listOf("OK" to tr("On / off"))
    HintKind.COVER -> listOf("OK" to tr("Open"))
    HintKind.CONTINUE -> listOf("OK" to tr("Open"), tr("Hold") + " OK" to tr("More"))
    HintKind.DUEL -> listOf("◀▶" to tr("Choose"), "OK" to tr("Keep"))
    HintKind.PICK -> listOf("OK" to tr("Choose"), "▲" to tr("Title"))
    HintKind.CHOOSE -> listOf("OK" to tr("Choose"))
    HintKind.NEXT -> listOf("OK" to tr("Show this one"))
    HintKind.HERO -> listOf("OK" to tr("Choose"), "▼" to tr("Rows"))
    HintKind.SELECT -> listOf("OK" to tr("Select"))
    HintKind.TICK -> listOf("OK" to tr("Tick"), "▲▼" to tr("Move"))
    HintKind.RAIL, HintKind.RAIL_HERE -> emptyList()
}

internal fun hintText(kind: HintKind, name: String = ""): String =
    if (kind.isRail) (if (kind == HintKind.RAIL) "OK $name" else name)
    else hintOf(kind).joinToString(" · ") { "${it.first} ${it.second}" }

internal object Hints {
    var shown by mutableStateOf<HintKind?>(null)
    var name by mutableStateOf("")
    private var owner: Any? = null

    fun focus(by: Any, kind: HintKind, name: String = "") {
        owner = by
        shown = kind
        this.name = name
    }

    fun refresh(by: Any, kind: HintKind, name: String = "") {
        if (owner === by) {
            shown = kind
            this.name = name
        }
    }

    fun blur(by: Any) {
        if (owner === by) {
            owner = null
            shown = null
            name = ""
        }
    }

    fun line(): String? = shown?.let { hintText(it, name) }
}

@Composable
internal fun Modifier.hint(kind: HintKind, name: String = ""): Modifier {
    val by = remember { Any() }
    var focused by remember { mutableStateOf(false) }
    DisposableEffect(by) { onDispose { Hints.blur(by) } }
    LaunchedEffect(kind, name, focused) { if (focused) Hints.refresh(by, kind, name) }
    return onFocusChanged {
        focused = it.isFocused
        if (it.isFocused) Hints.focus(by, kind, name) else Hints.blur(by)
    }
}

internal val HINT_ROOM = 52.dp
internal val HINT_PILL_WIDTH = 380.dp
private val RAIL_NAME_WIDTH = 260.dp

@Composable
internal fun rememberHintRoom(): androidx.compose.ui.unit.Dp {
    val context = LocalContext.current
    return remember { if (Settings(context).showHelp) HINT_ROOM else 0.dp }
}

@Composable
internal fun BoxScope.HintBar(top: androidx.compose.ui.unit.Dp? = null) {
    val kind = Hints.shown ?: return
    val context = LocalContext.current
    if (!kind.isRail && !remember { Settings(context).showHelp }) return
    Row((if (top == null) Modifier.align(Alignment.BottomEnd).padding(end = 32.dp, bottom = 10.dp) else Modifier.align(Alignment.TopEnd).padding(end = 32.dp, top = top)).background(Arcade.band.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
        .padding(horizontal = 14.dp, vertical = 6.dp).testTag("hints"), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (kind.isRail) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (kind == HintKind.RAIL) KeyCap("OK", WHITE_DIM)
            Text(Hints.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = RAIL_NAME_WIDTH))
        }
        else hintOf(kind).forEach { (keys, word) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                KeyCap(keys, WHITE_DIM)
                Text(word, color = Colors.dim, fontSize = 14.sp)
            }
        }
    }
}
