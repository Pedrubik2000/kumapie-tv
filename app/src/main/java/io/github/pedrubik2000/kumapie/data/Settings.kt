package io.github.pedrubik2000.kumapie.data

import android.content.Context

/** Everything the app remembers on the TV itself. Progress (resume, scenes seen, minutes) lives on the server. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("kumapie", Context.MODE_PRIVATE)

    /** The dojo server, e.g. https://<pc>.<tailnet>.ts.net:8445 (no trailing slash). Empty = not set up yet. */
    var server: String
        get() = prefs.getString("server", "") ?: ""
        set(value) = prefs.edit().putString("server", normalizeServer(value)).apply()

    companion object {
        fun normalizeServer(value: String): String {
            val v = value.trim().trimEnd('/')
            return if (v.isEmpty() || v.startsWith("http://") || v.startsWith("https://")) v else "https://$v"
        }
    }
}
