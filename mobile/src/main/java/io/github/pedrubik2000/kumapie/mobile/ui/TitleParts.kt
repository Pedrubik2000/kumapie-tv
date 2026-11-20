package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.pedrubik2000.kumapie.data.Found
import io.github.pedrubik2000.kumapie.ui.Colors

/** A backdrop fading into the page at the bottom. */
@Composable
internal fun androidx.compose.foundation.layout.BoxScope.Backdrop(image: String) {
    AsyncImage(image, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize().background(Colors.surface))
    Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.25f to Color.Transparent, 1f to Colors.background)))
}

/** The title's logo (fanart.tv), else its name. */
@Composable
internal fun TitleLogo(f: Found) {
    if (f.logo.isNotEmpty()) AsyncImage(f.logo, f.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
        modifier = Modifier.height(64.dp).widthIn(max = 260.dp))
    else Text(f.shownName, style = MaterialTheme.typography.headlineSmall, color = Colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
}
