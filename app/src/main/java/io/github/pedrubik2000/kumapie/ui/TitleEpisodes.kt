package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import io.github.pedrubik2000.kumapie.data.TitlePage
import io.github.pedrubik2000.kumapie.data.partway
import io.github.pedrubik2000.kumapie.i18n.tr
import org.json.JSONObject

private val TILE_WIDTH = 170.dp
private val LENGTH = Regex("""^\s*(\d+)(?:\s*min\w*)?\s*$""", RegexOption.IGNORE_CASE)

private class TileLook(val watched: Boolean, val fraction: Float?, val tag: Boolean, val blurred: Boolean)

private fun lengthText(runtime: String): String? = LENGTH.find(runtime)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..600 }?.let { tr("%d min", it) }

@Composable
internal fun EpisodeRow(page: TitlePage, blur: Boolean, focus: TitleFocus, onPlay: (JSONObject?) -> Unit, onMark: (JSONObject) -> Unit, modifier: Modifier) {
    val eps = page.episodes
    val next = page.next
    val at = remember(eps, next?.optString("id")) { eps.indexOf(next).coerceAtLeast(0) }
    val title = page.shown
    val length = lengthText(title.runtime)
    val resuming = page.resuming?.takeIf { partway(it.resume, it.duration) }
    val list = rememberLazyListState(at)
    var rowFocused by remember { mutableStateOf(false) }
    LaunchedEffect(at) { if (!rowFocused) list.scrollToItem(at) }
    Column(modifier) {
        Row(Modifier.padding(start = RAIL), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Heading(tr("Episodes"))
            Text(factLine(eps.size.takeIf { it > 0 }?.toString(), if (blur) tr("No spoilers on") else null), color = Colors.dim, fontSize = 14.sp)
        }
        LazyRow(state = list, modifier = Modifier.padding(start = RAIL - 8.dp).onFocusChanged { rowFocused = it.hasFocus }.focusRequester(focus.row).focusRestorer(focus.episodes), horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(start = 8.dp, end = 32.dp, top = 10.dp, bottom = 8.dp)) {
            if (eps.isEmpty()) item {
                EpisodeTile(title.background.ifEmpty { title.poster }, tr("Watch"), factLine(tr("Watch"), title.year.ifEmpty { null }, length),
                    TileLook(false, null, false, false), focusEpisode(focus, true).hint(HintKind.PLAY), null) { onPlay(null) }
            }
            itemsIndexed(eps, key = { _, e -> e.getString("id") }) { i, e ->
                val watched = e.optBoolean("watched")
                val going = resuming?.takeIf { it.id == e.optString("id") && !watched }
                val left = going?.let { leftText(it) }
                val look = TileLook(watched, going?.let { ((it.resume ?: 0.0) / it.duration).toFloat() }, next?.optString("id") == e.optString("id") && !watched, blur && !watched && going == null)
                val caption = factLine(tr("Ep %d", e.optInt("number")), if (watched) tr("Watched") else left ?: length)
                val ready = e.optBoolean("ready")
                EpisodeTile(e.optString("thumb"), tr("Episode %1\$d", e.optInt("number")), caption, look,
                    focusEpisode(focus, i == at).hint(if (ready) HintKind.EPISODE_FILES else HintKind.EPISODE).onPreviewKeyEvent { k ->
                        (ready && k.key == Key.DirectionDown).also { if (it && k.type == KeyEventType.KeyDown) page.others(e) }
                    }, { onMark(e) }) { onPlay(e) }
            }
        }
    }
}

private fun focusEpisode(focus: TitleFocus, entry: Boolean): Modifier =
    (if (entry) Modifier.focusRequester(focus.episodes) else Modifier).focusProperties { up = focus.main }

@Composable
private fun EpisodeTile(thumb: String, name: String, caption: String, look: TileLook, modifier: Modifier, onLongClick: (() -> Unit)?, onClick: () -> Unit) {
    Column(Modifier.width(TILE_WIDTH)) {
        Card(onClick = onClick, onLongClick = onLongClick, shape = arcadeCardShape(PosterSlab), border = arcadeCardBorder(PosterSlab),
            colors = arcadeCardColors(), modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f).semantics { text = AnnotatedString(name) }) {
            Box(Modifier.fillMaxSize().background(Arcade.slab)) {
                var failed by remember(thumb) { mutableStateOf(false) }
                if (thumb.isNotEmpty() && !failed) AsyncImage(thumb, null, contentScale = ContentScale.Crop, onError = { failed = true },
                    modifier = Modifier.fillMaxSize().then(if (look.blurred) Modifier.blur(16.dp) else Modifier).alpha(if (look.watched) 0.55f else 1f))
                if (look.tag) Text(tr("Next up").uppercase(), color = Arcade.accent, fontSize = 13.sp, style = Arcade.label, letterSpacing = 1.sp,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Arcade.band, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
                if (look.watched) Icon(Icons.Default.Check, null, tint = Color.Black,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp).background(Color.White, CircleShape).padding(3.dp))
                if (look.watched) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp).height(3.dp).background(Color.White.copy(alpha = 0.85f), RoundedCornerShape(2.dp)))
                else look.fraction?.let { ProgressBar(it, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp)) }
            }
        }
        Text(caption, color = Colors.dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
    }
}
