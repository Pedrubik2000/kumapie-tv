package io.github.pedrubik2000.kumapie.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.IntSize

fun Modifier.pageBackdrop(covered: () -> Boolean = { false }): Modifier =
    drawWithCache {
        val w = (size.width / 4).toInt().coerceAtLeast(1); val h = (size.height / 4).toInt().coerceAtLeast(1)
        val glow = ImageBitmap(w, h)
        CanvasDrawScope().draw(this, layoutDirection, Canvas(glow), Size(w.toFloat(), h.toFloat())) {
            drawRect(Brush.radialGradient(listOf(Color(0xFF1A2333), Colors.background), center = Offset(w * 0.15f, 0f), radius = w * 0.7f))
        }
        onDrawBehind {
            if (!covered()) drawImage(glow, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low)
        }
    }
