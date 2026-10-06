package io.github.pedrubik2000.kumapie.data

import android.content.Context

/** Everything the app remembers on the device itself. Progress (resume, scenes seen, minutes) lives on the server. */
class Settings(context: Context) {
    /** Public so each app can keep its own settings in the same file (the TV keeps its button map here). */
    val prefs = context.getSharedPreferences("kumapie", Context.MODE_PRIVATE)

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

    /** What the subtitles show; kept from scene to scene and between episodes. */
    var subtitles: Subtitles
        get() = runCatching { Subtitles.valueOf(prefs.getString("subtitles", null)!!) }.getOrDefault(Subtitles.HIDDEN)
        set(value) = prefs.edit().putString("subtitles", value.name).apply()

    /** Phone/tablet: play episodes upright, one scene per page (swipe up), instead of landscape. */
    var upright: Boolean
        get() = prefs.getBoolean("upright_player", false)
        set(value) = prefs.edit().putBoolean("upright_player", value).apply()

    /** The feed's scene levels: 0 = i+0, 1 = i+1, 2 = i+2 and up. */
    var feedLevels: Set<Int>
        get() = prefs.getString("feed_levels", "1")!!.split(',').mapNotNull { it.toIntOrNull() }.toSet()
        set(value) = prefs.edit().putString("feed_levels", value.sorted().joinToString(",")).apply()

    /** The help screen when an episode opens, and the key hints at the top. Options > Help works either way. */
    var showHelp: Boolean
        get() = prefs.getBoolean("show_help", true)
        set(value) = prefs.edit().putBoolean("show_help", value).apply()

    companion object {
        fun normalizeServer(value: String): String {
            val v = value.trim().trimEnd('/')
            return if (v.isEmpty() || v.startsWith("http://") || v.startsWith("https://")) v else "https://$v"
        }
    }
}

enum class Subtitles(val label: String) {
    HIDDEN("Hidden"), BLURRED("German, blurred"), GERMAN("German"), BOTH("German + English"), ENGLISH("English");

    fun next(): Subtitles = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(german: Boolean, english: Boolean): Subtitles = when {
            german && english -> BOTH
            german -> GERMAN
            english -> ENGLISH
            else -> HIDDEN
        }
    }
}
