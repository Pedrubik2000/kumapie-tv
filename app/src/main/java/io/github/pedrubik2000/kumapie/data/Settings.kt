package io.github.pedrubik2000.kumapie.data

import android.content.Context
import io.github.pedrubik2000.kumapie.player.Action
import io.github.pedrubik2000.kumapie.player.Binding
import io.github.pedrubik2000.kumapie.player.KeyContext
import io.github.pedrubik2000.kumapie.player.defaultBindings
import org.json.JSONObject

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

    /** What the subtitles show; kept from scene to scene and between episodes. */
    var subtitles: Subtitles
        get() = runCatching { Subtitles.valueOf(prefs.getString("subtitles", null)!!) }.getOrDefault(Subtitles.HIDDEN)
        set(value) = prefs.edit().putString("subtitles", value.name).apply()

    /** The help screen when an episode opens, and the key hints at the top. Options > Help works either way. */
    var showHelp: Boolean
        get() = prefs.getBoolean("show_help", true)
        set(value) = prefs.edit().putBoolean("show_help", value).apply()

    /** The buttons of a context: what you set in Settings > Change buttons, else the defaults. */
    fun keys(context: KeyContext): Map<Binding, Action> {
        val json = prefs.getString("keys_${context.name}", null) ?: return defaultBindings(context)
        return runCatching {
            val o = JSONObject(json)
            o.keys().asSequence().associate { k ->
                val (kind, code) = k.split(":", limit = 2)
                Binding(code.toInt(), kind == "long") to Action.valueOf(o.getString(k))
            }
        }.getOrElse { defaultBindings(context) }
    }

    fun setKeys(context: KeyContext, bindings: Map<Binding, Action>) {
        val o = JSONObject()
        bindings.forEach { (b, a) -> o.put((if (b.long) "long:" else "short:") + b.code, a.name) }
        prefs.edit().putString("keys_${context.name}", o.toString()).apply()
    }

    fun resetKeys(context: KeyContext) = prefs.edit().remove("keys_${context.name}").apply()

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
