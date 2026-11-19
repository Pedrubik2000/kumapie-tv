package io.github.pedrubik2000.kumapie.ui

import io.github.pedrubik2000.kumapie.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import io.github.pedrubik2000.kumapie.data.resumeLine
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import io.github.pedrubik2000.kumapie.data.Found
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.Text
import androidx.tv.material3.MaterialTheme
import coil3.compose.AsyncImage
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.data.ahead
import io.github.pedrubik2000.kumapie.data.easyPercent
import io.github.pedrubik2000.kumapie.data.levelLine
import io.github.pedrubik2000.kumapie.data.shortTitle
import io.github.pedrubik2000.kumapie.data.shown

/** Home's rows, in this order (the phone's); reels are feed-only, so the TV never lists them. */
private val KINDS = listOf("anime" to "Anime", "shows" to "Shows", "movies" to "Movies", "youtube" to "YouTube", "visual_novels" to "Visual novels")

/**
 * Asks for the focus until the target is laid out: a lazy list composes its items only when it lays them out, so a
 * plain requestFocus() right after the first composition throws (item 13c).
 */
suspend fun FocusRequester.focusSoon() {
    repeat(10) {
        withFrameNanos { }
        if (runCatching { requestFocus() }.isSuccess) return
    }
}

/**
 * The language switch (only with 2+ languages, saved per profile), then "Continue watching" and one row of posters per
 * media type. OK on a show opens its episodes; on "Continue watching", the player where it was left.
 */
