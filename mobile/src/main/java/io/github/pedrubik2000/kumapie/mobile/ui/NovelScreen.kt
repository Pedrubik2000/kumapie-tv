package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import io.github.pedrubik2000.kumapie.player.SceneController

/**
 * A novel's screen drawn by kumapie (item 46): the line's starting picture, then each change fading in over its time,
 * following the audio's position. The episode's file is audio only, so this covers the empty video.
 * The pictures of this line and the next few are decoded ahead, and a screen whose pictures aren't ready yet keeps
 * the last one drawn: loading them on demand cut to black for a moment at every change.
 * A change with a mask is the game's wipe: the mask's bright parts come in first, with a soft edge (as higurashi.py's
 * blend() for the TV video).
 */
@Composable
fun NovelScreen(ctl: SceneController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scene = ctl.scene
    // Read the player every frame: the 40 ms tick made the fades step.
    var pos by remember { mutableDoubleStateOf(ctl.position) }
    LaunchedEffect(ctl) { while (true) withFrameMillis { pos = ctl.livePosition() } }

    val bitmaps = remember { mutableStateMapOf<String, ImageBitmap?>() } // null: failed, drawn without it
    LaunchedEffect(ctl.index) {
        val near = ctl.scenes.drop(ctl.index).take(4).flatMap { listOfNotNull(it.base) + it.pics.flatMap { p -> listOfNotNull(p.url, p.mask) } }.distinct()
        bitmaps.keys.retainAll(near.toSet())
        val loader = SingletonImageLoader.get(context)
        for (url in near) if (url !in bitmaps) {
            val r = loader.execute(ImageRequest.Builder(context).data(url).build())
            bitmaps[url] = (r as? SuccessResult)?.image?.toBitmap()?.asImageBitmap()
        }
    }

    // Only the last picture that is fully in, plus the ones still fading over it.
    val shown = scene.pics.filter { it.start <= pos }
    val from = shown.indexOfLast { it.start + it.fade <= pos }
    val layers = (if (from < 0) listOfNotNull(scene.base?.let { Triple(it, null, { 1f }) }) else emptyList()) +
        shown.drop(from.coerceAtLeast(0)).map { p ->
            Triple(p.url, p.mask, { if (p.fade <= 0) 1f else ((pos - p.start) / p.fade).toFloat().coerceIn(0f, 1f) })
        }
    val last = remember { arrayOf<List<Triple<ImageBitmap, ImageBitmap?, () -> Float>>>(emptyList()) }
    if (layers.all { it.first in bitmaps && (it.second == null || it.second in bitmaps) })
        last[0] = layers.mapNotNull { (url, mask, p) -> bitmaps[url]?.let { Triple(it, mask?.let(bitmaps::get), p) } }

    Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(4f / 3f)) {
            last[0].forEach { (bmp, mask, p) ->
                val m = Modifier.fillMaxSize()
                Image(bmp, null, contentScale = ContentScale.Fit, modifier = if (mask == null) m.graphicsLayer { alpha = p() } else m
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawImage(mask, dstSize = IntSize(size.width.toInt(), size.height.toInt()), blendMode = BlendMode.DstIn, colorFilter = wipe(p()))
                    })
            }
        }
    }
}

/** The mask's grey m as alpha 4m + 5p - 4: at p of the way, the parts brighter than 1 - 1.25p are in (blend()'s edge). */
private fun wipe(p: Float) = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
    0f, 0f, 0f, 0f, 0f,
    0f, 0f, 0f, 0f, 0f,
    0f, 0f, 0f, 0f, 0f,
    4f, 0f, 0f, 0f, (5 * p - 4) * 255,
)))
