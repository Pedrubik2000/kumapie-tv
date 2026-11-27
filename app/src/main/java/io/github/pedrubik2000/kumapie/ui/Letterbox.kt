package io.github.pedrubik2000.kumapie.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.VideoSize

@Composable
internal fun rememberSubtitleGap(player: Player): Dp {
    var size by remember(player) { mutableStateOf(player.videoSize) }
    DisposableEffect(player) {
        val listen = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) { size = videoSize }
        }
        player.addListener(listen)
        onDispose { player.removeListener(listen) }
    }
    val screen = LocalConfiguration.current
    return letterboxGap(screen.screenWidthDp.toFloat(), screen.screenHeightDp.toFloat(),
        size.width * size.pixelWidthHeightRatio, size.height.toFloat()).dp
}

internal fun letterboxGap(screenW: Float, screenH: Float, videoW: Float, videoH: Float): Float {
    if (videoW <= 0f || videoH <= 0f || screenW <= 0f) return 40f
    val band = (screenH - minOf(screenH, screenW * videoH / videoW)) / 2f
    return maxOf(40f, band + 16f)
}
