package android.net

/** Desktop stand-in: Uri.encode as :core's Api uses it (spaces as %20, like Android). */
object Uri {
    fun encode(s: String?): String? = s?.let { java.net.URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }
}
