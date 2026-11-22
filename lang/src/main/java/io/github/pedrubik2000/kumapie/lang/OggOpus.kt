package io.github.pedrubik2000.kumapie.lang

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Opus packets into an Ogg Opus stream (RFC 7845 over RFC 3533): an OpusHead page, an OpusTags page, then the audio,
 * granule positions in 48 kHz samples (a packet's from its TOC byte, RFC 6716 3.1). Android's own Ogg writer (MediaMuxer)
 * aborted the app (core plan 11c). [finish] with the clip's length trims the last packet's padding; no packet = an error.
 * [opusHead]: a fresh OpusHead from the encoder's csd-0, its pre-skip or, when that says 0 (Android's encoder), csd-1's
 * delay in ns.
 * ponytail: one logical stream, mono/stereo only (mapping family 0), no cap on a page's duration (255 segments, about
 * 5 s of a 16 kbps clip); a seek table or surround needs pages flushed by time and a channel mapping table.
 */
class OggOpusWriter(private val out: OutputStream, head: ByteArray, private val serial: Int = 0x6B756D61) {
    private val preSkip = (head[10].toInt() and 0xFF) or ((head[11].toInt() and 0xFF) shl 8)
    private val lacing = ArrayList<Int>()
    private val body = ByteArrayOutputStream()
    private var seq = 0
    private var granule = 0L
    private var pageGranule = -1L
    private var lastGranule = 0L
    private var continued = false
    private var inPacket = false
    private var packets = 0

    init {
        put(head)
        flush(BOS)
        put(TAGS)
        flush(0)
    }

    fun packet(p: ByteArray) {
        granule += opusSamples(p)
        packets++
        put(p)
    }

    fun finish(samples: Long? = null) {
        check(packets > 0) { "no audio" }
        pageGranule = if (samples == null) granule else (preSkip + samples).coerceIn(lastGranule, granule)
        flush(EOS)
        out.flush()
    }

    private fun put(p: ByteArray) {
        var off = 0
        while (true) {
            if (lacing.size == MAX_SEGMENTS) flush(0)
            val n = minOf(SEGMENT, p.size - off)
            lacing += n
            body.write(p, off, n)
            off += n
            inPacket = n == SEGMENT
            if (!inPacket) break
        }
        pageGranule = granule
    }

    private fun flush(flags: Int) {
        val page = ByteArrayOutputStream()
        page.write("OggS".toByteArray())
        page.write(0)
        page.write(flags or if (continued) CONTINUED else 0)
        for (i in 0 until 8) page.write((pageGranule shr (8 * i)).toInt() and 0xFF)
        le32(page, serial)
        le32(page, seq++)
        le32(page, 0)
        page.write(lacing.size)
        lacing.forEach(page::write)
        body.writeTo(page)
        val bytes = page.toByteArray()
        val crc = oggCrc(bytes)
        for (i in 0 until 4) bytes[22 + i] = (crc shr (8 * i)).toByte()
        out.write(bytes)
        if (pageGranule >= 0) lastGranule = pageGranule
        continued = inPacket
        pageGranule = -1
        lacing.clear()
        body.reset()
    }

    companion object {
        private const val SEGMENT = 255
        private const val MAX_SEGMENTS = 255
        private const val CONTINUED = 1
        private const val BOS = 2
        private const val EOS = 4
        private val TAGS = "OpusTags".toByteArray() + byteArrayOf(7, 0, 0, 0) + "kumapie".toByteArray() + byteArrayOf(0, 0, 0, 0)
        private val CRC = IntArray(256) { i ->
            var r = i shl 24
            repeat(8) { r = if (r < 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
            r
        }

        fun oggCrc(b: ByteArray): Int = b.fold(0) { c, x -> (c shl 8) xor CRC[((c ushr 24) xor (x.toInt() and 0xFF)) and 0xFF] }

        private fun le32(o: OutputStream, v: Int) = (0 until 4).forEach { o.write((v shr (8 * it)) and 0xFF) }

        fun opusSamples(p: ByteArray): Int {
            if (p.isEmpty()) return 0
            val config = (p[0].toInt() and 0xFF) shr 3
            val frame = when {
                config < 12 -> intArrayOf(480, 960, 1920, 2880)[config % 4]
                config < 16 -> intArrayOf(480, 960)[config % 2]
                else -> intArrayOf(120, 240, 480, 960)[config % 4]
            }
            val frames = when (p[0].toInt() and 3) {
                0 -> 1
                3 -> if (p.size > 1) p[1].toInt() and 0x3F else 0
                else -> 2
            }
            return frame * frames
        }

        fun opusHead(csd: List<ByteArray>, channels: Int, rate: Int): ByteArray {
            val h = csd.firstOrNull()?.let { c -> (0..c.size - 19).firstOrNull { String(c, it, 8, Charsets.US_ASCII) == "OpusHead" }?.let { c.copyOfRange(it, it + 19) } }
            fun u(at: Int, n: Int) = h?.let { (0 until n).fold(0L) { v, i -> v or ((it[at + i].toLong() and 0xFF) shl (8 * i)) } } ?: 0L
            val ch = h?.let { it[9].toInt() } ?: channels
            require(ch in 1..2) { "Ogg Opus: $ch channels" }
            val delay = csd.getOrNull(1)?.takeIf { it.size == 8 }?.let { d -> (0 until 8).fold(0L) { v, i -> v or ((d[i].toLong() and 0xFF) shl (8 * i)) } * 48_000 / 1_000_000_000 } ?: 0L
            val skip = u(10, 2).takeIf { it > 0 } ?: delay
            val inRate = u(12, 4).takeIf { it > 0 } ?: rate.toLong()
            val o = ByteArrayOutputStream()
            o.write("OpusHead".toByteArray())
            o.write(1)
            o.write(ch)
            o.write(skip.toInt() and 0xFF)
            o.write((skip.toInt() shr 8) and 0xFF)
            le32(o, inRate.toInt())
            o.write(byteArrayOf(0, 0, 0))
            return o.toByteArray()
        }
    }
}
