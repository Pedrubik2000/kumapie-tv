package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.player.LiveLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The fit before the first line (Pedro, 2026-11-28): a Matroska file's index says when each line of its own subtitle
 * track starts. A small file is written here as mkvmerge lays one out (SeekHead, Info, Tracks, a Cluster, Cues at the
 * end) and read back whole, cut short, and over HTTP ranges.
 */
class MkvIndexTest {
    private fun bytes(v: Long, n: Int) = ByteArray(n) { (v shr (8 * (n - 1 - it))).toByte() }
    private fun id(v: Long) = bytes(v, (1..4).first { v < (1L shl (8 * it)) })
    private fun el(id: Long, content: ByteArray) = id(id) + byteArrayOf(1) + bytes(content.size.toLong(), 7) + content
    private fun num(id: Long, v: Long) = el(id, bytes(v, 8))
    private fun str(id: Long, v: String) = el(id, v.toByteArray())
    private fun track(n: Long, type: Long, codec: String, name: String, lang: String) =
        el(0xAE, num(0xD7, n) + num(0x83, type) + str(0x86, codec) + str(0x536E, name) + str(0x22B59C, lang))
    private fun cue(ms: Long, track: Long) = el(0xBB, num(0xB3, ms) + el(0xB7, num(0xF7, track) + num(0xF1, 0)))

    private val lineMs = listOf(4_610L, 9_000L, 12_500L, 20_000L, 26_250L, 31_000L)
    private val signMs = (0 until 30).map { 40_000L + it * 42 }
    private val cues = el(0x1C53BB6B, (listOf(0L, 8_000L, 16_000L).map { cue(it, 1) } + lineMs.map { cue(it, 4) } + lineMs.map { cue(it, 3) } +
        signMs.map { cue(it, 4) } + cue(60_000, 4) + cue(60_000, 4)).reduce(ByteArray::plus))

    private fun file(subs: ByteArray, padding: Int = 0): ByteArray {
        val info = el(0x1549A966, num(0x2AD7B1, 1_000_000))
        val tracks = el(0x1654AE6B, track(1, 1, "V_AV1", "", "und") + track(2, 2, "A_OPUS", "", "jpn") + subs)
        val cluster = el(0x1F43B675, ByteArray(5_000))
        val void = el(0xEC, ByteArray(padding))
        fun seekHead(tracksAt: Long, cuesAt: Long) = el(0x114D9B74,
            el(0x4DBB, el(0x53AB, id(0x1654AE6B)) + num(0x53AC, tracksAt)) + el(0x4DBB, el(0x53AB, id(0x1C53BB6B)) + num(0x53AC, cuesAt)))
        val n = seekHead(0, 0).size.toLong()
        val tracksAt = n + info.size + void.size
        val segment = seekHead(tracksAt, tracksAt + tracks.size + cluster.size) + info + void + tracks + cluster + cues
        return el(0x1A45DFA3, ByteArray(20)) + el(0x18538067, segment)
    }

    private val english = track(3, 17, "S_TEXT/ASS", "Signs & Songs", "eng") + track(4, 17, "S_TEXT/ASS", "English Subs", "eng")

    @Test fun theDialogueTracksLineStarts() {
        val b = file(english)
        val h = requireNotNull(MkvIndex.head(b))
        val t = requireNotNull(MkvIndex.dialogue(requireNotNull(h.tracks)))
        assertEquals(4L, t.number)
        val at = requireNotNull(h.cues).toInt()
        val all = MkvIndex.times(b.copyOfRange(at + 12, b.size), t.number, h.scale)
        assertEquals(lineMs.size + signMs.size + 1, all.size)
        assertEquals(lineMs + 60_000L, MkvIndex.spoken(all))
        assertEquals(listOf(461L, 900L), MkvIndex.times(b.copyOfRange(at + 12, b.size), t.number, 100_000).take(2))
    }

    @Test fun aJapaneseTrackOrNoSpokenTrackMeansNothingToFit() {
        assertNull(MkvIndex.dialogue(MkvIndex.tracks(english + track(5, 17, "S_TEXT/UTF8", "", "ja"))))
        assertNull(MkvIndex.dialogue(MkvIndex.tracks(track(3, 17, "S_TEXT/ASS", "Signs & Songs", "eng"))))
        assertNull(MkvIndex.dialogue(MkvIndex.tracks(track(3, 17, "S_HDMV/PGS", "English", "eng") + track(4, 17, "S_TEXT/ASS", "English", "eng"))))
        assertNull(MkvIndex.dialogue(MkvIndex.tracks(english + track(5, 17, "S_TEXT/ASS", "Signs", "jpn"))))
    }

    @Test fun notMatroskaOrCutShort() {
        assertNull(MkvIndex.head(ByteArray(4096) { it.toByte() }))
        assertNull(MkvIndex.head(ByteArray(0)))
        val b = file(english, padding = 3_000)
        val h = requireNotNull(MkvIndex.head(b.copyOf(2_500)))
        assertNull(h.tracks)
        assertTrue(requireNotNull(h.tracksAt) > 2_500)
        assertTrue(MkvIndex.times(ByteArray(7) { 0xBB.toByte() }, 4, 1_000_000).isEmpty())
    }

