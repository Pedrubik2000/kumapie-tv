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
import androidx.compose.foundation.lazy.LazyColumn
import io.github.pedrubik2000.kumapie.data.Found
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
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
fun HomeScreen(api: Api, settings: Settings, profile: String, who: String, onShow: (Show) -> Unit,
               onPlay: (Show, Episode) -> Unit, onSettings: () -> Unit, onStats: () -> Unit, onProfiles: () -> Unit,
               onReview: (String) -> Unit = {}, onDiscover: () -> Unit = {}, onDiscovered: (Found) -> Unit = {}) {
    var shows by remember { mutableStateOf<List<Show>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        runCatching { api.shows() }
            .onSuccess { all -> shows = all.filter { it.kind != "reels" }.map { s -> s.copy(episodes = s.episodes.filter { it.scenes > 0 }) }
                .filter { it.episodes.isNotEmpty() } } // 0 scenes: nothing to play scene by scene
            .onFailure { error = it.message ?: it.toString() }
    }
    val langs = Lang.ALL.map { it.code }.filter { c -> shows.orEmpty().any { it.lang == c } }
    var picked by remember(profile) { mutableStateOf(settings.prefs.getString("home_lang_$profile", null)) }
    val lang = picked?.takeIf { it in langs } ?: langs.firstOrNull()
    // Discover titles kept on Home (+ HOME on a title's page), to stream later: Japanese only, as Discover.
    var kept by remember { mutableStateOf(emptyList<Found>()) }
    LaunchedEffect(lang) { kept = if (lang == "ja") runCatching { api.saved() }.getOrDefault(emptyList()) else emptyList() }
    // Streamed titles in "Continue watching" too (the episode left partway, or the next one).
    // null while loading: the row waited for them, else it showed without them and reshuffled (focus on a card that moved).
    var streams by remember { mutableStateOf<List<Pair<Show, Episode>>?>(null) }
    LaunchedEffect(lang) { streams = if (lang == "ja") runCatching { api.going() }.getOrDefault(emptyList()) else emptyList() }

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 32.dp)) {
        // Scrolls sideways when the buttons don't fit (a squeezed SETTINGS wrapped letter by letter, 353 px tall, and
        // pushed everything down: bedroom TV, 2026-11-15); focus brings the far ones into view.
        // The logo stays put; only the buttons scroll (Pedro's nine cut SETTINGS and pushed the logo off, 2026-11-16).
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Title("kuma", "pie")
          Spacer(Modifier.width(20.dp))
          Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            if (langs.size > 1) langs.forEach { c ->
                Button(padding = TAB, onClick = { picked = c; settings.prefs.edit().putString("home_lang_$profile", c).apply() },
                    colors = arcadeButtonColors(selected = c == lang),
                    modifier = Modifier.padding(end = 8.dp)) { Text(Lang.of(c).displayName) }
            }
            Spacer(Modifier.width(16.dp))
            if (lang == "ja") { // Discover: Japanese only for now
                Button(padding = TAB, onClick = onDiscover) { Text(tr("Discover")) }
                Spacer(Modifier.width(8.dp))
            }
            if (lang != null) ReviewButton(api, lang, onReview)
            Button(padding = TAB, onClick = onProfiles) { Text(who) } // back to "Who's watching?"
            Spacer(Modifier.width(8.dp))
            Button(padding = TAB, onClick = onStats) { Text(tr("Stats")) }
            Spacer(Modifier.width(8.dp))
            Button(padding = TAB, onClick = onSettings) { Text(tr("Settings")) }
            Spacer(Modifier.width(12.dp)) // room for the focused button's 6% growth
          }
        }
        Spacer(Modifier.padding(8.dp))
        when {
            error != null -> ErrorBox(error!!, onRetry = { attempt++ }, onSettings = onSettings)
            shows == null || streams == null -> Text(tr("Loading…"), color = Colors.dim)
            lang == null -> Text(tr("No shows on the server yet."), color = Colors.dim)
            else -> {
                LangCard(api, lang)
                Rows(shows!!.filter { it.lang == lang }, lang, onShow, onPlay, kept, onDiscovered, streams!!)
            }
        }
    }
}

