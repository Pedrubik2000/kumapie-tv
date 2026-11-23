package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.BestForYou
import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BUTTON_PADDING = PaddingValues(horizontal = 14.dp, vertical = 10.dp)

@Composable
internal fun PickContent(env: DiscoverEnv, picks: Picks?, ranked: Ranked?, state: PickState) {
    val loggedIn = env.settings.anilistToken.isNotEmpty()
    val m = picks?.current
    LinkWhen(env.link, !loggedIn || m != null)
    AskTitles(env.levels, m?.kitsuId, picks?.let { p -> listOfNotNull(p.current) + p.next }.orEmpty().map { it.kitsuId })
    when {
        !loggedIn -> LoginPrompt(env)
        picks == null -> Text(tr("Loading…"), color = Colors.dim, modifier = Modifier.padding(start = CONTENT_START, top = 100.dp))
        m == null -> Text(tr("Nothing left to pick right now. Add titles to your Planning list on AniList, or open Browse."), color = Colors.dim, modifier = Modifier.padding(start = CONTENT_START, top = 100.dp))
        else -> PickBig(env, m, ranked, state)
    }
}

@Composable
internal fun LoginPrompt(env: DiscoverEnv) =
    LoginLine(Modifier.padding(start = CONTENT_START, top = 100.dp), Modifier.focusRequester(env.link.to).focusProperties { left = env.entry }, env.onSettings)

@Composable
private fun PickBig(env: DiscoverEnv, m: Mine, ranked: Ranked?, state: PickState) {
    val target = remember(m) { ListTarget(m) }
    val cover = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().onBackKey { runCatching { env.entry.requestFocus() } }) {
        HeroBackdrop(heroPicture(target, env.api), { FADE_SHORT }, Modifier.fillMaxWidth().height(460.dp))
        Row(Modifier.fillMaxWidth().padding(start = CONTENT_START, top = 56.dp, end = 40.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PickText(env, m, ranked, state, cover, Modifier.weight(1f))
            Card(onClick = { env.open(m.found(), "cover") }, shape = arcadeCardShape(PosterSlab), border = arcadeCardBorder(PosterSlab), colors = arcadeCardColors(),
                modifier = Modifier.width(140.dp).aspectRatio(2f / 3f).focusRequester(cover).focusProperties { left = env.link.to; up = MissingKeyLine.up(cover) }) {
                Cover(m.cover, m.shown(), Modifier.fillMaxSize(), fontSize = 13.sp, description = m.shown())
            }
        }
    }
}

@Composable
private fun PickText(env: DiscoverEnv, m: Mine, ranked: Ranked?, state: PickState, cover: FocusRequester, modifier: Modifier) {
    val why by produceState(emptyList<String>(), m.anilistId, ranked) {
        value = ranked?.let { r ->
            withContext(Dispatchers.Default) { whyLines(m, BestForYou.why(r.items, m.anilistId), scaleTo10(env.settings.anilistFormat)) }
        }.orEmpty()
    }
    val title = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        when (env.hold.take(DiscoverMode.PICK)) {
            "title" -> title.focusSoon()
            "cover" -> cover.focusSoon()
        }
        env.hold.restoring = false
    }
    Column(modifier) {
        Eyebrow((tr("Pick for me") + " · " + tr("From your Planning list")).uppercase())
        Card(onClick = { env.open(m.found(), "title") }, shape = arcadeCardShape(RowSlab), border = CardDefaults.border(border = Border.None, focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = RowSlab)),
            colors = arcadeCardColors(), scale = CardDefaults.scale(focusedScale = 1f),
            modifier = Modifier.fillMaxWidth().focusRequester(title).focusProperties { left = env.entry; right = cover; down = env.link.to; up = MissingKeyLine.up(title) }) {
            Text(m.shown(), color = Color.White, fontSize = 40.sp, lineHeight = 46.sp, style = Arcade.label, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp))
        }
        Text(pickFacts(m), color = WHITE_DIM, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, start = 8.dp))
        env.levels?.of(m.kitsuId)?.let { Box(Modifier.padding(top = 8.dp, start = 8.dp)) { DifficultyLine(it) } }
        if (why.isNotEmpty()) Why(why)
        PickButtons(env, m, state, title, cover)
    }
}

@Composable
private fun Why(lines: List<String>) {
    Text(tr("Why this one").uppercase(), color = WHITE_DIM, fontSize = 14.sp, letterSpacing = 3.sp, style = Arcade.label, modifier = Modifier.padding(top = 18.dp, start = 8.dp))
    lines.forEach {
        Row(Modifier.padding(top = 6.dp, start = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("✓", color = Arcade.accent, fontSize = 18.sp)
            Text(it, color = Color.White, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun PickButtons(env: DiscoverEnv, m: Mine, state: PickState, title: FocusRequester, cover: FocusRequester) {
    val found = remember(m) { m.found() }
    Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { env.hold.addToHome(env.api, found) },
            padding = BUTTON_PADDING, modifier = Modifier.focusRequester(env.link.to).focusProperties { left = env.entry; up = title; down = FocusRequester.Cancel }) { Text("+ " + tr("Home")) }
        Button(onClick = { state.notForMe(m.anilistId) }, padding = BUTTON_PADDING, modifier = Modifier.focusProperties { down = FocusRequester.Cancel }) { Text("✕ " + tr("Not for me")) }
        Button(onClick = { state.skip(m.anilistId) }, padding = BUTTON_PADDING, modifier = Modifier.focusProperties { right = cover; down = FocusRequester.Cancel }) { Text("» " + tr("Skip")) }
    }
}
