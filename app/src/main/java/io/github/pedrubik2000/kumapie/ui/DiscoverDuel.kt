package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.BestForYou
import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.TitleDifficulty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DuelContent(env: DiscoverEnv, field: List<Mine>?, ranked: Ranked?, state: PickState, onHomeIds: Set<String>) {
    val loggedIn = env.settings.anilistToken.isNotEmpty()
    val playable = loggedIn && field != null && field.size >= 2
    LinkWhen(env.link, !loggedIn || playable)
    AskTitles(env.levels, null, field.orEmpty().map { it.kitsuId })
    when {
        !loggedIn -> LoginPrompt(env)
        field == null || ranked == null -> Text(tr("Loading…"), color = Colors.dim, modifier = Modifier.padding(start = CONTENT_START, top = 100.dp))
        !playable -> Text(tr("Not enough titles on your Planning list to choose between"), color = Colors.dim, modifier = Modifier.padding(start = CONTENT_START, top = 100.dp))
        else -> Bracket(env, field.take(DUEL_SIZE), ranked, state, onHomeIds)
    }
}

@Composable
private fun Bracket(env: DiscoverEnv, field: List<Mine>, ranked: Ranked, state: PickState, onHomeIds: Set<String>) {
    val fieldIds = remember(field) { field.map { it.anilistId } }
    val saved = state.duel
    var game by remember(if (saved != null) null else fieldIds) { mutableStateOf(saved ?: Knockout(fieldIds)) }
    val byId = remember(ranked) { ranked.list.associateBy { it.anilistId } }
    val reasons by produceState(emptyMap<Int, String>(), game.entrants, ranked) {
        val tenth = scaleTo10(env.settings.anilistFormat)
        value = withContext(Dispatchers.Default) {
            game.entrants.mapNotNull { byId[it] }.mapNotNull { m -> whyLines(m, BestForYou.why(ranked.items, m.anilistId), tenth).firstOrNull()?.let { m.anilistId to it } }.toMap()
        }
    }
    val scope = rememberCoroutineScope()
    val pair = game.pair
    val winner = game.winner?.let { byId[it] }
    Box(Modifier.fillMaxSize().onBackKey { runCatching { env.entry.requestFocus() } }) {
        Column(Modifier.padding(start = CONTENT_START, top = 40.dp, end = 28.dp)) {
            if (pair != null) Duel(env, game, pair, byId, reasons) { first ->
                val next = game.choose(first)
                game = next
                state.duel = next
                if (next.winner != null) scope.launch { env.link.to.focusSoon() }
            }
            if (winner != null) Winner(env, winner, onHomeIds, state, onAgain = {
                state.duel = null
                game = Knockout(fieldIds)
                scope.launch { env.link.to.focusSoon() }
            })
            Strip(game, byId)
        }
    }
}

@Composable
private fun Duel(env: DiscoverEnv, game: Knockout, pair: Pair<Int, Int>, byId: Map<Int, Mine>, reasons: Map<Int, String>, onChoose: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(tr("Keep one"), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(tr("Round %1\$d of %2\$d", game.round, game.rounds) + " · " + tr("last one standing goes on Home"), color = Colors.dim, fontSize = 18.sp)
    }
    Row(Modifier.padding(top = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(pair.first, pair.second).forEachIndexed { i, id ->
            val m = byId[id] ?: return@forEachIndexed
            DuelCard(m, env.levels?.of(m.kitsuId), reasons[id],
                (if (i == 0) Modifier.focusRequester(env.link.to).focusProperties { left = env.entry; up = MissingKeyLine.up(env.link.to) } else Modifier).weight(1f)) { onChoose(i == 0) }
            if (i == 0) Box(Modifier.size(44.dp).background(Arcade.band, CircleShape), contentAlignment = Alignment.Center) {
                Text(tr("OR"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun DuelCard(m: Mine, level: TitleDifficulty?, reason: String?, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, shape = arcadeCardShape(RowSlab), border = arcadeCardBorder(RowSlab), colors = arcadeCardColors(), modifier = modifier.height(360.dp).semantics { contentDescription = m.shown() }) {
        Column(Modifier.fillMaxSize().background(Arcade.slab)) {
            Cover(m.banner.ifEmpty { m.cover }, "", Modifier.fillMaxWidth().height(150.dp), showTitle = false, alignment = Alignment.TopCenter, description = null)
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Cover(m.cover, "", Modifier.width(70.dp).height(105.dp).clip(PosterSlab), showTitle = false, description = null)
                Column {
                    Text(m.shown(), color = Color.White, fontSize = 24.sp, style = Arcade.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(duelFacts(m), color = WHITE_DIM, fontSize = 16.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                    level?.let { Box(Modifier.padding(top = 6.dp)) { DifficultyLine(it) } }
                }
            }
            reason?.let { Text("✓  $it", color = Color.White, fontSize = 17.sp, modifier = Modifier.padding(start = 12.dp, end = 12.dp)) }
        }
    }
}

@Composable
private fun Winner(env: DiscoverEnv, m: Mine, onHomeIds: Set<String>, state: PickState, onAgain: () -> Unit) {
    val scope = rememberCoroutineScope()
    val found = remember(m) { m.found() }
    val added = found.id in onHomeIds
    val openButton = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (env.hold.take(DiscoverMode.DUEL) == "open") openButton.focusSoon()
        env.hold.restoring = false
    }
    Text(tr("Last one standing"), color = WHITE_DIM, fontSize = 14.sp, letterSpacing = 3.sp, style = Arcade.label)
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Cover(m.cover, m.shown(), Modifier.width(120.dp).height(180.dp).clip(PosterSlab), fontSize = 13.sp, description = null)
        Column {
            Text(m.shown(), color = Color.White, fontSize = 40.sp, lineHeight = 46.sp, style = Arcade.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(pickFacts(m), color = WHITE_DIM, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            env.levels?.of(m.kitsuId)?.let { Box(Modifier.padding(top = 8.dp)) { DifficultyLine(it) } }
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { if (!added) scope.launch { runCatching { env.api.save(found, true) }.onSuccess { state.onHome(found.id, fromWinner = true) } } },
                    modifier = Modifier.focusRequester(env.link.to).focusProperties { left = env.entry; up = MissingKeyLine.up(env.link.to) }) {
                    Text(if (added) "✓ " + tr("On Home") else "+ " + tr("Home"))
                }
                Button(onClick = { env.open(found, "open") }, modifier = Modifier.focusRequester(openButton)) { Text(tr("Open")) }
                Button(onClick = onAgain) { Text(tr("Again")) }
            }
        }
    }
}

@Composable
private fun Strip(game: Knockout, byId: Map<Int, Mine>) {
    Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("%1\$d titles · %2\$d left", game.entrants.size, game.left), color = Colors.dim, fontSize = 16.sp)
        val dropped = game.dropped
        game.entrants.forEach { id ->
            val m = byId[id] ?: return@forEach
            Cover(m.cover, "", Modifier.size(36.dp).clip(CircleShape).alpha(if (id in dropped) 0.3f else 1f), showTitle = false, description = null)
        }
    }
}
