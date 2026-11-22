package io.github.pedrubik2000.kumapie.lang

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** [OggOpusWriter] (core plan 11c), checked by parsing its output with a reader of its own (bitwise CRC, page by page). */
class OggOpusTest {
    class Page(val flags: Int, val granule: Long, val serial: Int, val seq: Int, val lacing: List<Int>, val body: ByteArray)

    private fun crc(b: ByteArray): Int {
        var c = 0
        for (x in b) {
            c = c xor ((x.toInt() and 0xFF) shl 24)
            repeat(8) { c = if (c and Int.MIN_VALUE != 0) (c shl 1) xor 0x04C11DB7 else c shl 1 }
        }
        return c
    }

    private fun le(b: ByteArray, at: Int, n: Int) = (0 until n).fold(0L) { v, i -> v or ((b[at + i].toLong() and 0xFF) shl (8 * i)) }

    private fun pages(b: ByteArray): List<Page> {
        val out = mutableListOf<Page>()
        var at = 0
        while (at < b.size) {
            assertEquals("OggS", String(b, at, 4, Charsets.US_ASCII))
            assertEquals(0, b[at + 4].toInt())
            val n = b[at + 26].toInt() and 0xFF
            val lacing = (0 until n).map { b[at + 27 + it].toInt() and 0xFF }
            val end = at + 27 + n + lacing.sum()
            val page = b.copyOfRange(at, end)
            val stored = le(page, 22, 4).toInt()
            for (i in 22 until 26) page[i] = 0
            assertEquals("page ${out.size} CRC", stored, crc(page))
            out += Page(b[at + 5].toInt(), le(b, at + 6, 8), le(b, at + 14, 4).toInt(), le(b, at + 18, 4).toInt(), lacing, b.copyOfRange(at + 27 + n, end))
            at = end
        }
        return out
    }

