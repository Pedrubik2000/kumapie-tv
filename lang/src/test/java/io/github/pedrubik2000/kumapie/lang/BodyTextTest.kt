package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.BodyText
import io.github.pedrubik2000.kumapie.data.Playing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class BodyTextTest {
    private class Trickle(private val data: ByteArray, private val step: Int) : InputStream() {
        private var at = 0
        override fun read(): Int = if (at < data.size) data[at++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (at >= data.size) return -1
            val n = minOf(step, len, data.size - at)
            System.arraycopy(data, at, b, off, n)
            at += n
            return n
        }
    }

    @Test fun multibyteCharactersSplitAcrossReadsComeOutWhole() {
        val text = "日本語のテキスト \u00E9\u266A \uD83C\uDF8C".repeat(3000)
        for (step in listOf(1, 2, 3, 5, 7)) assertEquals(text, BodyText.text(Trickle(text.toByteArray(), step), max = 1 shl 20))
    }

    @Test fun overTheCapThrowsAClearIOException() {
        val e = assertThrows(IOException::class.java) { BodyText.text(ByteArrayInputStream(ByteArray(101) { 'a'.code.toByte() }), max = 100) }
        assertTrue(e.message!!.contains("100"))
        assertThrows(IOException::class.java) { BodyText.bytes(ByteArrayInputStream(ByteArray(101)), max = 100) }
    }

    @Test fun exactlyTheCapPasses() {
        assertEquals(100, BodyText.text(ByteArrayInputStream(ByteArray(100) { 'a'.code.toByte() }), max = 100).length)
        assertEquals(100, BodyText.bytes(ByteArrayInputStream(ByteArray(100)), max = 100).size)
    }

    @Test fun aUtf16BomIsDecodedWithItsCharset() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "字幕".toByteArray(Charsets.UTF_16LE)
        assertEquals("字幕", BodyText.text(ByteArrayInputStream(bytes), charset = Charsets.UTF_16))
    }

    @Test fun aUtf8BomStaysForTheCallerToStrip() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "abc".toByteArray()
        assertEquals("\uFEFFabc", BodyText.text(ByteArrayInputStream(bytes)))
    }

    @Test fun badBytesBecomeReplacementCharactersLikeString() {
        assertEquals(String(byteArrayOf(0x82.toByte(), 0xA0.toByte()), Charsets.UTF_8), BodyText.text(ByteArrayInputStream(byteArrayOf(0x82.toByte(), 0xA0.toByte()))))
    }

    @Test fun heavyWorkWaitsOnlyForAVideoOnANarrowDevice() {
        assertTrue(Playing.holds(narrow = true, playing = true))
        assertFalse(Playing.holds(narrow = true, playing = false))
        assertFalse(Playing.holds(narrow = false, playing = true))
    }
}
