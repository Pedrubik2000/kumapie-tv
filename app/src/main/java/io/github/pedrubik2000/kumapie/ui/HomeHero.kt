package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.Backdrops
import io.github.pedrubik2000.kumapie.data.Discovery
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.data.resumeLine
import io.github.pedrubik2000.kumapie.i18n.tr
import kotlinx.coroutines.delay

class Spot(val at: Int, val index: Int, val key: Any)

@Stable
class HomeMemory {
    var last: Spot? = null
}

@Stable
class HomeFocus(first: HeroTarget?, val memory: HomeMemory = HomeMemory()) {
    private val cards = HashMap<String, FocusRequester>()
    val rows = HashMap<Int, LazyListState>()
    var target by mutableStateOf(first)
    var row by mutableIntStateOf(0)
    val tall: Boolean get() = target.let { it == null || it is ResumeTarget }
    var back: FocusRequester? = null
    private val starts = HashMap<Any, FocusRequester>()

    fun start(key: Any): Modifier {
        val r = starts.getOrPut(key) { FocusRequester() }
        return Modifier.focusRequester(r).onFocusChanged { if (it.isFocused) back = r }
    }

    fun card(at: Int, key: Any): Modifier {
        val id = "$at:$key"
        return Modifier.focusRequester(cards.getOrPut(id) { FocusRequester() })
            .onFocusChanged { if (it.isFocused && memory.last?.let { m -> "${m.at}:${m.key}" } != id) memory.last = null }
    }

    fun leaving(at: Int, index: Int, key: Any) { memory.last = Spot(at, index, key) }

    suspend fun restore(list: LazyListState): Boolean {
        val s = memory.last ?: return false
        repeat(RESTORE_FRAMES) {
            if (memory.last !== s) return true
            runCatching { list.scrollToItem(s.at) }
            runCatching { rows[s.at]?.scrollToItem(s.index) }
            withFrameNanos { }
            if (cards["${s.at}:${s.key}"]?.let { runCatching { it.requestFocus() }.getOrDefault(false) } == true) {
                if (memory.last === s) memory.last = null
                return true
            }
        }
        if (memory.last === s) memory.last = null
        runCatching { list.scrollToItem(0) }
        runCatching { rows[s.at]?.scrollToItem(0) }
        return false
    }

    fun settled(): Modifier = Modifier.onFocusChanged { if (it.isFocused) memory.last = null }

    fun forget(): Modifier = Modifier.onFocusChanged { if (it.isFocused) back = null }

    var toTop: (() -> Unit)? = null

    fun returnFromRail(): Boolean {
        val had = back != null
        if (back?.requestFocus(FocusDirection.Enter) == true) return true
        back = null
        if (!had) return false
        toTop?.invoke()
        return true
    }
}

private const val RESTORE_FRAMES = 30
internal const val LOGIN_START = "login"

private val HERO_HEIGHT = 390.dp
private const val PICTURE_DEBOUNCE_MS = 700L
private val FADE_TALL = Brush.verticalGradient(0.5f to Color.Transparent, 1f to Arcade.background)
internal val FADE_SHORT = Brush.verticalGradient(0.4f to Color.Transparent, 0.85f to Arcade.background)
internal val SCRIM_LEFT = Brush.horizontalGradient(0f to Arcade.background, 0.24f to Arcade.background.copy(alpha = 0.93f), 0.5f to Arcade.background.copy(alpha = 0.66f),
    0.8f to Arcade.background.copy(alpha = 0.22f), 1f to Arcade.background.copy(alpha = 0.1f))
internal val SCRIM_BOTTOM = Brush.verticalGradient(0.45f to Color.Transparent, 0.75f to Arcade.background.copy(alpha = 0.6f), 1f to Arcade.background)
internal val SCRIM_TOP = Brush.verticalGradient(0f to Arcade.background.copy(alpha = 0.85f), 0.22f to Color.Transparent)
internal val WHITE_DIM = Color.White.copy(alpha = 0.85f)

