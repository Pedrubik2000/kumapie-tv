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

/** One page of the feed: a scene, played alone from its episode. */
private data class FeedItem(val show: Show, val episode: EpisodeDetail, val scene: Scene)

/** The feed as last shuffled, kept while the app runs (coming back keeps the place). */
private object FeedKept {
    var levels: Set<Int>? = null
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
fun FeedScreen(library: Library, shows: List<Show>, onBack: () -> Unit) {
    FullScreen(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT)
    val settings = library.settings
    var levels by remember { mutableStateOf(settings.feedLevels.ifEmpty { setOf(1) }) }
    var items by remember { mutableStateOf(FeedKept.items.takeIf { FeedKept.levels == levels }) }
    var loading by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(false) }

    LaunchedEffect(levels) {
        if (items != null && FeedKept.levels == levels) return@LaunchedEffect
        items = null
        val found = library.allEpisodes(shows) { loading = it }.flatMap { (show, ep) ->
            ep.scenes.filter { it.german && it.level != null && it.level!!.coerceAtMost(2) in levels }.map { FeedItem(show, ep, it) }
        }.shuffled()
        FeedKept.levels = levels
        FeedKept.items = found
        FeedKept.page = 0
        items = found
        loading = if (found.isEmpty()) "No scenes at these levels." else ""
    }

    // Feed watching sends lookups and "mark known" as usual, but no progress: an episode resumes where it was left.
    val backend = remember {
        val real = library.backend()
        object : Backend by real {
            override suspend fun progress(episode: String, pos: Double, seen: Collection<String>, watched: Double) {}
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val list = items
        if (list.isNullOrEmpty()) {
            Text(loading, color = Colors.dim, fontSize = 16.sp, modifier = Modifier.align(Alignment.Center))
        } else {
            val pager = rememberPagerState(initialPage = FeedKept.page.coerceIn(0, list.lastIndex)) { list.size }
            LaunchedEffect(pager.settledPage) { FeedKept.page = pager.settledPage }
            VerticalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 0) { page ->
                val item = list[page]
                if (page == pager.settledPage) {
                    key(item.scene.id) {
                        ScenePlayer(library, settings, backend,
                            item.episode.copy(scenes = listOf(item.scene), resume = item.scene.start),
                            startPaused = false, onBack = onBack)
                    }
                } else {
                    // Neighbours while swiping: the scene's words, no player.
                    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                        Text(item.scene.cues.joinToString(" ") { it.text }, color = Colors.dim, fontSize = 20.sp,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
                    }
                }
            }
        }
        IconButton(onClick = { filter = true }, modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(8.dp)
            .background(Color(0x66000000), androidx.compose.foundation.shape.CircleShape)) {
            Icon(Icons.Default.Tune, "Feed levels", tint = Colors.text)
        }
    }

    if (filter) {
        ModalBottomSheet(onDismissRequest = { filter = false }) {
            Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Scenes in the feed", color = Colors.accent, fontSize = 17.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "i+0", 1 to "i+1", 2 to "i+2 and up").forEach { (level, label) ->
                        FilterChip(selected = level in levels, label = { Text(label) }, onClick = {
                            val next = if (level in levels) levels - level else levels + level
                            if (next.isNotEmpty()) {
                                levels = next
                                settings.feedLevels = next
                            }
                        })
                    }
                }
                Text("i+1 = one new word. Changing this reshuffles the feed.", color = Colors.dim, fontSize = 13.sp)
            }
        }
    }
}

/**
 * An episode upright: every scene in order, one per page, swipe up for the next; starts on the scene it was left
 * in and reports progress like the landscape player. ⋮ > "Upright" off goes back to landscape.
 */
@Composable
fun UprightEpisode(library: Library, episode: EpisodeDetail, onBack: () -> Unit, onLandscape: () -> Unit) {
    FullScreen(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT)
    val scenes = episode.scenes
    if (scenes.isEmpty()) return
    val start = remember {
        val resume = episode.resume ?: 0.0
        scenes.indexOfLast { it.start <= resume + 0.05 }.coerceAtLeast(0)
    }
    val backend = remember { library.backend() }
    val pager = rememberPagerState(initialPage = start) { scenes.size }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 0) { page ->
            val scene = scenes[page]
            if (page == pager.settledPage) {
                key(scene.id) {
                    ScenePlayer(library, library.settings, backend, episode.copy(scenes = listOf(scene), resume = scene.start),
                        startPaused = false, onBack = onBack, onUpright = onLandscape, uprightNow = true)
                }
            } else {
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    Text(scene.cues.joinToString(" ") { it.text }, color = Colors.dim, fontSize = 20.sp,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
                }
            }
        }
    }
}
