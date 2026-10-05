package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.pedrubik2000.kumapie.ui.Colors

/** Same colours as the TV app (Colors in core): dark, the bear's brown as the accent. */
@Composable
fun MobileTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Colors.accent,
            onPrimary = Color.Black,
            secondary = Colors.accent,
            background = Colors.background,
            onBackground = Colors.text,
            surface = Colors.background,
            onSurface = Colors.text,
            surfaceContainer = Colors.surface,
            surfaceContainerHigh = Colors.surface,
            surfaceContainerHighest = Colors.surface,
            surfaceContainerLow = Colors.surface,
            onSurfaceVariant = Colors.dim,
        ),
        content = content,
    )
}

/** A thin progress bar (episode watched, show progress). */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(4.dp).background(Colors.surface, RoundedCornerShape(2.dp))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(Colors.accent, RoundedCornerShape(2.dp)))
    }
}

/** "24 min", "1 h 5 min". */
fun minutes(seconds: Double): String {
    val m = (seconds / 60).toInt()
    return when {
        m < 60 -> "$m min"
        m % 60 == 0 -> "${m / 60} h"
        else -> "${m / 60} h ${m % 60} min"
    }
}
