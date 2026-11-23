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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.i18n.tr
import kotlinx.coroutines.delay

private const val COMMIT_AFTER_MS = 1_500L

internal class AniState(val mine: Mine?, val ready: Boolean, val failed: Boolean)

@Composable
internal fun AniListCard(loggedIn: Boolean, state: AniState, format: String, finished: Boolean, next: Int?, focus: TitleFocus,
                         onStatus: (String) -> Unit, onScore: (Double) -> Unit, modifier: Modifier) {
    Column(modifier.width(300.dp).arcadePanel().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("AniList", color = Arcade.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        when {
            !loggedIn -> CardNote(tr("Log in with AniList to see your list here"))
            state.failed -> CardNote(tr("Couldn't read your AniList list"))
            !state.ready -> CardNote(tr("Loading…"))
            else -> CardRows(state.mine, format, finished, next, focus, onStatus, onScore)
        }
    }
}

@Composable
private fun CardNote(text: String) = Text(text, color = WHITE_DIM, fontSize = 14.sp)

@Composable
private fun CardRows(mine: Mine?, format: String, finished: Boolean, next: Int?, focus: TitleFocus,
                     onStatus: (String) -> Unit, onScore: (Double) -> Unit) {
    val status = remember(mine?.anilistId, mine?.status) { Stepper(STATUS_STEPS, statusStart(mine?.status), onStatus) }
    val steps = remember(format) { scoreSteps(format) }
    val score = remember(mine?.anilistId, mine?.score, steps) { Stepper(steps, scoreStart(steps, mine?.score ?: 0.0), onScore) }
    CommitSoon(status)
    CommitSoon(score)
    val listed = status.shown != null
    StepRow(tr("Status"), statusLabel(status.shown), Modifier.focusRequester(focus.status).focusProperties { up = focus.main }, status::step, status::commit)
    if (finished && (mine?.score ?: 0.0) <= 0.0) Text(tr("You finished it. Rate it?"), color = Arcade.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    StepRow(tr("Your score"), scoreLabel(score.shown ?: 0.0, format),
        Modifier.focusRequester(focus.score).focusProperties { canFocus = listed; down = focus.episodes }, score::step, score::commit)
    mine?.let { Progress(it) }
    val total = mine?.episodes
    when {
        status.shown == "PLANNING" || !listed -> next?.let { CardNote(tr("Play Ep %d moves it to Watching on AniList.", it)) }
        status.shown == "CURRENT" && total != null -> CardNote(tr("Finishing Ep %d marks it Completed on AniList.", total))
    }
}

@Composable
private fun Progress(mine: Mine) {
    val total = mine.episodes
    if (total != null) {
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
            Text("${mine.progress}", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("  / " + tr("%d episodes", total), color = WHITE_DIM, fontSize = 16.sp, modifier = Modifier.padding(bottom = 3.dp))
        }
        ProgressBar(mine.progress.toFloat() / total, Modifier.fillMaxWidth())
    } else Text(tr("%d episodes", mine.progress), color = WHITE_DIM, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp))
    if (mine.average > 0) Row(Modifier.fillMaxWidth()) {
        Text(tr("AniList score"), color = WHITE_DIM, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text("${mine.average}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun <T : Any> CommitSoon(stepper: Stepper<T>) {
    LaunchedEffect(stepper, stepper.shown) { delay(COMMIT_AFTER_MS); stepper.commit() }
    DisposableEffect(stepper) { onDispose { stepper.commit() } }
}

private fun stepKey(e: KeyEvent, onStep: (Int) -> Unit): Boolean {
    val by = when (e.key) { Key.DirectionLeft -> -1; Key.DirectionRight -> 1; else -> return false }
    if (e.type == KeyEventType.KeyDown) onStep(by)
    return true
}

@Composable
private fun StepRow(label: String, value: String, modifier: Modifier, onStep: (Int) -> Unit, onLeave: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(modifier
        .onFocusChanged { if (focused && !it.isFocused) onLeave(); focused = it.isFocused }
        .onKeyEvent { stepKey(it, onStep) }
        .semantics(mergeDescendants = true) {}
        .focusable()
        .fillMaxWidth().height(40.dp)
        .background(if (focused) Color.White else Arcade.slab, CircleShape)
        .border(1.dp, if (focused) Color.White else Arcade.line, CircleShape)
        .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = if (focused) Color.Black else WHITE_DIM, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text("‹  $value  ›", color = if (focused) Color.Black else Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
