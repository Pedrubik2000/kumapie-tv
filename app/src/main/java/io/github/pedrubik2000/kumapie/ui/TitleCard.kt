package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.AniListSync
import io.github.pedrubik2000.kumapie.i18n.tr
import kotlinx.coroutines.delay

private const val COMMIT_AFTER_MS = 1_500L

internal enum class AniPhase { LOADING, FAILED, LOGGED_OUT, READY }

internal class CardData(val status: String?, val score: Double, val progress: Int, val episodes: Int?, val average: Int)

internal class AniState(val phase: AniPhase, val data: CardData?, val format: String, val mine: io.github.pedrubik2000.kumapie.data.Mine?, val busy: Boolean = false, val retry: () -> Unit = {})

@Composable
internal fun AniListCard(state: AniState, edits: TitleEdits, finished: Boolean, next: Int?, focus: TitleFocus, modifier: Modifier,
                         sync: AniListSync.SyncState = AniListSync.SyncState.NONE) {
    Column(modifier.width(300.dp).arcadePanel().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("AniList", color = Arcade.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        val data = state.data
        when {
            state.phase == AniPhase.LOGGED_OUT -> CardNote(tr("Log in with AniList to see your list here"))
            state.phase == AniPhase.FAILED -> {
                CardNote(tr("Couldn't read your AniList list"))
                Button(onClick = { if (!state.busy) { focus.retried = true; state.retry() } },
                    modifier = Modifier.onFocusChanged { if (it.isFocused) focus.retried = true }.focusRequester(focus.status).focusProperties { up = focus.main; down = focus.row }) {
                    Text(if (state.busy) tr("Trying…") else tr("Try again"))
                }
            }
            data == null -> CardNote(tr("Loading…"))
            state.phase == AniPhase.LOADING -> {
                RowFace(tr("Status"), statusLabel(data.status), false, false, Modifier)
                RowFace(tr("Your score"), scoreLabel(data.score, state.format), false, false, Modifier)
                Progress(data)
            }
            else -> {
                CardRows(data, state.format, edits, finished, next, focus)
                when (sync) {
                    AniListSync.SyncState.SAVED -> CardNote(tr("Saved to AniList"))
                    AniListSync.SyncState.WAITING -> CardNote(tr("Will send when online"))
                    AniListSync.SyncState.NONE -> Unit
                }
            }
        }
    }
}

@Composable
private fun CardNote(text: String) = Text(text, color = WHITE_DIM, fontSize = 14.sp)

@Composable
private fun CardRows(data: CardData, format: String, edits: TitleEdits, finished: Boolean, next: Int?, focus: TitleFocus) {
    val status = edits.status
    val score = edits.score
    CommitSoon(status)
    CommitSoon(score)
    val listed = status.shown != null
    val current = if (edits.scoreOn) score.shown ?: 0.0 else data.score
    StepRow(tr("Status"), statusLabel(status.shown), Modifier.focusRequester(focus.status).focusProperties { up = focus.main; down = if (listed && edits.scoreOn) FocusRequester.Default else focus.row }, status::step, status::commit)
    if (finished && current <= 0.0) Text(tr("You finished it. Rate it?"), color = Arcade.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    StepRow(tr("Your score"), scoreLabel(current, format),
        Modifier.focusRequester(focus.score).focusProperties { canFocus = listed && edits.scoreOn; down = focus.row }, score::step, score::commit)
    Progress(data)
    when {
        status.shown == "PLANNING" || !listed -> next?.let { CardNote(tr("Play Ep %d moves it to Watching on AniList.", it)) }
        status.shown == "CURRENT" && data.episodes != null -> CardNote(tr("Finishing Ep %d marks it Completed on AniList.", data.episodes))
    }
}

@Composable
private fun Progress(data: CardData) {
    val total = data.episodes
    if (total != null) {
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
            Text("${data.progress}", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("  / " + tr("%d episodes", total), color = WHITE_DIM, fontSize = 16.sp, modifier = Modifier.padding(bottom = 3.dp))
        }
        ProgressBar(data.progress.toFloat() / total, Modifier.fillMaxWidth())
    } else Text(tr("%d episodes", data.progress), color = WHITE_DIM, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp))
    if (data.average > 0) Row(Modifier.fillMaxWidth()) {
        Text(tr("AniList score"), color = WHITE_DIM, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text("${data.average}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun <T : Any> CommitSoon(stepper: Stepper<T>) {
    LaunchedEffect(stepper, stepper.shown) { delay(COMMIT_AFTER_MS); stepper.commit() }
}

private fun openKey(e: KeyEvent, onOpen: () -> Unit): Boolean {
    if (e.key != Key.DirectionCenter && e.key != Key.Enter && e.key != Key.NumPadEnter) return false
    if (e.type == KeyEventType.KeyDown) onOpen()
    return true
}

private fun stepKey(e: KeyEvent, onStep: (Int) -> Unit): Boolean {
    val by = when (e.key) { Key.DirectionLeft -> -1; Key.DirectionRight -> 1; else -> return false }
    if (e.type == KeyEventType.KeyDown) onStep(by)
    return true
}

@Composable
internal fun StepRow(label: String, value: String, modifier: Modifier, onStep: (Int) -> Unit, onLeave: () -> Unit, flat: Boolean = false, onOpen: (() -> Unit)? = null) {
    var focused by remember { mutableStateOf(false) }
    RowFace(label, value, focused, flat, open = onOpen != null, modifier = modifier.hint(if (onOpen != null) HintKind.ROW else HintKind.VALUE)
        .onFocusChanged { if (focused && !it.isFocused) onLeave(); focused = it.isFocused }
        .onKeyEvent { if (onOpen != null) openKey(it, onOpen) else stepKey(it, onStep) }
        .semantics(mergeDescendants = true) {}
        .focusable())
}

@Composable
private fun RowFace(label: String, value: String, focused: Boolean, flat: Boolean, modifier: Modifier, open: Boolean = false) {
    Row(modifier.fillMaxWidth().height(40.dp)
        .background(if (focused) Color.White else if (flat) Color.Transparent else Arcade.slab, CircleShape)
        .border(1.dp, if (focused) Color.White else if (flat) Color.Transparent else Arcade.line, CircleShape)
        .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = if (focused) Color.Black else WHITE_DIM, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(if (open) "$value  ›" else "‹  $value  ›", color = if (focused) Color.Black else Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun CommitOnExit(edits: TitleEdits, ready: Boolean) {
    val readyNow by rememberUpdatedState(ready)
    DisposableEffect(edits) { onDispose { if (readyNow) edits.commit() } }
}