@Composable
internal fun HomeHero(focus: HomeFocus, warm: Pair<String, String>, api: Discovery, levels: Levels?, first: FocusRequester, below: FocusRequester,
                      emptyHome: Boolean, hasBelow: Boolean, onPlay: (Show, Episode) -> Unit, onShow: (Show) -> Unit, onDiscover: () -> Unit) {
    val picture = heroPicture(focus.target, api)
    Box(Modifier.fillMaxWidth().height(HERO_HEIGHT)) {
        GlyphWarm(warm.first, warm.second, Modifier.matchParentSize())
        HeroBackdrop(picture, { if (focus.tall) FADE_TALL else FADE_SHORT }, Modifier.matchParentSize())
        when (val t = focus.target) {
            is ResumeTarget -> ResumeText(t, focus, first, below, onPlay, onShow)
            is ContinueTarget -> InfoText(t, t.facts(), emptyList(), levels)
            is ListTarget -> InfoText(t, t.facts(), t.chips(), levels)
            is FoundTarget -> Unit
            null -> Row(Modifier.padding(start = RAIL, top = 96.dp, end = 32.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Find something to watch in Discover: what you play shows up here."), color = Colors.dim, modifier = Modifier.weight(1f, fill = false))
                Button(onClick = onDiscover, modifier = (if (emptyHome) Modifier.focusRequester(first) else Modifier).then(focus.forget()).then(focus.settled())
                    .focusProperties { if (emptyHome) down = if (hasBelow) below else FocusRequester.Cancel }) { Text(tr("Find something to watch")) }
            }
        }
    }
}

@Composable
internal fun HeroBackdrop(picture: String, fade: () -> Brush, modifier: Modifier) =
    Box(modifier.drawWithContent {
        drawContent()
        drawRect(SCRIM_LEFT)
        drawRect(SCRIM_BOTTOM)
        drawRect(SCRIM_TOP)
        drawRect(fade())
    }) {
        Cover(picture, "", Modifier.matchParentSize(), showTitle = false, alignment = Alignment.TopCenter, description = null, hero = true)
    }

@Composable
internal fun heroPicture(target: HeroTarget?, api: Discovery): String {
    val context = LocalContext.current
    var picture by remember { mutableStateOf(target?.let { Backdrops.saved(context, it.key) ?: it.fallback() }.orEmpty()) }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(target?.key) {
        val t = target ?: return@LaunchedEffect
        if (settled) delay(PICTURE_DEBOUNCE_MS)
        settled = true
        picture = Backdrops.saved(context, t.key) ?: t.fallback()
        if (t.kitsu() != null) Backdrops.get(context, api, t.type(), t.key)?.let { picture = it }
        if (t is ResumeTarget) Backdrops.putHero(context, picture)
    }
    return picture
}

@Composable
internal fun Eyebrow(text: String) =
    Text(text, color = WHITE_DIM, fontSize = 14.sp, letterSpacing = 3.sp, style = Arcade.label)

@Composable
private fun ResumeText(t: ResumeTarget, focus: HomeFocus, first: FocusRequester, below: FocusRequester, onPlay: (Show, Episode) -> Unit, onShow: (Show) -> Unit) {
    val e = t.episode
    Column(Modifier.padding(start = RAIL, top = 72.dp, end = 24.dp).width(600.dp)) {
        Eyebrow(t.eyebrow())
        FitTitle(t.title(), 52, 1)
        Text(factLine(e.title, resumeLine(e), episodesText(t.mine?.episodes ?: t.show.episodes.size)), color = WHITE_DIM, fontSize = 18.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        if (e.duration > 0) ProgressBar(((e.resume ?: 0.0) / e.duration).toFloat(), Modifier.width(380.dp).padding(top = 12.dp))
        Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onPlay(t.show, e) }, modifier = Modifier.hint(HintKind.HERO).focusRequester(first).then(focus.settled()).focusProperties { down = below }) { Text("▶  " + tr("Resume")) }
            Button(onClick = { onShow(t.show) }, modifier = Modifier.hint(HintKind.HERO).focusProperties { down = below }) { Text(tr("Episodes")) }
        }
    }
}

@Composable
internal fun InfoText(t: HeroTarget, facts: String, chips: List<String>, levels: Levels?) {
    Column(Modifier.padding(start = RAIL, top = 56.dp, end = 24.dp).width(900.dp)) {
        Eyebrow(t.eyebrow())
        FitTitle(t.title(), 44, 1)
        Text(facts, color = WHITE_DIM, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (t is ContinueTarget) episodeStrip(t.episode.number, leftText(t.episode), t.mine?.episodes ?: t.show.episodes.size.takeIf { it > 1 })
                .forEach { Chip(it.text, it.current) }
            chips.forEach { Chip(it, false) }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            levels?.of(t.kitsu())?.let { DifficultyLine(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                KeyCap("OK", WHITE_DIM)
                Text(tr("Open the title page"), color = WHITE_DIM, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun Chip(text: String, current: Boolean) {
    val edge = if (current) Arcade.accent else Arcade.line
    Text(text, color = if (current) Arcade.accent else Color.White, fontSize = 15.sp, style = Arcade.label, maxLines = 1,
        modifier = Modifier.background(Arcade.slab, RoundedCornerShape(14.dp)).border(1.dp, edge, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 6.dp))
}

@Composable
internal fun DifficultyLine(d: io.github.pedrubik2000.kumapie.lang.TitleDifficulty) =
    Text(buildAnnotatedString {
        append(tr("Difficulty") + " ")
        withStyle(SpanStyle(color = difficultyColour(d), fontWeight = FontWeight.Bold)) { append("${d.percent}%") }
        append(" · " + d.label)
    }, color = WHITE_DIM, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