@Composable
fun HomeScreen(api: io.github.pedrubik2000.kumapie.data.Discovery, settings: Settings, profile: String, who: String, onShow: (Show) -> Unit,
               onPlay: (Show, Episode) -> Unit, onSettings: () -> Unit, onStats: () -> Unit, onProfiles: () -> Unit,
               onReview: (String) -> Unit = {}, onDiscover: () -> Unit = {}, onDiscovered: (Found) -> Unit = {}) {
    var shows by remember { mutableStateOf<List<Show>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        runCatching { if (api is Api) api.shows() else emptyList() } // no PC: streamed titles only
            .onSuccess { all -> shows = all.filter { it.kind != "reels" }.map { s -> s.copy(episodes = s.episodes.filter { it.scenes > 0 }) }
                .filter { it.episodes.isNotEmpty() } } // 0 scenes: nothing to play scene by scene
            .onFailure { error = it.message ?: it.toString() }
    }
    val langs = if (api !is Api) listOf("ja") else Lang.ALL.map { it.code }.filter { c -> shows.orEmpty().any { it.lang == c } }
    var picked by remember(profile) { mutableStateOf(settings.prefs.getString("home_lang_$profile", null)) }
    val lang = picked?.takeIf { it in langs } ?: langs.firstOrNull()
    // Discover titles kept on Home (+ HOME on a title's page), to stream later: Japanese only, as Discover.
    var kept by remember { mutableStateOf(emptyList<Found>()) }
    LaunchedEffect(lang) { kept = if (lang == "ja") runCatching { api.saved() }.getOrDefault(emptyList()) else emptyList() }
    // Streamed titles in "Continue watching" too (the episode left partway, or the next one).
    // null while loading: the row waited for them, else it showed without them and reshuffled (focus on a card that moved).
    var streams by remember { mutableStateOf<List<Pair<Show, Episode>>?>(null) }
    // Waits for the shows: before them lang is null, and an empty list here drew Home without the streams.
    LaunchedEffect(lang, shows == null) {
        if (shows == null) return@LaunchedEffect
        streams = null
        streams = if (lang == "ja") runCatching { api.going() }.getOrDefault(emptyList()) else emptyList()
    }

    // Review's count: due and new cards on the PC (the PC refuses a person whose cards aren't there: no button).
    val context = androidx.compose.ui.platform.LocalContext.current
    val due by androidx.compose.runtime.produceState<Int?>(null, lang) { value = lang?.let { runCatching {
        if (api is Api) api.srsDue(it).let { d -> d.due + d.new } else io.github.pedrubik2000.kumapie.lang.ownReview(context, it).due().size }.getOrNull() } } // no PC: this TV's own cards
    // As the tablet's Glass Home (user, 2026-11-17): the bear top left, the language pill top right, round buttons
    // down the left edge over everything, the last episode as a picture across the top.
    val header = @Composable {
        Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 32.dp, top = 20.dp)) {
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(io.github.pedrubik2000.kumapie.R.drawable.bear), "kumapie",
                Modifier.align(Alignment.TopStart).size(64.dp)) // centred over the round buttons ([Rail]: 24 dp in, 56 dp wide)
            if (langs.size > 1) Button(padding = TAB, modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp), onClick = {
                val next = langs[(langs.indexOf(lang) + 1) % langs.size] // one button: OK goes to the next language
                picked = next; settings.prefs.edit().putString("home_lang_$profile", next).apply()
            }) { Text((lang?.let { Lang.of(it).displayName } ?: "") + "  ▾") }
        }
    }
    val railFocus = remember { mutableMapOf<String, FocusRequester>() }
    val toRows = remember { FocusRequester() } // Right from a round button: the rows scroll under them, so the beam search found nothing
    val rail = listOfNotNull(
        RailItem(androidx.compose.material.icons.Icons.Default.Home, tr("Home"), selected = true) {},
        RailItem(androidx.compose.material.icons.Icons.Default.Explore, tr("Discover"), onClick = onDiscover).takeIf { lang == "ja" },
        due?.let { n -> RailItem(androidx.compose.material.icons.Icons.Default.Style, tr("Review"), badge = n) { lang?.let(onReview) } },
        RailItem(androidx.compose.material.icons.Icons.Default.BarChart, tr("Stats"), onClick = onStats).takeIf { api is Api },
        RailItem(androidx.compose.material.icons.Icons.Default.Person, who, back = false, onClick = onProfiles).takeIf { api is Api }, // back to "Who's watching?"
        RailItem(androidx.compose.material.icons.Icons.Default.Settings, tr("Settings"), onClick = onSettings),
    )
    Box(Modifier.fillMaxSize()) {
        when {
            error != null -> Column { header(); Box(Modifier.padding(start = RAIL, end = 32.dp)) { ErrorBox(error!!, onRetry = { attempt++ }, onSettings = onSettings) } }
            shows == null || streams == null -> Column { header(); Text(tr("Loading…"), color = Colors.dim, modifier = Modifier.padding(start = RAIL)) }
            lang == null -> Column { header(); Text(tr("No shows on the server yet."), color = Colors.dim, modifier = Modifier.padding(start = RAIL)) }
            else -> Rows(shows!!.filter { it.lang == lang }, lang, onShow, onPlay, kept, onDiscovered, streams!!, header, railFocus, toRows, api = api) {
                if (api is Api) LangCard(api, lang)
                else if (kept.isEmpty()) Text(tr("Find something to watch in Discover: what you play shows up here."), color = Colors.dim)
            }
        }
        // From the top, under the bear: centred, six buttons reached the bear on a 540 dp Chromecast, and the column
        // jumped when Review came.
        // The rows scroll under the round buttons: a dark edge behind them, so cards don't show through the glass.
        Box(Modifier.padding(top = 90.dp).fillMaxHeight().width(RAIL).fadeTop(0.1f).background(androidx.compose.ui.graphics.Brush.horizontalGradient( // from under the bear
            0f to Colors.background.copy(alpha = 0.92f), 0.7f to Colors.background.copy(alpha = 0.75f), 1f to Color.Transparent)))
        Rail(rail, railFocus, toRows.takeIf { error == null && shows != null && streams != null && lang != null }, Modifier.align(Alignment.TopStart).padding(top = 100.dp))
    }
}

/** Where the page starts right of the round buttons ([Rail]). */
internal val RAIL = 124.dp

/** A round button of the Home's left edge: [badge] > 0 shows a count on it (Review). */
internal class RailItem(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String, val badge: Int = 0,
                       val selected: Boolean = false, val back: Boolean = true, val onClick: () -> Unit)

/** The round button OK went through last: focused again when Home comes back. */
private var railLast: String? = null

/**
 * The tablet's Glass menu on the TV: round buttons down the left edge, Home first. The focused one shows its name
 * beside it (on a phone you see the icon and tap; with a remote you need to know where OK goes).
 */
