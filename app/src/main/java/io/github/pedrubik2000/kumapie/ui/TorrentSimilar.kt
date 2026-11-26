package io.github.pedrubik2000.kumapie.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.AniList
import io.github.pedrubik2000.kumapie.data.AniListBrowse
import io.github.pedrubik2000.kumapie.data.AniListLists
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.SimilarPick
import io.github.pedrubik2000.kumapie.data.SimilarPicks
import io.github.pedrubik2000.kumapie.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val ADVANCE_S = 10
private const val DEBUG_SEARCH = "debug_wait_similar.txt"

@Stable
class SimilarState(private val settings: Settings, private val scope: kotlinx.coroutines.CoroutineScope) {
    val picks = mutableStateListOf<SimilarPick>()
    var index by mutableIntStateOf(0)
    var left by mutableIntStateOf(ADVANCE_S)
    val added = mutableStateListOf<Int>()
    var failed by mutableStateOf(false)
    val button = FocusRequester()
    var root: FocusRequester? = null
    val canAdd: Boolean get() = settings.anilistToken.isNotEmpty()
    val current: SimilarPick? get() = picks.getOrNull(index)

    fun next() {
        if (picks.size < 2) return
        index = (index + 1) % picks.size
        left = ADVANCE_S
        failed = false
    }

    fun listed(p: SimilarPick): String = if (p.id in added) "PLANNING" else p.listed

    fun add() {
        val p = current ?: return
        scope.launch {
            failed = !runCatching { settings.withAniList { token -> SimilarPicks.addToPlanning(p.id) { id, e -> AniList.save(token, id, e) } } }.isSuccess
            if (!failed) added += p.id
        }
    }
}

@Composable
internal fun rememberSimilar(settings: Settings, info: WaitInfo, enabled: Boolean): SimilarState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { SimilarState(settings, scope) }
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        val found = withContext(Dispatchers.IO) { runCatching { load(context, settings, info) }.getOrDefault(emptyList()) }
        state.picks.clear()
        state.picks.addAll(found)
    }
    LaunchedEffect(state.index, state.picks.size) {
        state.left = ADVANCE_S
        while (state.picks.size > 1) {
            delay(1000)
            if (--state.left <= 0) state.next()
        }
    }
    return state
}

private suspend fun load(context: Context, settings: Settings, info: WaitInfo): List<SimilarPick> {
    val prefs = settings.prefs
    val token = settings.anilistToken
    val debug = File(context.cacheDir, DEBUG_SEARCH).takeIf { context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 && it.exists() }?.readText()?.trim()
    val id = if (debug == null) AniList.idOfKitsu(info.id, prefs) else null
    val query = when {
        debug != null -> AniListBrowse.Query(search = debug)
        id != null -> AniListBrowse.Query(idIn = listOf(id))
        else -> AniListBrowse.Query(search = info.show)
    }
    val me = AniListBrowse.page(query, 1, token, prefs).items.firstOrNull() ?: return emptyList()
    val candidates = AniListBrowse.page(SimilarPicks.query(me), 1, token, prefs).items
    val mine = AniListLists.cached(prefs, settings.anilist).associate { it.anilistId to it.status }
    return SimilarPicks.pick(me, candidates, mine)
}

internal fun androidx.compose.ui.input.key.KeyEvent.pressedNext(): Boolean = type == KeyEventType.KeyDown &&
    key in setOf(Key.DirectionRight, Key.MediaNext, Key.MediaPlay, Key.MediaPlayPause, Key.MediaFastForward)

@Composable
internal fun SimilarPanel(state: SimilarState, info: WaitInfo, modifier: Modifier) {
    val pick = state.current
    Column(modifier.background(Arcade.slab, RoundedCornerShape(16.dp)).border(1.dp, Arcade.line, RoundedCornerShape(16.dp)).padding(horizontal = 18.dp, vertical = 16.dp)) {
        if (pick == null) {
            Text(tr("More like this"), color = Colors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            return@Column
        }
        Text(tr("If you like %s…", info.show), color = Colors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(tr("A pick from AniList while it loads"), color = Colors.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp, bottom = 12.dp))
        PickBody(pick, state)
        Spacer(Modifier.weight(1f))
        PickFooter(state)
    }
}

@Composable
private fun PickBody(pick: SimilarPick, state: SimilarState) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        coil3.compose.AsyncImage(model = pick.cover, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.width(104.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Colors.surface))
        Column(Modifier.weight(1f)) {
            Text(pick.native.ifEmpty { pick.romaji }, color = Colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (pick.native.isNotEmpty()) Text(pick.romaji, color = Colors.dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(SimilarPicks.stats(pick), color = Colors.text, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
            pick.reasons.forEach { Text("✓  $it", color = Colors.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp)) }
        }
    }
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        pick.genres.forEach {
            Text(it, color = Colors.dim, fontSize = 12.sp, maxLines = 1, modifier = Modifier.border(1.dp, Arcade.line, RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 2.dp))
        }
    }
    PlanButton(pick, state)
}

@Composable
private fun PlanButton(pick: SimilarPick, state: SimilarState) {
    val listed = state.listed(pick)
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            listed.isNotEmpty() -> Text("✓ " + tr("On your list · %s", statusLabel(listed)), color = Colors.due, fontSize = 14.sp,
                modifier = Modifier.border(1.dp, Colors.due, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp))
            state.canAdd -> {
                Button(onClick = state::add, modifier = Modifier.focusRequester(state.button).onPreviewKeyEvent {
                    val up = it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp
                    if (up) runCatching { state.root?.requestFocus() }
                    up
                }) { Text("＋ " + tr("Plan to watch")) }
                KeyCap("OK")
                if (state.failed) Text(tr("Couldn't add it"), color = Colors.unknown, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun PickFooter(state: SimilarState) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        state.picks.indices.forEach { i ->
            Box(Modifier.height(6.dp).width(if (i == state.index) 22.dp else 6.dp).background(if (i == state.index) Colors.text else Colors.text.copy(alpha = 0.3f), RoundedCornerShape(50)))
        }
        if (state.picks.size > 1) Text(tr("next in %d s", state.left), color = Colors.dim, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
    }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            KeyCap("▶")
            Text(tr("next pick"), color = Colors.dim, fontSize = 13.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            KeyCap("BACK")
            Text(tr("cancels the stream"), color = Colors.dim, fontSize = 13.sp)
        }
    }
}
