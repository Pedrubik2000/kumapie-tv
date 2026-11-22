package io.github.pedrubik2000.kumapie.data

object Multipart {
    fun file(body: ByteArray, contentType: String): Pair<String, ByteArray>? {
        val boundary = contentType.substringAfter("boundary=", "").substringBefore(';').trim('"', ' ').ifEmpty { return null }
        val name = body.find("filename=\"".toByteArray(), 0).takeIf { it >= 0 } ?: return null
        val start = body.find("\r\n\r\n".toByteArray(), name).takeIf { it >= 0 }?.plus(4) ?: return null
        val end = body.find("\r\n--$boundary".toByteArray(), start).takeIf { it >= 0 } ?: return null
        val nameEnd = body.find("\"".toByteArray(), name + 10).takeIf { it in name + 10 until start } ?: return null
        return String(body, name + 10, nameEnd - name - 10, Charsets.UTF_8).substringAfterLast('/').substringAfterLast('\\') to
            body.copyOfRange(start, end)
    }

    private fun ByteArray.find(what: ByteArray, from: Int): Int {
        var i = from
        while (i <= size - what.size) {
            var j = 0
            while (j < what.size && this[i + j] == what[j]) j++
            if (j == what.size) return i
            i++
        }
        return -1
    }
}
