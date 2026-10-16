package android.util

/** Desktop stand-in for Android's log (the shared :core code logs with it): to stdout. */
object Log {
    fun i(tag: String, msg: String): Int { println("$tag: $msg"); return 0 }
    fun d(tag: String, msg: String): Int = i(tag, msg)
    fun w(tag: String, msg: String, e: Throwable? = null): Int = i(tag, "$msg${e?.let { " ($it)" } ?: ""}")
    fun e(tag: String, msg: String, e: Throwable? = null): Int = w(tag, msg, e)
}
