package io.github.pedrubik2000.kumapie.data

import android.content.Context

/** Everything the app remembers on the TV itself. Progress (resume, scenes seen, minutes) lives on the server. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("kumapie", Context.MODE_PRIVATE)

    /** The dojo server, e.g. https://<pc>.<tailnet>.ts.net:8445 (no trailing slash). Empty = not set up yet. */
    var server: String
        get() = prefs.getString("server", "") ?: ""
        set(value) = prefs.edit().putString("server", normalizeServer(value)).apply()

    /** Pause when a scene ends (true) or play on into the next one. */
    var pauseAtSceneEnd: Boolean
        get() = prefs.getBoolean("pause_at_scene_end", true)
        set(value) = prefs.edit().putBoolean("pause_at_scene_end", value).apply()

    /** Play at 0.75x. */
    var slow: Boolean
        get() = prefs.getBoolean("slow", false)
        set(value) = prefs.edit().putBoolean("slow", value).apply()

    /** How subtitles start in every scene: HIDDEN, GERMAN or BOTH. */
    var subtitles: Subtitles
        get() = runCatching { Subtitles.valueOf(prefs.getString("subtitles", null)!!) }.getOrDefault(Subtitles.HIDDEN)
        set(value) = prefs.edit().putString("subtitles", value.name).apply()

    companion object {
        fun normalizeServer(value: String): String {
            val v = value.trim().trimEnd('/')
            return if (v.isEmpty() || v.startsWith("http://") || v.startsWith("https://")) v else "https://$v"
        }
    }
}

enum class Subtitles(val label: String) {
    HIDDEN("Hidden until asked"), GERMAN("German"), BOTH("German + English");

    fun next(): Subtitles = entries[(ordinal + 1) % entries.size]
}