@Composable
internal fun Rail(items: List<RailItem>, focus: MutableMap<String, FocusRequester>, toRows: FocusRequester?, modifier: Modifier) {
    Column(modifier.padding(start = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        items.forEach { item -> androidx.compose.runtime.key(item.label) {
            var focused by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    androidx.tv.material3.IconButton(onClick = { if (item.back && !item.selected) railLast = item.label; item.onClick() },
                        modifier = Modifier.size(56.dp).focusRequester(focus.getOrPut(item.label) { FocusRequester() }).onFocusChanged { focused = it.isFocused }.focusProperties { toRows?.let { right = it } },
                        colors = androidx.tv.material3.IconButtonDefaults.colors(
                            containerColor = Colors.background.copy(alpha = 0.55f), contentColor = Color.White, // Home too: white only when focused
                            focusedContainerColor = Color.White, focusedContentColor = Color.Black),
                        border = androidx.tv.material3.IconButtonDefaults.border(border = androidx.tv.material3.Border(
                            androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))))) {
                        androidx.tv.material3.Icon(item.icon, item.label, Modifier.size(26.dp))
                    }
                    if (item.badge > 0) Text(if (item.badge > 99) "99+" else "${item.badge}", color = Color.White, fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.TopEnd).background(Colors.unknown, androidx.compose.foundation.shape.CircleShape).padding(horizontal = 5.dp))
                }
                if (focused) Text(item.label, color = Color.White, fontSize = 16.sp, style = Arcade.label,
                    modifier = Modifier.padding(start = 12.dp).background(Colors.background.copy(alpha = 0.7f), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
            }
        } }
    }
}

/** The header's buttons: narrower than elsewhere so all nine of Pedro's fit a 960 dp TV. */
internal val TAB = PaddingValues(horizontal = 16.dp, vertical = 10.dp)

