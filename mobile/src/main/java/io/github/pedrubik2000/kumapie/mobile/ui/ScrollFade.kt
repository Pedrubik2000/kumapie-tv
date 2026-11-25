package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp

internal fun Modifier.fadeTop(height: Dp, scroll: ScrollState): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
    drawContent()
    if (!scroll.canScrollBackward) return@drawWithContent
    val h = height.toPx()
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 0f, endY = h),
        topLeft = Offset.Zero, size = Size(size.width, h), blendMode = BlendMode.DstIn)
}