    private fun serve(b: ByteArray, ranges: Boolean, test: (String, MutableList<String>) -> Unit) =
        serve(b.size.toLong(), ranges, { from, n -> b.copyOfRange(from.toInt(), from.toInt() + n) }, test)

    private fun serve(size: Long, ranges: Boolean, read: (Long, Int) -> ByteArray, test: (String, MutableList<String>) -> Unit) {
        val asked = java.util.Collections.synchronizedList(ArrayList<String>())
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v.mkv") { x ->
            val range = x.requestHeaders.getFirst("Range").orEmpty().removePrefix("bytes=")
            asked += range
            val from = if (ranges) range.substringBefore("-").toLong() else 0L
            val to = minOf(size - 1, (if (ranges) range.substringAfter("-").toLongOrNull() else null) ?: (from + 16 * 1024 * 1024))
            val part = read(from, (to - from + 1).toInt())
            x.sendResponseHeaders(if (ranges) 206 else 200, part.size.toLong())
            runCatching { x.responseBody.use { it.write(part) } }
        }
        server.start()
        try { test("http://127.0.0.1:${server.address.port}/v.mkv", asked) } finally { server.stop(0) }
    }

    @Test fun overHttpRanges() = serve(file(english, padding = 300_000), ranges = true) { url, asked ->
        assertEquals(lineMs + 60_000L, MkvIndex.starts(url))
        assertEquals(3, asked.size)
        assertEquals("0-262143", asked[0])
    }

    @Test fun aServerWithoutRangesGivesNothing() = serve(file(english, padding = 300_000), ranges = false) { url, _ ->
        assertTrue(MkvIndex.starts(url).isEmpty())
        assertTrue(MkvIndex.starts("file:///sdcard/v.mkv").isEmpty())
    }

    @Test fun theLineOnScreenTakesItsStretchsOffset() {
        val pieces = listOf(0.0 to 2.0, 180.0 to -0.5, 900.0 to null)
        assertEquals(0.0, LiveLines.pieceShift(pieces, 2.0, 60.0), 1e-9)
        assertEquals(-2.5, LiveLines.pieceShift(pieces, 2.0, 200.0), 1e-9)
        assertEquals(-2.5, LiveLines.pieceShift(pieces, 2.0, 1000.0), 1e-9)
        assertEquals(0.0, LiveLines.pieceShift(pieces, null, 200.0), 1e-9)
        assertEquals(0.0, LiveLines.pieceShift(emptyList(), 2.0, 200.0), 1e-9)
    }

    /**
     * The whole episode's line starts at once (the index) on a file whose opening is another length: 2.0 s off before
     * minute five, -0.8 s after. The first offset must be the first stretch's, as [AloneStream.moves] moves each
     * later stretch from the one before; one offset for the whole file would be the majority's (-0.8) and the lines
     * after the opening would then be moved twice.
     */
    @Test fun theFirstOffsetOfAWholeEpisodeIsItsFirstStretchs() {
        val starts = java.util.Random(17).let { r -> (0 until 320).runningFold(5.0) { t, _ -> t + 2.0 + r.nextInt(50) / 10.0 } }
        val refs = starts.map { if (it < 300) it + 2.0 else it - 0.8 }
        assertEquals(-0.8, requireNotNull(LiveLines.fit(refs, starts)), 0.05)
        val f = LiveLines.first(refs, starts, whole = true)
        assertEquals(2.0, requireNotNull(f.offset), 0.05)
        val p = requireNotNull(f.pieces)
        assertEquals(f.offset, p.first().second)
        val moved = ArrayList<Pair<Double, Double>>()
        assertEquals(2, AloneStream.moves(p, 1) { by, from -> moved += by to from })
        assertEquals(-2.8, moved.single().first, 0.05)
        assertNull(LiveLines.first(refs, starts, whole = false).pieces)
        assertEquals(-0.8, requireNotNull(LiveLines.first(refs, starts, whole = false).offset), 0.05)
        assertNull(LiveLines.first(refs, starts.map { it * 1.37 + 11 }, whole = true).offset)
    }

    /** A real file: `KUMAPIE_MKV=<path or http url> gradlew :lang:testDebugUnitTest --tests '*MkvIndexTest*'`. */
    @Test fun aRealFile() {
        val path = System.getenv("KUMAPIE_MKV").orEmpty()
        assumeTrue(path.isNotEmpty())
        if (path.startsWith("http")) return println("MkvIndex: ${MkvIndex.starts(path).size} line starts from $path")
        val f = java.io.RandomAccessFile(path, "r")
        serve(f.length(), true, { from, n -> ByteArray(n).also { f.seek(from); f.readFully(it) } }) { url, asked ->
            val starts = MkvIndex.starts(url)
            println("MkvIndex: ${starts.size} line starts, first ${starts.take(6)}, requests $asked")
            assertTrue(starts.size > 50)
        }
    }
}