    private fun packets(ps: List<Page>): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var cur = ByteArrayOutputStream()
        var open = false
        for (p in ps) {
            assertEquals(open, p.flags and 1 != 0)
            var off = 0
            for (l in p.lacing) {
                cur.write(p.body, off, l)
                off += l
                open = l == 255
                if (!open) { out += cur.toByteArray(); cur = ByteArrayOutputStream() }
            }
        }
        assertTrue("stream ends inside a packet", !open)
        return out
    }

    private fun head(preSkip: Int) = OggOpusWriter.opusHead(listOf(), 2, 48_000).also { it[10] = preSkip.toByte(); it[11] = (preSkip shr 8).toByte() }

    private fun write(packets: List<ByteArray>, samples: Long? = null): ByteArray {
        val o = ByteArrayOutputStream()
        OggOpusWriter(o, head(312)).apply { packets.forEach(::packet); finish(samples) }
        return o.toByteArray()
    }

    private fun celt20(size: Int) = ByteArray(size) { (it * 7).toByte() }.also { it[0] = (31 shl 3).toByte() } // 960 samples

    private fun checkStream(b: ByteArray, sent: List<ByteArray>): List<Page> {
        val ps = pages(b)
        ps.forEachIndexed { i, p -> assertEquals(i, p.seq); assertEquals(ps[0].serial, p.serial) }
        assertEquals(listOf(2) + List(ps.size - 2) { 0 } + 4, ps.map { it.flags and 6 })
        val all = packets(ps)
        assertEquals("OpusHead", String(all[0], 0, 8))
        assertEquals("OpusTags", String(all[1], 0, 8))
        assertEquals(listOf(0L, 0L), ps.take(2).map { it.granule })
        assertEquals(listOf(1, 1), ps.take(2).map { it.lacing.size })
        sent.forEachIndexed { i, p -> assertArrayEquals(p, all[i + 2]) }
        assertEquals(sent.size + 2, all.size)
        val g = ps.drop(2).map { it.granule }.filter { it >= 0 }
        assertEquals(g.sorted(), g)
        return ps
    }

    @Test fun smallStream() {
        val sent = List(10) { celt20(80) }
        val ps = checkStream(write(sent, 9_000), sent)
        assertEquals(3, ps.size)
        assertEquals(312 + 9_000L, ps.last().granule)
    }

    @Test fun untrimmed() {
        val sent = List(10) { celt20(80) }
        assertEquals(10 * 960L, checkStream(write(sent), sent).last().granule)
    }

    @Test fun manyPacketsSplitAcrossPages() {
        val sent = List(600) { celt20(40) }
        val ps = checkStream(write(sent), sent)
        assertTrue(ps.size >= 5)
        assertEquals(255 * 960L, ps[2].granule)
        assertEquals(600 * 960L, ps.last().granule)
    }

    @Test fun packetLargerThanAPage() {
        val sent = listOf(celt20(100), celt20(140_000), celt20(100))
        val ps = checkStream(write(sent), sent)
        assertEquals(960L, ps[2].granule)
        assertEquals(-1L, ps[3].granule)
        assertEquals(List(255) { 255 }, ps[3].lacing)
        assertTrue(ps[3].flags and 1 != 0 && ps[4].flags and 1 != 0)
    }

    @Test fun packetOfExactly255n() {
        val sent = listOf(celt20(510), celt20(255 * 252), celt20(10))
        val ps = checkStream(write(sent), sent)
        assertEquals(listOf(255, 255, 0), ps[2].lacing.take(3))
        assertEquals(255, ps[2].lacing.size)
        assertEquals(0, ps[3].lacing.first())
        assertTrue(ps[3].flags and 1 != 0)
    }

    @Test(expected = IllegalStateException::class)
    fun emptyStream() = OggOpusWriter(ByteArrayOutputStream(), head(312)).finish()

    @Test fun trimNeverBelowAnEarlierPage() {
        val sent = List(300) { celt20(40) }
        val ps = checkStream(write(sent, 1), sent)
        assertEquals(ps[ps.size - 2].granule, ps.last().granule)
    }

    @Test fun samplesFromToc() {
        assertEquals(2880, OggOpusWriter.opusSamples(byteArrayOf((3 shl 3).toByte())))
        assertEquals(960, OggOpusWriter.opusSamples(byteArrayOf(((12 shl 3) or 1).toByte())))
        assertEquals(360, OggOpusWriter.opusSamples(byteArrayOf(((16 shl 3) or 3).toByte(), 3)))
        assertEquals(0, OggOpusWriter.opusSamples(byteArrayOf()))
    }

    @Test fun headFromCodecConfig() {
        val android = head(0).copyOf().also { it[9] = 1 }
        val delay = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(6_500_000).array()
        val h = OggOpusWriter.opusHead(listOf(android, delay), 2, 44_100)
        assertEquals(19, h.size)
        assertEquals(1, h[9].toInt())
        assertEquals(312L, le(h, 10, 2))
        assertEquals(48_000L, le(h, 12, 4))
        assertEquals(356L, le(OggOpusWriter.opusHead(listOf(head(356), delay), 2, 48_000), 10, 2))
        assertEquals(44_100L, le(OggOpusWriter.opusHead(listOf(), 1, 44_100), 12, 4))
    }

    @Test(expected = IllegalArgumentException::class)
    fun surroundRefused() { OggOpusWriter.opusHead(listOf(), 6, 48_000) }

    /** ffprobe check by hand: `OGG_OUT=C:\...\t.ogg gradlew :lang:testDebugUnitTest --tests '*OggOpusTest*'`, then ffprobe it. */
    @Test fun writeForFfprobe() {
        val path = System.getenv("OGG_OUT") ?: return
        val silence = ByteArray(3) { 0 }.also { it[0] = (31 shl 3).toByte(); it[1] = 0xFF.toByte(); it[2] = 0xFE.toByte() }
        File(path).writeBytes(write(List(150) { silence }, 48_000L * 5 / 2))
    }

    /** A file a device wrote (`OGG_IN=<dir of .ogg>`): every page and packet through this reader; prints each file's numbers. */
    @Test fun readDeviceFiles() {
        val dir = System.getenv("OGG_IN") ?: return
        val files = File(dir).listFiles { f -> f.extension == "ogg" }.orEmpty()
        assertTrue("no .ogg in $dir", files.isNotEmpty())
        for (f in files) {
            val ps = pages(f.readBytes())
            assertEquals(listOf(2) + List(ps.size - 2) { 0 } + 4, ps.map { it.flags and 6 })
            ps.forEachIndexed { i, p -> assertEquals(i, p.seq) }
            val all = packets(ps)
            val samples = all.drop(2).sumOf { OggOpusWriter.opusSamples(it).toLong() }
            val skip = le(all[0], 10, 2)
            assertTrue(ps.last().granule in skip..samples)
            println("${f.name}: ${ps.size} pages, ${all.size - 2} packets, channels ${all[0][9]}, pre-skip $skip, granule ${ps.last().granule} of $samples")
        }
    }
}