/** The header's buttons: narrower than elsewhere so all nine of Pedro's fit a 960 dp TV. */
private val TAB = PaddingValues(horizontal = 16.dp, vertical = 10.dp)

/** "Review · N" for a person whose cards are on the PC (the PC refuses the others: no button). */
@Composable
private fun ReviewButton(api: Api, lang: String, onReview: (String) -> Unit) {
    var n by remember(lang) { mutableStateOf<Int?>(null) }
    LaunchedEffect(lang) { n = runCatching { api.srsDue(lang).let { it.due + it.new } }.getOrNull() }
    val count = n ?: return
    Button(padding = TAB, onClick = { onReview(lang) }, colors = arcadeButtonColors(selected = count > 0)) {
        Text((tr("Review") + if (count > 0) " · $count" else ""))
    }
    Spacer(Modifier.width(8.dp))
}

/** Hours in this language (TV + phones) and words known, above everything (user, 2026-10-15), as on the phone. */
@Composable
private fun LangCard(api: Api, lang: String) {
    var t by remember { mutableStateOf<io.github.pedrubik2000.kumapie.data.LangTotal?>(null) }
    var loaded by remember(lang) { mutableStateOf(false) }
    LaunchedEffect(lang) { t = runCatching { api.stats().langs[lang] }.getOrNull(); loaded = true }
    val s = t ?: return Spacer(Modifier.height(if (loaded) 0.dp else 66.dp)) // its height while loading: Home jumped down when it came
    Row(Modifier.padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf((if (s.total < 36000) hoursMinutes(s.total) else "${(s.total / 3600).toInt()} h") to tr("In total"), hoursMinutes(s.week) to tr("Last 7 days"),
            hoursMinutes(s.today) to tr("Today"), "${s.known}" to tr("Words known")).forEach { (v, l) ->
            Column(Modifier.background(Arcade.slab, RowSlab).padding(horizontal = 26.dp, vertical = 8.dp)) {
                Text(v, fontSize = 22.sp, color = Colors.accent, style = Arcade.label)
                Text(l, fontSize = 12.sp, color = Colors.dim, style = Arcade.label)
            }
        }
    }
}

