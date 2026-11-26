package io.github.pedrubik2000.kumapie.torrent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

@Composable
fun torrentMessage(other: String?): String? {
    val line by TorrentEngine.line.collectAsState()
    return if (TorrentEngine.failed) line else other ?: line
}
