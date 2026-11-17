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

/** One page of the feed: a whole reel. */
private data class FeedItem(val show: Show, val episode: EpisodeDetail)

/** Reels never watched first (random), then the ones with the most lines not seen yet, then, all watched, at random. */
private fun inOrder(reels: List<Pair<Show, EpisodeDetail>>): List<FeedItem> {
    fun unseen(e: EpisodeDetail) = e.scenes.count { !it.seen }
    val (left, watched) = reels.partition { (_, e) -> unseen(e) > 0 }
    val (fresh, started) = left.partition { (_, e) -> e.scenes.none { it.seen } }
    return (fresh.shuffled() + started.sortedByDescending { (_, e) -> unseen(e) } + watched.shuffled()).map { (show, e) -> FeedItem(show, e) }
}

/** The feed as last shuffled, kept while the app runs (coming back keeps the place). */
private object FeedKept {
    var lang: String? = null
    var items: List<FeedItem>? = null
    var page = 0
}

/**
 * The feed: reels (short clips), upright, one per page, swipe up for the next. Each page is the full player for that
 * reel: subtitles, word cards, dictionary, Add to Anki. Scenes cut from episodes were here until there were no
 * scenes (kumapie_plan.md "no more scenes").
 */
@Composable
fun FeedScreen(library: Library, shows: List<Show>, lang: String, onBack: () -> Unit) {
    FullScreen(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT)
    val settings = library.settings
    var items by remember { mutableStateOf(FeedKept.items.takeIf { FeedKept.lang == lang }) }
    var loading by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (items != null) return@LaunchedEffect
        val found = inOrder(library.allEpisodes(shows.filter { it.kind == "reels" }) { loading = it }.filter { (_, ep) -> ep.scenes.isNotEmpty() })
        FeedKept.lang = lang
        FeedKept.items = found
        FeedKept.page = 0
        items = found
        loading = if (found.isEmpty()) tr("No reels yet.") else ""
    }

    // A whole reel counts as watched: which reels come next goes by that.
    val backend = remember { library.backend(lang) } // the feed follows Home's language
    // The next page's video, opened and buffered while this one plays, so a swipe starts at once
    // (user, 2026-10-15: "takes too much time from video to video"). One at a time: each holds a decoder.
    val context = androidx.compose.ui.platform.LocalContext.current
    val warm = remember { HashMap<Int, androidx.media3.exoplayer.ExoPlayer>() }
    fun warmUp(list: List<FeedItem>, page: Int) {
        warm.keys.filter { it != page + 1 }.forEach { warm.remove(it)?.release() }
        val next = list.getOrNull(page + 1) ?: return
        if (page + 1 in warm) return
        warm[page + 1] = androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(next.episode.video), (next.episode.scenes.first().start * 1000).toLong())
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
                    key(item.episode.id) {
                        // The feed's episodes were read once: words marked known (or unmarked) since keep their colour.
                        val known = library.languages.of(item.episode.lang).known
                        val words = item.episode.words.mapValues { (k, w) ->
                            val m = k in known.markedWords
                            if (m == w.marked) w else w.copy(marked = m, status = if (m) "k" else known.status(k) ?: "u")
                        }
                        val ready = remember { warm.remove(page) } // buffered while the page before played
                        ScenePlayer(library, settings, backend,
                            item.episode.copy(resume = item.episode.scenes.first().start, words = words), startPaused = false, onBack = onBack,
                            preloaded = ready)
                    }
                } else {
                    // Neighbours while swiping: the reel's name, no player.
                    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                        Text("${item.show.title} · ${item.episode.title}", color = Colors.dim, fontSize = 20.sp,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
                    }
                }
            }
        }
    }
}