/** "Continue watching" (episodes left partway, as on the phone), then a row per media type. */
@Composable
private fun Rows(shows: List<Show>, lang: String, onShow: (Show) -> Unit, onPlay: (Show, Episode) -> Unit,
                 kept: List<Found> = emptyList(), onDiscovered: (Found) -> Unit = {}, streams: List<Pair<Show, Episode>> = emptyList()) {
    val going = io.github.pedrubik2000.kumapie.data.continueRow(shows, streams)
    val other = shows.filter { s -> KINDS.none { it.first == s.kind } }
    val rows = KINDS.map { (k, label) -> tr(label) to shows.filter { it.kind == k } } + listOf(tr("Other") to other)
    val first = remember { FocusRequester() }
    // Down from a Continue card goes to the row under it: the beam search took the next (lower, smaller) card instead.
    val below = remember { FocusRequester() }
    val firstShelf = rows.indexOfFirst { it.second.isNotEmpty() }
    // The top edge fades out, as on Discover: a row scrolled up showed half posters cut flat under the header.
    LazyColumn(Modifier.fadeTop(), contentPadding = PaddingValues(top = 12.dp)) {
        // As on the phone: the last episode as a big card (focused first, OK plays it), the other shows in progress
        // beside it as small cards (user, 2026-10-15: Home is for resuming fast). Novels are read: they count too.
        if (going.isNotEmpty()) item(key = "going") {
            // A new state when the streamed cards arrive: else the row stays scrolled to the old first card.
            LazyRow(state = remember(streams) { androidx.compose.foundation.lazy.LazyListState() }, horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Bottom,
                contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 20.dp, bottom = 24.dp)) { // room for the focused card to grow 10%
                itemsIndexed(going, key = { _, p -> p.second.id }) { i, (s, e) ->
                    ResumeCard(s, e, (if (i == 0) Modifier.focusRequester(first) else Modifier)
                        .focusProperties { if (kept.isNotEmpty() || firstShelf >= 0) down = below }, width = if (i == 0) 360.dp else 220.dp) { onPlay(s, e) }
                }
            }
        }
        if (kept.isNotEmpty()) item(key = "streaming") {
            Shelf(tr("Streaming")) {
                itemsIndexed(kept, key = { _, f -> f.id }) { i, f ->
                    Poster(f.poster, f.name, f.year, null, when { i != 0 -> Modifier; going.isEmpty() -> Modifier.focusRequester(first); else -> Modifier.focusRequester(below) }) { onDiscovered(f) }
                }
            }
        }
        rows.forEachIndexed { r, (label, list) ->
            // Easiest first, finished last, as on the phone.
            val list = list.sortedWith(compareBy({ ahead(it).isEmpty() }, { -(easyPercent(it) ?: -1) }))
            if (list.isNotEmpty()) item(key = "kind-$r") {
                val top = going.isEmpty() && kept.isEmpty() && rows.take(r).all { it.second.isEmpty() }
                Shelf(label) {
                    itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
                        Poster(s.poster, shortTitle(s.title), levelLine(s), null,
                            when {
                                i != 0 -> Modifier
                                top -> Modifier.focusRequester(first)
                                r == firstShelf && kept.isEmpty() && going.isNotEmpty() -> Modifier.focusRequester(below)
                                else -> Modifier
                            }) { onShow(s) }
                    }
                }
            }
        }
    }
    LaunchedEffect(lang, shows, streams) { first.focusSoon() }
}

@Composable
private fun Shelf(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column {
        Heading(title, Modifier.padding(start = 16.dp))
        // The focused card grows by 10%: padding keeps it inside the screen and off its title.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), content = content)
    }
}

@Composable
internal fun Poster(image: String, title: String, line: String, progress: Float?, modifier: Modifier, onClick: () -> Unit) {
    Column(Modifier.width(110.dp)) {
        Card(onClick = onClick, shape = arcadeCardShape(PosterSlab), border = arcadeCardBorder(PosterSlab), modifier = modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            Box(Modifier.fillMaxSize()) { // a Card lays out as a column: the box puts the image over the neutral poster
                NeutralPoster(title, Modifier.fillMaxSize()) // shows where there's no poster (never an episode frame)
                AsyncImage(model = image, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        // Two lines: seasons were all "SOUSOU NO FRIEREN …"; 16 dp: the focused card's frame ran into it (Chromecast).
        Text(title, color = Colors.text, style = MaterialTheme.typography.bodyMedium.merge(Arcade.label), maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 16.dp))
        Text(line, color = Colors.dim, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) // one line cut it to "quedan …"
        if (progress != null) ProgressBar(progress, Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

/** An episode left partway: the show's poster cropped wide, then where it was left and how easy it is. */
@Composable
private fun ResumeCard(s: Show, e: Episode, modifier: Modifier, width: Dp, onClick: () -> Unit) {
    Column(Modifier.width(width)) {
        Card(onClick = onClick, shape = arcadeCardShape(RowSlab), border = arcadeCardBorder(RowSlab), modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            Box(Modifier.fillMaxSize()) {
                NeutralPoster(s.title, Modifier.fillMaxSize(), showTitle = false)
                AsyncImage(model = s.poster, contentDescription = s.title, contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Colors.background.copy(alpha = 0.85f))))
                ProgressBar(((e.resume ?: 0.0) / e.duration).toFloat(), Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp))
            }
        }
        Text(shortTitle(s.title), color = Colors.text, style = (if (width > 300.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium).merge(Arcade.label), maxLines = 1,
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
            Text(plainError(message), color = Colors.dim, modifier = Modifier.padding(vertical = 12.dp))
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
