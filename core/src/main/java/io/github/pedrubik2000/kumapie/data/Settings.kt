package io.github.pedrubik2000.kumapie.data

import android.content.Context

/** Everything the app remembers on the device itself. Progress (resume, scenes seen, minutes) lives on the server. */
class Settings(context: Context) {
    /** Public so each app can keep its own settings in the same file (the TV keeps its button map here). */
    val prefs = context.getSharedPreferences("kumapie", Context.MODE_PRIVATE)

    init {
        io.github.pedrubik2000.kumapie.i18n.Tr.use(menuLanguage)
        Lang.speaker = speaks
    }

    /** The language the person speaks ("en" / "es"): translation lines and meanings. Unset: the phone's language. */
    var speaks: String
        get() = prefs.getString("speaks", null)
            ?: if (java.util.Locale.getDefault().language == "es") "es" else "en"
        set(value) { prefs.edit().putString("speaks", value).apply(); Lang.speaker = value }

    /** The menus' language: "" = the phone's, "en" or "es" (Settings > Menu language; the app restarts its screen). */
    var menuLanguage: String
        get() = prefs.getString("ui_lang", "") ?: ""
        set(value) { prefs.edit().putString("ui_lang", value).apply(); io.github.pedrubik2000.kumapie.i18n.Tr.use(value) }

    /** The dojo server, e.g. https://<pc>.<tailnet>.ts.net:8445 (no trailing slash). Empty = not set up yet. */
    var server: String
        get() = prefs.getString("server", "") ?: ""
        set(value) = prefs.edit().putString("server", normalizeServer(value)).apply()

    /** Pause when a scene ends (true) or play on into the next one. */
    /** Primed Listening: each new scene waits on its start, its lines on screen, until play (read first, then hear). */
    var pauseAtSceneStart: Boolean
        get() = prefs.getBoolean("pause_at_scene_start", false)
        set(value) = prefs.edit().putBoolean("pause_at_scene_start", value).apply()

    var pauseAtSceneEnd: Boolean
        get() = prefs.getBoolean("pause_at_scene_end", true)
        set(value) = prefs.edit().putBoolean("pause_at_scene_end", value).apply()

    /** Scenes not seen yet play straight through (gaps too, no pauses); seen ones follow the two settings above. */
    var newStraight: Boolean
        get() = prefs.getBoolean("new_straight", false)
        set(value) = prefs.edit().putBoolean("new_straight", value).apply()

    /** Play at 0.75x. */
    var slow: Boolean
        get() = prefs.getBoolean("slow", false)
        set(value) = prefs.edit().putBoolean("slow", value).apply()

    /** What the subtitles show; kept from scene to scene and between episodes. */
    var subtitles: Subtitles
        get() = runCatching { Subtitles.valueOf(prefs.getString("subtitles", null)!!.replace("GERMAN", "TARGET")) }.getOrDefault(Subtitles.HIDDEN)
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

/** What the subtitles show: the episode's language (target), its translation (English), both, or nothing. */
enum class Subtitles {
    HIDDEN, BLURRED, TARGET, BOTH, ENGLISH;

    fun label(lang: Lang): String = when (this) {
        HIDDEN -> io.github.pedrubik2000.kumapie.i18n.tr("Hidden")
        BLURRED -> io.github.pedrubik2000.kumapie.i18n.tr("%s, blurred", lang.displayName)
        TARGET -> lang.displayName
        BOTH -> "${lang.displayName} + ${lang.translationDisplayName}"
        ENGLISH -> lang.translationDisplayName
    }

    fun next(): Subtitles = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(target: Boolean, english: Boolean): Subtitles = when {
            target && english -> BOTH
            target -> TARGET
            english -> ENGLISH
            else -> HIDDEN
        }
    }
}
