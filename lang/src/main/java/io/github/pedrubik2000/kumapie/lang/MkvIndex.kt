package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.player.LiveLines
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * When the lines of a Matroska video's own subtitle track start, read from the file's index (Cues) alone: two or three
 * HTTP Range requests (the head, the Cues element; a few MB of a file of GBs), nothing downloaded, nothing played.
 * mkvmerge writes a Cues entry for every subtitle block, so the whole episode's line starts are known before the
 * first line is spoken and [LiveLines.fit] doesn't wait to hear them (Pedro, 2026-11-28: "annoying to have to wait 6
 * lines to get good subtitles"). Empty when the file can't say: not Matroska, no Cues for its subtitle track (some
 * muxers index the video only), a Japanese track (its own lines are shown, nothing to fit), a server without Range.
 * Times are whole milliseconds cut as ExoPlayer cuts them, so a line heard later is the same one, not a second one.
 * ponytail: text tracks only (S_TEXT/...): a picture track (PGS) has a block where a line ends too. Add when a file needs it.
 * ponytail: the first offset is the first [LiveLines.pieces] stretch's (240 s windows): a cold open of under 8 lines
 * timed apart from the rest takes the rest's offset. A fit on the earliest lines alone, as a stretch of its own, if a file needs it.
 */
object MkvIndex {
    class Track(val number: Long, val type: Long, val codec: String, val name: String, val language: String, val forced: Boolean)

    /** [cues] / [tracksAt]: where those elements start in the file (null: not in the SeekHead); [tracks]: already in the head read. */
    class Head(val cues: Long?, val tracksAt: Long?, val scale: Long, val tracks: List<Track>?)

    private class El(val id: Long, val from: Int, val to: Int, val whole: Boolean)

    /** An EBML number at [i]: (value, next index); [keep] the length marker (element ids). Null: cut off. */
    private fun vint(b: ByteArray, i: Int, keep: Boolean): Pair<Long, Int>? {
        if (i >= b.size) return null
        val first = b[i].toInt() and 0xFF
        val n = (1..8).firstOrNull { first and (0x80 shr (it - 1)) != 0 } ?: return null
        if (i + n > b.size) return null
        var v = (if (keep) first else first and (0xFF shr n)).toLong()
        for (k in 1 until n) v = (v shl 8) or (b[i + k].toLong() and 0xFF)
        val unknown = !keep && v == (1L shl (7 * n)) - 1
        return (if (unknown) Long.MAX_VALUE else v) to i + n
    }

    /** The elements of [b] from [from] to [to]; one that runs past [to] comes cut ([El.whole] false) and ends the walk. */
    private fun elements(b: ByteArray, from: Int, to: Int): List<El> {
        val out = ArrayList<El>()
        var i = from
        while (i < to) {
            val (id, j) = vint(b, i, true) ?: break
            val (size, data) = vint(b, j, false) ?: break
            if (data > to) break
            val end = if (size > to - data) Long.MAX_VALUE else data + size
            out += El(id, data, minOf(end, to.toLong()).toInt(), end <= to)
            if (end > to) break
            i = end.toInt()
        }
        return out
    }

    private fun number(b: ByteArray, e: El): Long = (e.from until e.to).fold(0L) { v, i -> (v shl 8) or (b[i].toLong() and 0xFF) }

    private fun text(b: ByteArray, e: El) = String(b, e.from, e.to - e.from, Charsets.UTF_8).trimEnd('\u0000')

