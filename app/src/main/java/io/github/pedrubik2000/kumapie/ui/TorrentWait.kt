package io.github.pedrubik2000.kumapie.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.EpisodeDetail
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.WaitWords
import io.github.pedrubik2000.kumapie.torrent.TorrentEngine
import io.github.pedrubik2000.kumapie.torrent.TorrentPhase
import io.github.pedrubik2000.kumapie.torrent.TorrentStatus
import io.github.pedrubik2000.kumapie.torrent.WaitText
import io.github.pedrubik2000.kumapie.torrent.waitText

data class WaitInfo(val id: String, val show: String, val number: Int?, val episode: String, val release: String, val backdrop: String?, val noSpoilers: Boolean = false)

private val PAGE = Color(0xFF0C0B11)
internal val GLASS = Color(0xFF2A2734).copy(alpha = 0.75f)
private const val FOCUS_DELAY_MS = 200L

@Composable
internal fun rememberStarted(player: Player, torrent: Boolean): Boolean {
    var started by remember(player) { mutableStateOf(!torrent || player.playbackState == Player.STATE_READY) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) started = true
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    return started
}

@Composable
private fun rememberPlayerState(player: Player): Int {
    var state by remember(player) { mutableIntStateOf(player.playbackState) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                state = playbackState
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    return state
}

@Composable
internal fun TorrentWait(
    player: Player, started: Boolean, info: WaitInfo, settings: Settings, episode: EpisodeDetail?, noLines: Boolean, onBack: () -> Unit,
    torrent: Boolean = true, onBuffering: (Boolean) -> Unit = {},
) {
    val state = rememberPlayerState(player)
    val buffering = state == Player.STATE_BUFFERING
    val status = (if (torrent && (!started || buffering)) TorrentEngine.status.collectAsState().value else null) ?: TorrentStatus()
    val words = rememberWaitWords(episode, settings, info.noSpoilers || started)
    val similar = rememberSimilar(settings, info, info.noSpoilers, !started)
    val showBuffer = started && buffering && torrent
    LaunchedEffect(showBuffer) { onBuffering(showBuffer) }
    if (!started) {
        BackHandler(onBack = onBack)
        WaitScreen(info, status, words, similar, noLines, onBack, if (torrent && state != Player.STATE_READY) waitText(status) else io.github.pedrubik2000.kumapie.torrent.gettingReadyText())
    } else if (showBuffer) {
        BufferingOverlay(player, status, if (info.noSpoilers) "" else info.release)
    }
}

@Composable
private fun WaitScreen(info: WaitInfo, status: TorrentStatus, words: WaitWords?, similar: SimilarState, noLines: Boolean, onBack: () -> Unit, text: WaitText) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(text.failed) { if (!text.failed) focus.requestFocus() }
    similar.root = focus
    val pick = similar.current
    val panel = !info.noSpoilers || pick != null
    val keys = Modifier.onKeyEvent {
        val down = it.type == KeyEventType.KeyDown
        when {
            !info.noSpoilers || pick == null -> false
            it.pressedNext() -> { similar.next(); true }
            down && it.key == Key.DirectionDown && similar.canAdd -> runCatching { similar.button.requestFocus() }.isSuccess
            else -> false
        }
    }
    Box(Modifier.fillMaxSize().background(PAGE).then(keys).focusRequester(focus).focusable(!text.failed)) {
        WaitBackdrop(info.backdrop)
        Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp), verticalAlignment = Alignment.Top) {
            WaitLeft(info, status, text, if (panel) Modifier.weight(1f).wrapContentWidth(Alignment.Start).widthIn(max = 560.dp) else Modifier.widthIn(max = 560.dp), onBack)
            if (panel) {
                Spacer(Modifier.width(32.dp))
                if (info.noSpoilers) SimilarPanel(similar, info, Modifier.padding(top = 24.dp).width(440.dp).height(440.dp))
                else WordsPanel(info.number, words, noLines, Modifier.padding(top = 24.dp).width(340.dp).heightIn(max = 460.dp))
            }
        }
    }
}

@Composable
private fun WaitLeft(info: WaitInfo, status: TorrentStatus, text: WaitText, modifier: Modifier, onBack: () -> Unit) {
    Column(modifier.padding(top = 120.dp)) {
        WaitHeader(info, text)
        Spacer(Modifier.height(20.dp))
        if (text.failed) WaitFailure(text, onBack) else WaitProgress(status, text)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            KeyCap("BACK")
            Text(tr("Cancel"), color = Colors.dim, fontSize = 16.sp)
        }
        if (info.release.isNotEmpty() && !info.noSpoilers) Text(info.release, color = Colors.dim, fontSize = 13.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable
private fun WaitBackdrop(url: String?) {
    if (url != null) coil3.compose.AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(24.dp))
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(PAGE.copy(alpha = 0.92f), PAGE.copy(alpha = 0.7f)))))
}

@Composable
private fun WaitHeader(info: WaitInfo, text: WaitText) {
    Text(text.label, color = Colors.dim, fontSize = 12.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Medium)
    Text(info.show, color = Colors.text, fontSize = 44.sp, lineHeight = 50.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 6.dp))
    val line = listOfNotNull(info.number?.let { tr("Episode %d", it) }, info.episode.takeIf { it.isNotBlank() }).joinToString(" · ")
    if (line.isNotEmpty()) Text(line, color = Colors.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
}

private fun peersLine(status: TorrentStatus, text: WaitText): String = when {
    status.peers <= 0 -> text.headline
    status.peers == 1 -> tr("1 peer found") + " · " + tr("looking for more")
    else -> tr("%d peers found", status.peers) + " · " + tr("looking for more")
}

@Composable
private fun WaitProgress(status: TorrentStatus, text: WaitText) {
    val finding = status.phase == TorrentPhase.PEERS
    Column(Modifier.fillMaxWidth().background(GLASS, RowSlab).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (finding) peersLine(status, text) else text.headline, color = Colors.text, fontSize = if (finding) 20.sp else 28.sp, fontWeight = FontWeight.Bold)
            if (!finding && text.detail.isNotEmpty()) Text(text.detail, color = Colors.dim, fontSize = 16.sp, modifier = Modifier.padding(bottom = 3.dp))
        }
        if (!finding) Box(Modifier.padding(vertical = 10.dp).fillMaxWidth().height(4.dp).background(Colors.text.copy(alpha = 0.15f), RowSlab)) {
            Box(Modifier.fillMaxWidth(status.percent / 100f).fillMaxHeight().background(Colors.accent, RowSlab))
        }
        Text(text.stats.ifEmpty { text.hint }, color = Colors.dim, fontSize = 16.sp, maxLines = 1, modifier = Modifier.padding(top = if (finding) 8.dp else 0.dp))
    }
}

@Composable
private fun WaitFailure(text: WaitText, onBack: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(FOCUS_DELAY_MS)
        runCatching { focus.requestFocus() }
    }
    val detail = text.detail.takeIf { it.isNotEmpty() && !it.startsWith(text.headline) && it != text.headline }
    Column(Modifier.fillMaxWidth().background(GLASS, RowSlab).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(text.headline, color = Colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (detail != null) Text(detail, color = Colors.dim, fontSize = 16.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 4.dp))
        Button(onClick = onBack, modifier = Modifier.padding(top = 12.dp).focusRequester(focus)) { Text(tr("Other releases")) }
    }
}
