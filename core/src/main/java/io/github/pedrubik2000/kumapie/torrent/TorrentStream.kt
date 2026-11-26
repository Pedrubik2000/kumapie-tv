package io.github.pedrubik2000.kumapie.torrent

import org.libtorrent4j.Priority
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.io.IOException
import java.util.BitSet

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
    private var have = BitSet(numPieces)
    private var haveAt = 0L
    private var lastEnd = 0L
    private var windowAt = -1

    @Synchronized fun start() {
        reprioritise(0)
        for ((rank, p) in (TorrentPlan.head(first, last, pieceLen) + TorrentPlan.tail(first, last, pieceLen)).distinct().withIndex()) deadline(p, rank)
    }

    fun available(pos: Long): Long = TorrentPlan.available(offset, size, pos, pieceLen, ::has)

    @Synchronized private fun has(piece: Int): Boolean {
        if (have.get(piece)) return true
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - haveAt >= SNAPSHOT_MS) {
            val bits = handle.status(TorrentHandle.QUERY_PIECES).pieces()
            have = BitSet(numPieces).also { for (i in 0 until numPieces) if (bits.getBit(i)) it.set(i) }
            haveAt = now
        }
        return have.get(piece)
    }

    @Synchronized fun want(pos: Long) {
        val jumped = TorrentPlan.jumped(lastEnd, pos)
        lastEnd = pos
        val piece = TorrentPlan.pieceAt(offset, pos, pieceLen)
        if (!jumped && piece == windowAt) return
        if (jumped) {
            android.util.Log.i("kumapie-torrent", "seek to $pos")
            handle.clearPieceDeadlines()
            deadlined.clear()
            reprioritise(pos)
        }
        windowAt = piece
        var rank = 0
        for (p in TorrentPlan.urgent(piece, last, pieceLen)) {
            if (has(p)) continue
            deadline(p, rank)
            rank++
        }
    }

    fun haveBytes(): Long = handle.fileProgress(TorrentHandle.PIECE_GRANULARITY).getOrNull(index) ?: 0L

    private fun deadline(piece: Int, rank: Int) {
        if (!has(piece) && deadlined.add(piece)) handle.setPieceDeadline(piece, TorrentPlan.deadline(rank))
    }

    private fun reprioritise(pos: Long) {
        val plan = TorrentPlan.priorities(numPieces, first, last, TorrentPlan.pieceAt(offset, pos, pieceLen), pieceLen)
        handle.prioritizePieces(Array(numPieces) { Priority.fromSwig(plan[it]) })
    }

    private companion object {
        const val SNAPSHOT_MS = 150L
    }
}