/** Hours in this language (TV + phones) and words known, above everything (user, 2026-10-15), as on the phone. */
@Composable
private fun LangCard(api: Api, lang: String) {
    var t by remember { mutableStateOf<io.github.pedrubik2000.kumapie.data.LangTotal?>(null) }
    var loaded by remember(lang) { mutableStateOf(false) }
    LaunchedEffect(lang) { t = runCatching { api.stats().langs[lang] }.getOrNull(); loaded = true }
    val s = t ?: return Spacer(Modifier.height(if (loaded) 0.dp else 66.dp)) // its height while loading: Home jumped down when it came
    // As the tablet's chips: today first (in the accent colour), dark glass with a thin white edge.
    Row(Modifier.padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(hoursMinutes(s.today) to tr("Today"), hoursMinutes(s.week) to tr("Last 7 days"),
            (if (s.total < 36000) hoursMinutes(s.total) else "${"%.1f".format(s.total / 3600)} h") to tr("In total"), "%,d".format(s.known) to tr("Words known"))
            .forEachIndexed { i, (v, l) ->
                Column(Modifier.background(Colors.background.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text(v, fontSize = 20.sp, color = if (i == 0) Colors.accent else Color.White, style = Arcade.label)
                    Text(l, fontSize = 12.sp, color = Colors.dim, style = Arcade.label)
                }
            }
    }
}

/** "Continue watching" (episodes left partway, as on the phone), then a row per media type. */
@Composable
private fun Rows(shows: List<Show>, lang: String, onShow: (Show) -> Unit, onPlay: (Show, Episode) -> Unit,
                 kept: List<Found> = emptyList(), onDiscovered: (Found) -> Unit = {}, streams: List<Pair<Show, Episode>> = emptyList(),
                 header: @Composable () -> Unit = {}, railFocus: Map<String, FocusRequester> = emptyMap(), first: FocusRequester = remember { FocusRequester() }, api: io.github.pedrubik2000.kumapie.data.Discovery? = null, stats: @Composable () -> Unit = {}) {
    val going = io.github.pedrubik2000.kumapie.data.continueRow(shows, streams)
    val more = going.drop(1) // the first is the picture at the top
    val other = shows.filter { s -> KINDS.none { it.first == s.kind } }
    val rows = KINDS.map { (k, label) -> tr(label) to shows.filter { it.kind == k } } + listOf(tr("Other") to other)
    // Down from Resume goes to the row under it: the beam search could take a far card instead.
    val below = remember { FocusRequester() }
    val firstShelf = rows.indexOfFirst { it.second.isNotEmpty() }
    // The banner stays whole while its buttons have the focus (the TV list scrolled Resume to the middle and cut its top).
    // Rows built a screen ahead in idle frames and kept a screen behind: building one as it scrolled in took ~57 ms
    // of a frame on the Chromecast (Continue watching -> the row below skipped 2-3 frames, 2026-11-18).
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    val list = androidx.compose.foundation.lazy.rememberLazyListState(
        cacheWindow = androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow(ahead = 600.dp, behind = 600.dp))
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Back down in the rows: up to Resume first (it closed the app).
    val scrolled = list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0
    LazyColumn(Modifier.onBackKey(scrolled) { scope.launch { list.scrollToItem(0); first.focusSoon() } }, state = list) {
        // As the tablet's Glass Home: the last episode across the top (Resume focused first, OK plays it), the
        // buttons and the stats on its picture (user, 2026-11-17; Home is for resuming fast). Novels count too.
        item(key = "top") {
            going.firstOrNull()?.let { (s, e) ->
                // The rows' characters, drawn once behind the picture (GlyphWarm).
                val warm = remember(kept, rows) {
                    val titles = kept.map { it.nameJa.ifEmpty { it.name } } + rows.flatMap { (_, l) -> l.map { shortTitle(it.shown) } }
                    val lines = kept.map { it.year } + rows.flatMap { (_, l) -> l.map(::levelLine) }
                    titles.joinToString("").toSet().joinToString("") to lines.joinToString("").toSet().joinToString("")
                }
                // A streamed title: its title page's picture (Backdrops), not the episode's frame.
                val context = androidx.compose.ui.platform.LocalContext.current
                var big by remember(s.id) { mutableStateOf(io.github.pedrubik2000.kumapie.data.Backdrops.saved(context, s.id)) }
                if (s.kind == "stream" && api != null) LaunchedEffect(s.id) {
                    if (big == null) big = io.github.pedrubik2000.kumapie.data.Backdrops.get(context, api, if (e.id == s.id) "movie" else "series", s.id)
                }
                Hero(s, e, header, stats, warm = warm, big.orEmpty(), Modifier.focusRequester(first).onFocusChanged { if (it.isFocused) scope.launch { list.animateScrollToItem(0) } }.focusProperties { if (more.isNotEmpty() || kept.isNotEmpty() || firstShelf >= 0) down = below },
                    onTop = { scope.launch { list.animateScrollToItem(0) } }, onShow = { onShow(s) }) { onPlay(s, e) }
            } ?: Column { header(); Box(Modifier.padding(horizontal = 32.dp)) { stats() } }
        }
        if (more.isNotEmpty()) item(key = "going") {
            // A new state when the streamed cards arrive: else the row stays scrolled to the old first card.
            Column {
                Heading(tr("Continue watching"), Modifier.padding(start = RAIL, top = 20.dp))
                LazyRow(state = remember(streams) { androidx.compose.foundation.lazy.LazyListState() }, horizontalArrangement = Arrangement.spacedBy(24.dp),
                    contentPadding = PaddingValues(start = RAIL, end = 32.dp, top = 12.dp, bottom = 24.dp)) { // room for the focused card to grow 10%
                    itemsIndexed(more, key = { _, p -> p.second.id }) { i, (s, e) ->
                        ResumeCard(s, e, if (i == 0) Modifier.focusRequester(below) else Modifier, width = 220.dp) { onPlay(s, e) }
                    }
                }
            }
        }
        if (kept.isNotEmpty()) item(key = "streaming") {
            Box { Shelf(tr("Streaming"), inset = RAIL) { // edge to edge: a padded box cut the posters with a straight line
                itemsIndexed(kept, key = { _, f -> f.id }) { i, f ->
                    Poster(f.poster, f.nameJa.ifEmpty { f.name }, f.year, null, when { i != 0 -> Modifier; going.isEmpty() -> Modifier.focusRequester(first); more.isEmpty() -> Modifier.focusRequester(below); else -> Modifier }) { onDiscovered(f) }
                }
            } }
        }
        rows.forEachIndexed { r, (label, list) ->
            // Easiest first, finished last, as on the phone.
            val list = list.sortedWith(compareBy({ ahead(it).isEmpty() }, { -(easyPercent(it) ?: -1) }))
            if (list.isNotEmpty()) item(key = "kind-$r") {
                val top = going.isEmpty() && kept.isEmpty() && rows.take(r).all { it.second.isEmpty() }
                Box { Shelf(label, inset = RAIL) {
                    itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
                        Poster(s.poster, shortTitle(s.shown), levelLine(s), null,
                            when {
                                i != 0 -> Modifier
                                top -> Modifier.focusRequester(first)
                                r == firstShelf && kept.isEmpty() && going.isNotEmpty() && more.isEmpty() -> Modifier.focusRequester(below)
                                else -> Modifier
                            }) { onShow(s) }
                    }
                } }
            }
        }
    }
    // Back from Discover, Stats or Settings: on its round button (it was always Resume).
    LaunchedEffect(lang, shows, streams) { railLast?.let { railFocus[it] }?.also { railLast = null }?.focusSoon() ?: first.focusSoon() }
}

