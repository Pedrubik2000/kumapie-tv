package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import io.github.pedrubik2000.kumapie.player.SceneController

/**
 * A novel's screen drawn by kumapie (item 46): the line's starting picture, then each change fading in over its time,
 * following the audio's position. The episode's file is audio only, so this covers the empty video.
 * ponytail: the game's mask wipes are drawn as plain fades; a shader would do the real wipe if it matters.
 */
@Composable
fun NovelScreen(ctl: SceneController, modifier: Modifier = Modifier) {
    val scene = ctl.scene
    val pos = ctl.position
    Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(4f / 3f)) {
            // Only the last picture that is fully in, plus the ones still fading over it.
            val shown = scene.pics.filter { it.start <= pos }
            val from = shown.indexOfLast { it.start + it.fade <= pos }
            val bottom = if (from >= 0) shown[from].url else scene.base
            AsyncImage(bottom, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            shown.drop(from + 1).forEach { p ->
                val a = if (p.fade <= 0) 1f else ((pos - p.start) / p.fade).toFloat().coerceIn(0f, 1f)
                AsyncImage(p.url, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().alpha(a))
            }
        }
    }
}
