package io.github.pedrubik2000.kumapie.torrent

object TorrentPlan {
    const val HEAD_BYTES = 4L shl 20
    const val TAIL_BYTES = 2L shl 20
    const val AHEAD_BYTES = 8L shl 20
    const val URGENT_BYTES = 2L shl 20
    const val IGNORE = 0
    const val NORMAL = 4
    const val TOP = 7
    private val VIDEO = setOf("mkv", "mp4", "m4v", "avi", "webm", "mov", "ts", "mpg", "mpeg", "wmv")

    fun pick(names: List<String>, sizes: List<Long>, wanted: Int): Int {
        if (wanted in names.indices) return wanted
        val videos = names.indices.filter { names[it].substringAfterLast('.', "").lowercase() in VIDEO }.ifEmpty { names.indices.toList() }
        return videos.maxByOrNull { sizes[it] } ?: -1
    }

    fun firstPiece(offset: Long, pieceLen: Int): Int = (offset / pieceLen).toInt()

    fun lastPiece(offset: Long, size: Long, pieceLen: Int): Int = ((offset + size - 1) / pieceLen).toInt()

    fun pieceAt(offset: Long, pos: Long, pieceLen: Int): Int = ((offset + pos) / pieceLen).toInt()

    private fun span(bytes: Long, pieceLen: Int): Int = ((bytes + pieceLen - 1) / pieceLen).toInt().coerceAtLeast(1)

    fun head(first: Int, last: Int, pieceLen: Int): IntRange = first..minOf(last, first + span(HEAD_BYTES, pieceLen) - 1)

    fun tail(first: Int, last: Int, pieceLen: Int): IntRange = maxOf(first, last - span(TAIL_BYTES, pieceLen) + 1)..last

    fun ahead(piece: Int, last: Int, pieceLen: Int): IntRange = piece..minOf(last, piece + span(AHEAD_BYTES, pieceLen) - 1)

    fun urgent(piece: Int, last: Int, pieceLen: Int): IntRange = piece..minOf(last, piece + span(URGENT_BYTES, pieceLen) - 1)

    fun priorities(numPieces: Int, first: Int, last: Int, pivot: Int, pieceLen: Int): IntArray {
        val out = IntArray(numPieces)
        for (p in first..last) out[p] = if (p < pivot) IGNORE else NORMAL
        for (p in head(first, last, pieceLen)) out[p] = TOP
        for (p in tail(first, last, pieceLen)) out[p] = TOP
        for (p in ahead(pivot.coerceIn(first, last), last, pieceLen)) out[p] = TOP
        return out
    }

    fun deadline(rank: Int): Int = 300 + rank * 400

    fun available(offset: Long, size: Long, pos: Long, pieceLen: Int, have: (Int) -> Boolean): Long {
        if (pos >= size) return 0
        val last = lastPiece(offset, size, pieceLen)
        var p = pieceAt(offset, pos, pieceLen)
        if (!have(p)) return 0
        while (p < last && have(p + 1)) p++
        return minOf((p + 1).toLong() * pieceLen, offset + size) - (offset + pos)
    }

    fun jumped(lastEnd: Long, pos: Long): Boolean = pos < lastEnd - AHEAD_BYTES / 4 || pos > lastEnd + AHEAD_BYTES / 2
}