/**
 * Home's top, as the tablet's Glass Home: the last episode's picture across the screen (about 60 % of its height),
 * the header ([header]) on it, then "CONTINUE WATCHING", the title, where it was left, Resume (plays from there, the
 * focus at start: [modifier]) and Episodes (the show's page); the stats ([stats]) at its bottom right. No blur on TVs:
 * dark gradients keep the text readable.
 */
@Composable
private fun Hero(s: Show, e: Episode, header: @Composable () -> Unit, stats: @Composable () -> Unit, warm: Pair<String, String>,
                 image: String, modifier: Modifier, onTop: () -> Unit, onShow: () -> Unit, onPlay: () -> Unit) {
    val screen = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    val height = (screen * 0.54f).dp
    Box(Modifier.fillMaxWidth().height(height)) {
        GlyphWarm(warm.first, warm.second, Modifier.matchParentSize())
        // The picture is a whole screen tall, as on the title page: it goes on under the first row (drawn over it) and
        // fades out to nothing (to the page's backdrop, a gradient: fading to one colour left a hard edge across the
        // screen, Chromecast 2026-11-17).
        Box(Modifier.fillMaxWidth()
            .layout { m, c ->
                val h = screen.dp.roundToPx()
                val p = m.measure(c.copy(minHeight = h, maxHeight = h))
                layout(c.maxWidth, c.maxHeight) { p.place(0, 0) }
            }
            .graphicsLayer(compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0.45f to Color.Black, 0.95f to Color.Transparent), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
            }) {
            Cover(image.ifEmpty { e.thumb.ifEmpty { s.poster } }, s.title, Modifier.matchParentSize(), showTitle = false, alignment = Alignment.TopCenter, description = null)
            Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to Colors.background.copy(alpha = 0.9f), 0.65f to Color.Transparent)))
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Colors.background.copy(alpha = 0.6f), 0.25f to Color.Transparent)))
        }
        header()
        Column(Modifier.align(Alignment.BottomStart).padding(start = RAIL, end = 24.dp, bottom = 24.dp).width(600.dp)) {
            Text(tr("Continue watching").uppercase(), color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, letterSpacing = 3.sp, style = Arcade.label)
            Text(shortTitle(s.shown), color = Color.White, fontSize = 52.sp, lineHeight = 58.sp, style = Arcade.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(e.title, resumeLine(e)).filter { it.isNotBlank() }.joinToString(" · "), color = Color.White.copy(alpha = 0.85f), fontSize = 18.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            if (e.duration > 0) ProgressBar(((e.resume ?: 0.0) / e.duration).toFloat(), Modifier.width(380.dp).padding(top = 12.dp))
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onPlay, modifier = modifier) { Text("▶  " + tr("Resume")) }
                Button(onClick = onShow, modifier = Modifier.onFocusChanged { if (it.isFocused) onTop() }) { Text(tr("Episodes")) }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 32.dp, bottom = 8.dp)) { stats() }
    }
}

@Composable
internal fun Shelf(title: String, inset: androidx.compose.ui.unit.Dp = 16.dp, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column {
        Heading(title, Modifier.padding(start = inset))
        // The focused card grows by 10%: padding keeps it inside the screen and off its title.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(horizontal = inset, vertical = 12.dp), content = content)
    }
}

