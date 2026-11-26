package io.github.pedrubik2000.kumapie.torrent

import org.libtorrent4j.Priority
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.io.IOException

class TorrentException(message: String) : IOException(message)

class TorrentStream(val ref: TorrentRef, val handle: TorrentHandle, info: TorrentInfo, val index: Int, root: File) {
    private val pieceLen = info.pieceLength()
    private val numPieces = info.numPieces()
    private val offset = info.files().fileOffset(index)
    val size = info.files().fileSize(index)
    val path = File(root, info.files().filePath(index))
    private val first = TorrentPlan.firstPiece(offset, pieceLen)
    private val last = TorrentPlan.lastPiece(offset, size, pieceLen)
    private val deadlined = HashSet<Int>()
    private var lastEnd = 0L

    @Synchronized fun start() {
        reprioritise(0)
        for ((rank, p) in (TorrentPlan.head(first, last, pieceLen) + TorrentPlan.tail(first, last, pieceLen)).distinct().withIndex()) deadline(p, rank)
    }

    fun available(pos: Long): Long = TorrentPlan.available(offset, size, pos, pieceLen) { handle.havePiece(it) }

    @Synchronized fun want(pos: Long) {
        if (TorrentPlan.jumped(lastEnd, pos)) {
            handle.clearPieceDeadlines()
            deadlined.clear()
            reprioritise(pos)
        }
        lastEnd = pos
        var rank = 0
        for (p in TorrentPlan.ahead(TorrentPlan.pieceAt(offset, pos, pieceLen), last, pieceLen)) {
            if (handle.havePiece(p)) continue
            deadline(p, rank)
            rank++
        }
    }

    fun haveBytes(): Long = handle.fileProgress(TorrentHandle.PIECE_GRANULARITY).getOrNull(index) ?: 0L

    private fun deadline(piece: Int, rank: Int) {
        if (!handle.havePiece(piece) && deadlined.add(piece)) handle.setPieceDeadline(piece, TorrentPlan.deadline(rank))
    }

    private fun reprioritise(pos: Long) {
        val plan = TorrentPlan.priorities(numPieces, first, last, TorrentPlan.pieceAt(offset, pos, pieceLen), pieceLen)
        handle.prioritizePieces(Array(numPieces) { Priority.fromSwig(plan[it]) })
    }
}