    /** The start of a Matroska file ([b], its first bytes): null when it isn't one. */
    fun head(b: ByteArray): Head? {
        val top = elements(b, 0, b.size)
        if (top.firstOrNull()?.id != EBML) return null
        val segment = top.getOrNull(1)?.takeIf { it.id == SEGMENT } ?: return null
        var cues: Long? = null
        var tracksAt: Long? = null
        var scale = 1_000_000L
        var tracks: List<Track>? = null
        for (e in elements(b, segment.from, b.size)) when (e.id) {
            SEEK_HEAD -> if (e.whole) for (seek in elements(b, e.from, e.to).filter { it.id == SEEK }) {
                val parts = elements(b, seek.from, seek.to)
                val at = parts.firstOrNull { it.id == SEEK_POSITION }?.let { segment.from + number(b, it) } ?: continue
                when (parts.firstOrNull { it.id == SEEK_ID }?.let { number(b, it) }) { CUES -> cues = at; TRACKS -> tracksAt = at }
            }
            INFO -> if (e.whole) elements(b, e.from, e.to).firstOrNull { it.id == TIMESTAMP_SCALE }?.let { scale = number(b, it) }
            TRACKS -> if (e.whole) tracks = tracks(b, e.from, e.to)
            CLUSTER -> break
        }
        return Head(cues, tracksAt, scale, tracks)
    }

    /** The entries of a Tracks element's content. */
    fun tracks(b: ByteArray, from: Int = 0, to: Int = b.size): List<Track> = elements(b, from, to).filter { it.id == TRACK_ENTRY && it.whole }.map { t ->
        val parts = elements(b, t.from, t.to)
        fun one(id: Long) = parts.firstOrNull { it.id == id }
        Track(one(TRACK_NUMBER)?.let { number(b, it) } ?: 0, one(TRACK_TYPE)?.let { number(b, it) } ?: 0, one(CODEC)?.let { text(b, it) }.orEmpty(),
            one(NAME)?.let { text(b, it) }.orEmpty(), (one(LANGUAGE_BCP47) ?: one(LANGUAGE))?.let { text(b, it) } ?: "eng", one(FORCED)?.let { number(b, it) } == 1L)
    }

    /**
     * The track whose line starts to fit to, the one [LiveLines.dialogue] makes the player read: the first subtitle
     * track that isn't forced or signs/songs. Null when there is none, when it isn't text (the player would hear
     * another track's lines than the index gave), or when the file has a Japanese track (its own lines are shown).
     */
    fun dialogue(tracks: List<Track>): Track? {
        val subs = tracks.filter { it.type == SUBTITLES }
        if (subs.any { it.language.startsWith("ja") || it.language == "jpn" }) return null
        return subs.firstOrNull { !it.forced && !LiveLines.notDialogue(it.name) }?.takeIf { it.codec.startsWith("S_TEXT") }
    }

    /** The times (ms, ascending, each once) of the Cues content [b]'s entries for [track]. */
    fun times(b: ByteArray, track: Long, scale: Long): List<Long> = elements(b, 0, b.size).filter { it.id == CUE_POINT && it.whole }.mapNotNull { p ->
        val parts = elements(b, p.from, p.to)
        val time = parts.firstOrNull { it.id == CUE_TIME }?.let { number(b, it) } ?: return@mapNotNull null
        time.takeIf { parts.any { pos -> pos.id == CUE_POSITIONS && elements(b, pos.from, pos.to).any { it.id == CUE_TRACK && number(b, it) == track } } }
    }.map { it * scale / 1_000_000 }.toSortedSet().toList()

    /**
     * Without the typesetting: a sign that moves is a block per frame, hundreds of "lines" a few 1/100 s apart, which
     * drown the spoken ones in [LiveLines.fit]'s count. A run of [RUN] or more starts under [FRAME] s apart goes whole.
     */
    fun spoken(times: List<Long>): List<Long> {
        val out = ArrayList<Long>()
        var i = 0
        while (i < times.size) {
            var j = i
            while (j + 1 < times.size && times[j + 1] - times[j] < FRAME) j++
            if (j - i + 1 < RUN) out += times.subList(i, j + 1)
            i = j + 1
        }
        return out
    }