@Composable
internal fun Poster(image: String, title: String, line: String, progress: Float?, modifier: Modifier, onClick: () -> Unit) {
    Column(Modifier.width(110.dp)) {
        Card(onClick = onClick, shape = arcadeCardShape(PosterSlab), border = arcadeCardBorder(PosterSlab), colors = arcadeCardColors(), modifier = modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            // The neutral poster where there's none (never an episode frame). Smaller, file names split at their dots:
            // 18 sp broke words in the middle ("Download/s", "chainsmo/ker.cat").
            Cover(image, title.replace('.', ' ').replace('_', ' '), Modifier.fillMaxSize(), fontSize = 13.sp, description = title)
        }
        // Two lines: seasons were all "SOUSOU NO FRIEREN …"; 16 dp: the focused card's frame ran into it (Chromecast).
        Text(title, color = Colors.text, style = MaterialTheme.typography.bodyMedium.merge(Arcade.label), maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 16.dp))
        Text(line, color = Colors.dim, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) // one line cut it to "quedan …"
        if (progress != null) ProgressBar(progress, Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

/**
 * The rows' characters in the posters' text styles, drawn behind the hero picture (1/255 opaque: invisible, but
 * drawn). The first time a character is drawn in a style its glyph is made (FreeType, fake bold for Japanese in
 * SemiBold): the rows' Japanese titles took two frames of 83-100 ms when they first scrolled in on the Chromecast
 * (2026-11-18); here they're made while Home opens. Clipped to the hero: what doesn't fit isn't drawn.
 */
@Composable
private fun GlyphWarm(titles: String, lines: String, modifier: Modifier) {
    val ink = Color.White.copy(alpha = 1f / 255)
    Column(modifier.clipToBounds()) {
        Text(titles, color = ink, style = MaterialTheme.typography.bodyMedium.merge(Arcade.label))
        Text(lines, color = ink, style = MaterialTheme.typography.bodySmall)
    }
}

/** An episode left partway: the show's poster cropped wide, then where it was left and how easy it is. */
@Composable
private fun ResumeCard(s: Show, e: Episode, modifier: Modifier, width: Dp, onClick: () -> Unit) {
    Column(Modifier.width(width)) {
        Card(onClick = onClick, shape = arcadeCardShape(RowSlab), border = arcadeCardBorder(RowSlab), colors = arcadeCardColors(), modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            Box(Modifier.fillMaxSize()) {
                Cover(s.poster, s.title, Modifier.fillMaxSize(), showTitle = false, alignment = Alignment.TopCenter)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Colors.background.copy(alpha = 0.85f))))
                if (e.duration > 0) ProgressBar(((e.resume ?: 0.0) / e.duration).toFloat(), Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp))
            }
        }
        Text(shortTitle(s.shown), color = Colors.text, style = (if (width > 300.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium).merge(Arcade.label), maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 18.dp))
        Text("${e.title} · ${resumeLine(e)}", color = Colors.dim, style = if (width > 300.dp) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ErrorBox(message: String, onRetry: () -> Unit, onSettings: (() -> Unit)?, server: Boolean = true) {
    val retry = remember { FocusRequester() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Title(if (server) tr("Can't reach the dojo server") else tr("That didn't work"), fontSize = 26.sp) // [server] false: no PC, the addons answered
            Text(if (server) plainError(message) else message, color = Colors.dim, modifier = Modifier.padding(vertical = 12.dp))
            if (server) Text(tr("Is the PC on, and Tailscale connected on the TV?"), color = Colors.dim)
            Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onRetry, modifier = Modifier.focusRequester(retry)) { Text(tr("Try again")) }
                if (onSettings != null) Button(onClick = onSettings) { Text(tr("Settings")) }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { retry.requestFocus() } }
}

/** Java's network messages in plain words, in the menu language (the parents saw "unexpected end of stream"). */
internal fun plainError(m: String): String = m.lowercase().let {
    when {
        "end of stream" in it || "reset" in it || "aborted" in it -> tr("The connection to the PC was cut.")
        "timeout" in it || "timed out" in it -> tr("The PC took too long to answer.")
        "failed to connect" in it || "refused" in it || "unable to resolve" in it || "unreachable" in it -> tr("The PC didn't answer.")
        else -> m
    }
}
