package io.github.pedrubik2000.kumapie.data

import io.github.pedrubik2000.kumapie.i18n.tr
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
            if (total > max) throw TooBig()
            out.write(buffer, 0, n)
        }
    }

    class TooBig : IOException(tr("The answer was too big to load"))

    private class CountingStream(private val inner: InputStream, private val max: Int) : InputStream() {
        private var total = 0

        override fun read(): Int = inner.read().also { if (it >= 0) count(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count(it) }

        private fun count(n: Int) {
            total += n
            if (total > max) throw TooBig()
        }
    }
}

object Playing {
    private val count = java.util.concurrent.atomic.AtomicInteger()
    private val later = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    fun later(key: String, action: () -> Unit) { later[key] = action }

    var on: Boolean
        get() = count.get() > 0
        set(value) {
            if (value) count.incrementAndGet()
            else if (count.updateAndGet { maxOf(0, it - 1) } == 0) later.keys.toList().forEach { later.remove(it)?.let { run -> runCatching(run) } }
        }

    fun holds(narrow: Boolean = Playable.narrow, playing: Boolean = on): Boolean = narrow && playing
}
