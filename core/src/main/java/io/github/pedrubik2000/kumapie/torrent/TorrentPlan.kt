package io.github.pedrubik2000.kumapie.torrent

object TorrentPlan {
    const val HEAD_BYTES = 4L shl 20
    const val TAIL_BYTES = 2L shl 20
    const val CAP_BYTES = 200L shl 20
    const val SMALL_CAP_BYTES = 64L shl 20
    const val MAX_CAP_BYTES = 256L shl 20
    const val LOW_FREE_BYTES = 1L shl 30
    private val VIDEO = setOf("mkv", "mp4", "m4v", "avi", "webm", "mov", "ts", "mpg", "mpeg", "wmv")

    fun pick(names: List<String>, sizes: List<Long>, wanted: Int): Int {
        if (wanted in names.indices) return wanted
        val videos = names.indices.filter { names[it].substringAfterLast('.', "").lowercase() in VIDEO }.ifEmpty { names.indices.toList() }
        return videos.maxByOrNull { sizes[it] } ?: -1
    }

    fun cap(wide: Boolean, free: Long): Long = (if (!wide || free < LOW_FREE_BYTES) SMALL_CAP_BYTES else CAP_BYTES).coerceAtMost(MAX_CAP_BYTES)

    fun retryable(failedAt: Long, now: Long, window: Long): Boolean = now - failedAt > window
}
