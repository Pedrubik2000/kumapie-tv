package io.github.pedrubik2000.kumapie.lang

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class Reply(val code: Int, val body: InputStream, val retryAfter: Int? = null) {
    constructor(code: Int, text: String, retryAfter: Int? = null) : this(code, ByteArrayInputStream(text.toByteArray()), retryAfter)

    fun text(): String = body.bufferedReader().use { it.readText() }
}

fun interface JitenTransport {
    fun get(url: String, userAgent: String): Reply
}

object JitenHttp : JitenTransport {
    override fun get(url: String, userAgent: String): Reply {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", userAgent)
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: ByteArrayInputStream(ByteArray(0)))
        return Reply(code, stream, c.getHeaderField("Retry-After")?.trim()?.toIntOrNull())
    }
}

class JitenPaced(
    private val inner: JitenTransport,
    private val gapMs: Long = 500,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
) : JitenTransport {
    private var last = 0L

    @Synchronized
    override fun get(url: String, userAgent: String): Reply {
        val wait = last + gapMs - clock()
        if (wait > 0) sleep(wait)
        last = clock()
        return inner.get(url, userAgent)
    }
}
