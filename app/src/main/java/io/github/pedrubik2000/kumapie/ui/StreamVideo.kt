package io.github.pedrubik2000.kumapie.ui

import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun StreamVideo(player: ExoPlayer, plain: Boolean, started: Boolean, buffering: Boolean, frameRate: Float?): PlayerView? {
    var view by remember { mutableStateOf<PlayerView?>(null) }
    MatchFrameRate(player, frameRate)
    AndroidView(
        factory = {
            PlayerView(it).apply {
                subtitleView?.visibility = android.view.View.GONE
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setBackgroundColor(android.graphics.Color.BLACK)
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                controllerAutoShow = false
                this.player = player
            }.also { made -> view = made }
        },
        modifier = Modifier.fillMaxSize(),
        update = { gate(it, plain && started, buffering) },
        onRelease = { it.player = null },
    )
    return view
}

private fun gate(view: PlayerView, controls: Boolean, buffering: Boolean) {
    view.useController = controls && !buffering
    view.isFocusable = controls
    view.descendantFocusability = if (controls) ViewGroup.FOCUS_BEFORE_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
    if (controls && !buffering && !view.hasFocus()) view.requestFocus()
    if (!controls && view.hasFocus()) view.clearFocus()
}

@Composable
fun MatchFrameRate(player: ExoPlayer, hint: Float? = null) {
    val view = LocalView.current
    val matched = remember(player) { AtomicBoolean(false) }
    val window = remember(view) {
        generateSequence(view.context) { (it as? android.content.ContextWrapper)?.baseContext }.firstNotNullOfOrNull { it as? android.app.Activity }?.window
    }
    fun match(fps: Float, said: Boolean = false) {
        val display = view.display ?: return
        if (window == null || fps <= 0f || matched.get()) return
        val now = display.mode
        val same = display.supportedModes.filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }.map { it.modeId to it.refreshRate }
        val id = modeForFrameRate(fps, now.modeId, same)
        if (said && id == null && modeForFrameRate(fps, -1, same) == null) return
        if (!matched.compareAndSet(false, true)) return
        android.util.Log.i("kumapie", "video $fps fps: display mode ${id ?: "kept"}")
        if (id != null) window.attributes = window.attributes.apply { preferredDisplayModeId = id }
    }
    LaunchedEffect(player, hint) { if (hint != null) match(hint, said = true) }
    DisposableEffect(player) {
        val times = ArrayList<Long>()
        var done = false
        val meta = androidx.media3.exoplayer.video.VideoFrameMetadataListener { us, _, format, _ ->
            if (done || matched.get()) return@VideoFrameMetadataListener
            val fps = if (format.frameRate > 0f) format.frameRate else { times += us; if (times.size < 75) return@VideoFrameMetadataListener; frameRateOf(times) }
            done = true
            if (fps != null) view.post { match(fps) }
        }
        player.setVideoFrameMetadataListener(meta)
        onDispose {
            player.clearVideoFrameMetadataListener(meta)
            window?.let { it.attributes = it.attributes.apply { preferredDisplayModeId = 0 } }
        }
    }
}