    /** The line starts (ms) of [url]'s dialogue track (see the class); empty when the file can't say. Blocking: call off the main thread. */
    fun starts(url: String): List<Long> = runCatching {
        if (!url.startsWith("http")) return emptyList()
        val h = head(read(url, 0, HEAD) ?: return emptyList()) ?: return emptyList()
        val tracks = h.tracks ?: element(url, h.tracksAt ?: return emptyList(), MAX_TRACKS)?.let { tracks(it) } ?: return emptyList()
        val track = dialogue(tracks) ?: return emptyList()
        spoken(times(element(url, h.cues ?: return emptyList(), MAX_CUES) ?: return emptyList(), track.number, h.scale))
    }.getOrDefault(emptyList())

    /** The content of the element that starts at byte [at] of [url]; null over [max] bytes or cut off. */
    private fun element(url: String, at: Long, max: Int): ByteArray? = open(url, at, null)?.use { s ->
        val start = ByteArray(12)
        val got = fill(s, start)
        val (_, j) = vint(start, 0, true) ?: return null
        val (size, data) = vint(start, j, false) ?: return null
        if (size > max || data > got) return null
        val out = ByteArray(size.toInt())
        val kept = got - data
        System.arraycopy(start, data, out, 0, minOf(kept, out.size))
        if (out.size > kept && fill(s, out, kept) < out.size) return null
        out
    }

    private fun read(url: String, at: Long, n: Int): ByteArray? = open(url, at, n)?.use { s -> ByteArray(n).let { it.copyOf(fill(s, it)) } }

    private fun fill(s: InputStream, b: ByteArray, from: Int = 0): Int {
        var n = from
        while (n < b.size) { val r = s.read(b, n, b.size - n); if (r < 0) break; n += r }
        return n
    }

    /** [url] from byte [at] ([n] bytes, or to the end: the caller stops reading). Null unless the server answers the range (206; 200 only from 0). */
    private fun open(url: String, at: Long, n: Int?): InputStream? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT; readTimeout = TIMEOUT
            setRequestProperty("Range", "bytes=$at-" + (n?.let { at + it - 1 } ?: ""))
        }
        val code = runCatching { c.responseCode }.getOrElse { c.disconnect(); throw it }
        if (code != HttpURLConnection.HTTP_PARTIAL && !(code == HttpURLConnection.HTTP_OK && at == 0L)) { c.disconnect(); return null }
        return object : java.io.FilterInputStream(c.inputStream) { override fun close() { runCatching { super.close() }; c.disconnect() } }
    }

    private const val EBML = 0x1A45DFA3L
    private const val SEGMENT = 0x18538067L
    private const val SEEK_HEAD = 0x114D9B74L
    private const val SEEK = 0x4DBBL
    private const val SEEK_ID = 0x53ABL
    private const val SEEK_POSITION = 0x53ACL
    private const val INFO = 0x1549A966L
    private const val TIMESTAMP_SCALE = 0x2AD7B1L
    private const val TRACKS = 0x1654AE6BL
    private const val TRACK_ENTRY = 0xAEL
    private const val TRACK_NUMBER = 0xD7L
    private const val TRACK_TYPE = 0x83L
    private const val CODEC = 0x86L
    private const val NAME = 0x536EL
    private const val LANGUAGE = 0x22B59CL
    private const val LANGUAGE_BCP47 = 0x22B59DL
    private const val FORCED = 0x55AAL
    private const val CLUSTER = 0x1F43B675L
    private const val CUES = 0x1C53BB6BL
    private const val CUE_POINT = 0xBBL
    private const val CUE_TIME = 0xB3L
    private const val CUE_POSITIONS = 0xB7L
    private const val CUE_TRACK = 0xF7L
    private const val SUBTITLES = 17L
    private const val HEAD = 256 * 1024
    private const val MAX_TRACKS = 1024 * 1024
    private const val MAX_CUES = 8 * 1024 * 1024
    private const val TIMEOUT = 8_000
    private const val RUN = 4
    private const val FRAME = 120L
}
