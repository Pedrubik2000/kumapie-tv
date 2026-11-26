package io.github.pedrubik2000.kumapie.data

import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset

object BodyText {
    const val LIMIT = 2 shl 20
    private const val CHUNK = 8192

    val big: Int get() = if (Playable.narrow) 8 shl 20 else 64 shl 20

    fun text(stream: InputStream, max: Int = LIMIT, charset: Charset = Charsets.UTF_8): String {
        val out = StringBuilder()
        val reader = InputStreamReader(CountingStream(stream, max), charset)
        val buffer = CharArray(CHUNK)
        while (true) {
            val n = reader.read(buffer)
            if (n < 0) return out.toString()
            out.append(buffer, 0, n)
        }
    }

    fun text(body: ByteArray, max: Int = big): String = text(java.io.ByteArrayInputStream(body), max)

    fun bytes(stream: InputStream, max: Int = LIMIT): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(CHUNK)
        var total = 0
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) return out.toByteArray()
            total += n
            if (total > max) throw IOException("Answer over $max bytes")
            out.write(buffer, 0, n)
        }
    }

    private class CountingStream(private val inner: InputStream, private val max: Int) : InputStream() {
        private var total = 0

        override fun read(): Int = inner.read().also { if (it >= 0) count(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count(it) }

        private fun count(n: Int) {
            total += n
            if (total > max) throw IOException("Answer over $max bytes")
        }
    }
}

object Playing {
    @Volatile var on = false

    fun holds(narrow: Boolean = Playable.narrow, playing: Boolean = on): Boolean = narrow && playing
}
