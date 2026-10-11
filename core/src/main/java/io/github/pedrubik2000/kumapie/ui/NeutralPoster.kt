package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A show without a poster: its title on a colour picked from the name (never an episode frame: no spoilers, item 24).
 * Drawn under the poster image, so it shows wherever the image is missing or fails to load.
 */
@Composable
fun NeutralPoster(title: String, modifier: Modifier = Modifier, fontSize: TextUnit = 18.sp, showTitle: Boolean = true) {
    val hue = ((title.hashCode() % 360) + 360) % 360
    Box(modifier.background(Color.hsv(hue.toFloat(), 0.35f, 0.32f)), contentAlignment = Alignment.Center) {
        if (showTitle) BasicText(title, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(12.dp),
            style = TextStyle(color = Colors.text, fontSize = fontSize, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center))
    }
}
