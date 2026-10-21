package io.github.pedrubik2000.kumapie.mobile.ui

import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.data.Backend
import io.github.pedrubik2000.kumapie.data.EpisodeDetail
import io.github.pedrubik2000.kumapie.data.Scene
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.ui.Colors
import io.github.pedrubik2000.kumapie.i18n.tr

/** One page of the feed: a scene, played alone from its episode; or ([scene] null) a whole reel. */
private data class FeedItem(val show: Show, val episode: EpisodeDetail, val scene: Scene?)

/** A whole reel after every [REEL_EVERY] scenes (user, 2026-10-15). */
private const val REEL_EVERY = 3

/**
 * [scenes] with a whole reel after every [REEL_EVERY] of them, whatever its level: reels never watched first (random),
 * then the ones with the most scenes not seen yet, then, all watched, any reel at random.
 */
private fun withReels(scenes: List<FeedItem>, reels: List<Pair<Show, EpisodeDetail>>): List<FeedItem> {
    if (reels.isEmpty()) return scenes
    fun unseen(e: EpisodeDetail) = e.scenes.count { !it.seen }
    val (fresh, started) = reels.filter { (_, e) -> unseen(e) > 0 }.partition { (_, e) -> e.scenes.none { it.seen } }
    val queue = ArrayDeque(fresh.shuffled() + started.sortedByDescending { (_, e) -> unseen(e) })
    fun next() = (queue.removeFirstOrNull() ?: reels.random()).let { (show, e) -> FeedItem(show, e, null) }
    if (scenes.isEmpty()) return List(reels.size) { next() }
    return scenes.chunked(REEL_EVERY).flatMap { chunk -> if (chunk.size == REEL_EVERY) chunk + next() else chunk }
}

/** The feed as last shuffled, kept while the app runs (coming back keeps the place). */
private object FeedKept {
    var lang: String? = null
    var levels: Set<Int>? = null
    /** Scenes seen when it was built: watching more makes a new feed. */
    var seen = -1
    var items: List<FeedItem>? = null
    var page = 0
}

