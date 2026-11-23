package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.data.resumeLine
import io.github.pedrubik2000.kumapie.data.shortTitle
import io.github.pedrubik2000.kumapie.data.shown

@Composable
internal fun Shelf(title: String, inset: Dp = 16.dp, content: LazyListScope.() -> Unit) {
    Column {
        Heading(title, Modifier.padding(start = inset))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(horizontal = inset, vertical = 12.dp), content = content)
    }
}

@Composable
internal fun Poster(image: String, title: String, line: String, progress: Float?, modifier: Modifier, percent: Int? = null, width: Dp = 110.dp, onClick: () -> Unit) {
    Column(Modifier.width(width)) {
        Card(onClick = onClick, shape = arcadeCardShape(PosterSlab), border = arcadeCardBorder(PosterSlab), colors = arcadeCardColors(), modifier = modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            Box(Modifier.fillMaxSize()) {
                Cover(image, title.replace('.', ' ').replace('_', ' '), Modifier.fillMaxSize(), fontSize = 13.sp, description = title)
                percent?.let { DifficultyTag(it, Modifier.align(Alignment.TopStart).padding(6.dp)) }
            }
        }
        Text(title, color = Colors.text, style = MaterialTheme.typography.bodyMedium.merge(Arcade.label), maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 16.dp))
        Text(line, color = Colors.dim, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (progress != null) ProgressBar(progress, Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

@Composable
internal fun DifficultyTag(percent: Int, modifier: Modifier) =
    Text("$percent%", color = Color.White, fontSize = 14.sp, style = Arcade.label,
        modifier = modifier.background(bandColour(percent), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))

private val CARD_FADE = Brush.verticalGradient(0.5f to Color.Transparent, 1f to Arcade.background.copy(alpha = 0.85f))

@Composable
internal fun ResumeCard(s: Show, e: Episode, modifier: Modifier, width: Dp, picture: String? = null, onClick: () -> Unit) {
    Column(Modifier.width(width)) {
        Card(onClick = onClick, shape = arcadeCardShape(RowSlab), border = arcadeCardBorder(RowSlab), colors = arcadeCardColors(), modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            Box(Modifier.fillMaxSize()) {
                Cover(picture ?: s.poster, s.title, Modifier.fillMaxSize(), showTitle = false, alignment = Alignment.TopCenter)
                Box(Modifier.fillMaxSize().background(CARD_FADE))
                if (e.duration > 0) ProgressBar(((e.resume ?: 0.0) / e.duration).toFloat(), Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp))
            }
        }
        Text(shortTitle(s.shown), color = Colors.text, style = MaterialTheme.typography.titleMedium.merge(Arcade.label), maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 18.dp))
        Text("${e.title} · ${resumeLine(e)}", color = Colors.dim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun GlyphWarm(titles: String, lines: String, modifier: Modifier) {
    val ink = Color.White.copy(alpha = 1f / 255)
    Column(modifier.clipToBounds()) {
        Text(titles, color = ink, style = MaterialTheme.typography.bodyMedium.merge(Arcade.label))
        Text(lines, color = ink, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun titlePicture(s: Show, e: Episode, api: io.github.pedrubik2000.kumapie.data.Discovery?): String? {
    val context = androidx.compose.ui.platform.LocalContext.current
    var big by androidx.compose.runtime.remember(s.id) { androidx.compose.runtime.mutableStateOf(io.github.pedrubik2000.kumapie.data.Backdrops.saved(context, s.id)) }
    if (s.kind == "stream" && api != null) androidx.compose.runtime.LaunchedEffect(s.id) {
        if (big == null) big = io.github.pedrubik2000.kumapie.data.Backdrops.get(context, api, if (e.id == s.id) "movie" else "series", s.id)
    }
    return big
}
