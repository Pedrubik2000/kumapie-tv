package io.github.pedrubik2000.kumapie.torrent

import io.github.pedrubik2000.kumapie.i18n.tr

enum class TorrentPhase { PEERS, DOWNLOADING, FAILED }

enum class TorrentFault { NONE, OFF, NO_PEERS, NO_SPACE, STALLED, OTHER }

data class TorrentStatus(
    val phase: TorrentPhase = TorrentPhase.PEERS,
    val fault: TorrentFault = TorrentFault.NONE,
    val peers: Int = 0,
    val rate: Long = 0,
    val have: Long = 0,
    val need: Long = 0,
    val ahead: Long = 0,
    val size: Long = 0,
    val release: String = "",
    val message: String = "",
) {
    val percent: Int get() = if (need <= 0) 0 else (have * 100 / need).toInt().coerceIn(0, 100)

    val bufferPercent: Int get() = (ahead * 100 / RESUME_BYTES).toInt().coerceIn(0, 100)

    val chunk: Float get() = if (size <= 0) 0f else (ahead.toFloat() / size).coerceIn(0f, 1f)

    val etaSeconds: Int? get() = if (rate < MIN_RATE || have >= need) null else ((need - have + rate - 1) / rate).toInt().takeIf { it in 1..MAX_ETA }

    companion object {
        const val RESUME_BYTES = 4L shl 20
        private const val MIN_RATE = 50_000L
        private const val MAX_ETA = 99

        fun startNeed(size: Long): Long = minOf(size, TorrentPlan.HEAD_BYTES + TorrentPlan.TAIL_BYTES)

        fun failed(fault: TorrentFault, message: String, peers: Int, release: String) =
            TorrentStatus(TorrentPhase.FAILED, fault, peers = peers, release = release, message = message)
    }
}

data class WaitText(val label: String, val headline: String, val detail: String, val stats: String, val hint: String, val failed: Boolean)

fun peerCount(n: Int): String = if (n == 1) tr("1 peer") else tr("%d peers", n)

fun waitText(s: TorrentStatus): WaitText = when (s.phase) {
    TorrentPhase.PEERS -> WaitText(
        tr("STARTING SOON"),
        if (s.peers == 1) tr("1 peer found") else if (s.peers > 0) tr("%d peers found", s.peers) else tr("Finding peers…"),
        if (s.peers > 0) tr("looking for more") else "",
        "",
        tr("Usually ready in 10–15 s"),
        false,
    )
    TorrentPhase.DOWNLOADING -> WaitText(
        tr("STARTING SOON"),
        "${s.percent}%",
        listOfNotNull(tr("of the start"), s.etaSeconds?.let { tr("about %d s", it) }).joinToString(" · "),
        tr("%1\$s/s · %2\$s · %3\$s of %4\$s", TorrentEngine.megabytes(s.rate), peerCount(s.peers), TorrentEngine.megabytes(s.have), TorrentEngine.megabytes(s.need)),
        "",
        false,
    )
    TorrentPhase.FAILED -> failedText(s)
}

private fun failedText(s: TorrentStatus): WaitText {
    val (headline, detail) = when (s.fault) {
        TorrentFault.NO_PEERS -> tr("No one is sharing this file") to tr("%s after 60 s", peerCount(s.peers))
        TorrentFault.OFF -> tr("Torrent streaming is off") to tr("Turn it on in Settings, or pick a Real-Debrid release.")
        TorrentFault.NO_SPACE -> tr("Not enough free space") to s.message.substringAfter(": ").replaceFirstChar { it.uppercase() }
        TorrentFault.STALLED -> tr("The download stalled") to tr("Try another release.")
        else -> s.message to ""
    }
    return WaitText(tr("COULDN'T START"), headline, detail, "", "", true)
}

fun bufferText(s: TorrentStatus): Pair<String, String> =
    tr("Buffering %d%%", s.bufferPercent) to tr("%1\$s/s · %2\$s", TorrentEngine.megabytes(s.rate), peerCount(s.peers))
