package io.github.pedrubik2000.kumapie.lang

import java.util.Locale

object Tombstone {
    class Text(val all: String, val crashing: String)

    private class Thread(val id: Long, val name: String, val frames: List<String>)

    private fun walk(b: ByteArray, from: Int, to: Int, each: (num: Int, wire: Int, value: Long, start: Int, end: Int) -> Unit) {
        var p = from
        fun varint(): Long {
            var v = 0L
            var shift = 0
            while (true) {
                check(p < to && shift < 64)
                val x = b[p++].toInt()
                v = v or ((x and 0x7f).toLong() shl shift)
                if (x and 0x80 == 0) return v
                shift += 7
            }
        }
        while (p < to) {
            val tag = varint()
            val num = (tag ushr 3).toInt()
            when (val wire = (tag and 7).toInt()) {
                0 -> each(num, wire, varint(), p, p)
                1 -> { check(p + 8 <= to); p += 8 }
                5 -> { check(p + 4 <= to); p += 4 }
                2 -> {
                    val len = varint()
                    check(len >= 0)
                    val end = p + minOf(len, (to - p).toLong()).toInt()
                    each(num, wire, 0, p, end)
                    p = end
                }
                else -> error("wire $wire")
            }
        }
    }

    private fun str(b: ByteArray, s: Int, e: Int) = String(b, s, e - s, Charsets.UTF_8)

    private fun frame(b: ByteArray, s: Int, e: Int, n: Int): String {
        var pc = 0L
        var fn = ""
        var off = 0L
        var file = ""
        walk(b, s, e) { num, wire, v, a, z ->
            when {
                num == 1 && wire == 0 -> pc = v
                num == 4 && wire == 2 -> fn = str(b, a, z)
                num == 5 && wire == 0 -> off = v
                num == 6 && wire == 2 -> file = str(b, a, z)
            }
        }
        return String.format(Locale.ROOT, "#%02d pc %016x  %s", n, pc, file) + if (fn.isEmpty()) "" else " ($fn+$off)"
    }

    private fun thread(b: ByteArray, s: Int, e: Int): Thread? {
        var value: Pair<Int, Int>? = null
        walk(b, s, e) { num, wire, _, a, z -> if (num == 2 && wire == 2) value = a to z }
        val (from, to) = value ?: return null
        var id = 0L
        var name = ""
        val frames = mutableListOf<String>()
        runCatching {
            walk(b, from, to) { num, wire, v, a, z ->
                when {
                    num == 1 && wire == 0 -> id = v
                    num == 2 && wire == 2 -> name = str(b, a, z)
                    num == 4 && wire == 2 -> frames += frame(b, a, z, frames.size)
                }
            }
        }
        return Thread(id, name, frames)
    }

    private fun signal(b: ByteArray, s: Int, e: Int): String {
        var number = 0L
        var name = ""
        var code = ""
        walk(b, s, e) { num, wire, v, a, z ->
            when {
                num == 1 && wire == 0 -> number = v
                num == 2 && wire == 2 -> name = str(b, a, z)
                num == 4 && wire == 2 -> code = str(b, a, z)
            }
        }
        return "signal $number ($name)" + if (code.isEmpty()) "" else ", $code"
    }

    fun parse(b: ByteArray): Text? {
        var tid = -1L
        var signal = ""
        var abort = ""
        val threads = mutableListOf<Thread>()
        runCatching {
            walk(b, 0, b.size) { num, wire, v, s, e ->
                when {
                    num == 6 && wire == 0 -> tid = v
                    num == 10 && wire == 2 -> signal = runCatching { signal(b, s, e) }.getOrDefault("")
                    num == 14 && wire == 2 -> abort = str(b, s, e)
                    num == 16 && wire == 2 -> thread(b, s, e)?.let { threads += it }
                }
            }
        }
        if (signal.isEmpty() && threads.isEmpty()) return null
        val head = listOfNotNull(abort.takeIf { it.isNotEmpty() }?.let { "Abort message: '$it'" }, signal.takeIf { it.isNotEmpty() })
        fun block(t: Thread) = "tid ${t.id} ${t.name}\n" + t.frames.joinToString("\n")
        val crashing = threads.filter { it.id == tid }
        return Text((head + threads.map(::block)).joinToString("\n"), (head + crashing.map(::block)).joinToString("\n"))
    }
}