/**
 * The feed: scenes from every show, upright, one per page, swipe up for the next; by default only i+1 scenes (the
 * level filter is in ⚙ and remembered). Each page is the full player for that one scene: subtitles, word cards,
 * dictionary, Add to Anki. Watching here doesn't move the episode's resume point.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(library: Library, shows: List<Show>, lang: String, onBack: () -> Unit) {
    FullScreen(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT)
    val settings = library.settings
    var levels by remember { mutableStateOf(settings.feedLevels.ifEmpty { setOf(1) }) }
    val seenNow = remember { library.progress.merged.seen.size }
    fun kept() = FeedKept.levels == levels && FeedKept.lang == lang && FeedKept.seen == seenNow
    var items by remember { mutableStateOf(FeedKept.items.takeIf { kept() }) }
    var loading by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(false) }

    LaunchedEffect(levels) {
        if (items != null && kept()) return@LaunchedEffect
        items = null
        // No spoilers: only scenes already watched to the end (reels are short clips, nothing to spoil).
        // Only episodes with a scene watched to the end can give a scene (reels come whole): the rest aren't read.
        val wanted = shows.map { s -> if (s.kind == "reels") s else s.copy(episodes = s.episodes.filter { it.seen > 0 }) }.filter { it.episodes.isNotEmpty() }
        val all = library.allEpisodes(wanted) { loading = it }
        // Reels come whole, every few pages (withReels), not as single scenes.
        val scenes = all.filter { (show, _) -> show.kind != "reels" }.flatMap { (show, ep) ->
            ep.scenes.filter { it.target && it.level != null && it.level!!.coerceAtMost(2) in levels && it.seen }
                .map { FeedItem(show, ep, it) }
        }.shuffled()
        val found = withReels(scenes, all.filter { (show, ep) -> show.kind == "reels" && ep.scenes.isNotEmpty() })
        FeedKept.lang = lang
        FeedKept.levels = levels
        FeedKept.seen = seenNow
        FeedKept.items = found
        // Which episodes the feed drew from (adb logcat -s kumapie): checks the no-spoiler rule against "N seen".
        android.util.Log.i("kumapie", "feed: ${found.size} scenes, levels $levels: " +
            found.groupingBy { "${it.show.title} / ${it.episode.title}" + if (it.scene == null) " (whole)" else "" }.eachCount().entries.joinToString { "${it.key} ${it.value}" })
        FeedKept.page = 0
        items = found
        loading = if (found.isEmpty()) tr("No scenes yet: the feed only shows scenes you've already watched, so nothing is spoiled.") else ""
    }

    // Feed watching sends lookups and "mark known" as usual, but no progress: an episode resumes where it was left.
    val backend = remember {
        val real = library.backend(lang) // the feed follows Home's language
        object : Backend by real {
            override suspend fun progress(episode: String, pos: Double, seen: Collection<String>, watched: Double) {}
        }
    }
    // A whole reel counts as watched (its scenes seen): which reels come next goes by that.
    val reelBackend = remember { library.backend(lang) }
    // The next page's video, opened and buffered at its scene while this one plays, so a swipe starts at once
    // (user, 2026-10-15: "takes too much time from video to video"). One at a time: each holds a decoder.
    val context = androidx.compose.ui.platform.LocalContext.current
    val warm = remember { HashMap<Int, androidx.media3.exoplayer.ExoPlayer>() }
    fun warmUp(list: List<FeedItem>, page: Int) {
        warm.keys.filter { it != page + 1 }.forEach { warm.remove(it)?.release() }
        val next = list.getOrNull(page + 1) ?: return
        if (page + 1 in warm) return
        val start = next.scene?.start ?: next.episode.scenes.first().start
        warm[page + 1] = androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(next.episode.video), (start * 1000).toLong())
            playWhenReady = false
            prepare()
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { warm.values.forEach { it.release() }; warm.clear() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val list = items
        if (list.isNullOrEmpty()) {
            Text(loading, color = Colors.dim, fontSize = 16.sp, modifier = Modifier.align(Alignment.Center))
        } else {
            val pager = rememberPagerState(initialPage = FeedKept.page.coerceIn(0, list.lastIndex)) { list.size }
            LaunchedEffect(pager.settledPage) { FeedKept.page = pager.settledPage; warmUp(list, pager.settledPage) }
            VerticalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 0) { page ->
                val item = list[page]
                if (page == pager.settledPage) {
                    key(item.scene?.id ?: item.episode.id) {
                        // The feed's episodes were read once: words marked known (or unmarked) since keep their colour.
                        val known = library.languages.of(item.episode.lang).known
                        val words = item.episode.words.mapValues { (k, w) ->
                            val m = k in known.markedWords
                            if (m == w.marked) w else w.copy(marked = m, status = if (m) "k" else known.status(k) ?: "u")
                        }
                        val scene = item.scene
                        val ready = remember { warm.remove(page) } // buffered while the page before played
                        if (scene == null) ScenePlayer(library, settings, reelBackend,
                            item.episode.copy(resume = item.episode.scenes.first().start, words = words), startPaused = false, onBack = onBack,
                            preloaded = ready)
                        else ScenePlayer(library, settings, backend,
                            item.episode.copy(scenes = listOf(scene), resume = scene.start, words = words),
                            startPaused = false, onBack = onBack, preloaded = ready)
                    }
                } else {
                    // Neighbours while swiping: the scene's words, no player.
                    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                        Text(item.scene?.cues?.joinToString(" ") { it.text } ?: "${item.show.title} · ${item.episode.title}", color = Colors.dim, fontSize = 20.sp,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
                    }
                }
            }
        }
        IconButton(onClick = { filter = true }, modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(8.dp)
            .background(Color(0x66000000), androidx.compose.foundation.shape.CircleShape)) {
            Icon(Icons.Default.Tune, tr("Feed levels"), tint = Colors.text)
        }
    }

    if (filter) {
        ModalBottomSheet(onDismissRequest = { filter = false }) {
            Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Scenes in the feed") + " · " + io.github.pedrubik2000.kumapie.data.Lang.of(lang).displayName, color = Colors.accent, fontSize = 17.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "i+0", 1 to "i+1", 2 to "i+2 and up").forEach { (level, label) ->
                        FilterChip(selected = level in levels, label = { Text(tr(label)) }, onClick = {
                            val next = if (level in levels) levels - level else levels + level
                            if (next.isNotEmpty()) {
                                levels = next
                                settings.feedLevels = next
                            }
                        })
                    }
                }
                Text(tr("i+1 = one new word. Changing this reshuffles the feed."), color = Colors.dim, fontSize = 13.sp)
            }
        }
    }
}

