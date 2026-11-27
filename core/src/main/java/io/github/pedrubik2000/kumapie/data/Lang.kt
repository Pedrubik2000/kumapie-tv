package io.github.pedrubik2000.kumapie.data

import io.github.pedrubik2000.kumapie.i18n.tr
import java.util.Locale

/**
 * A language kumapie plays: what an episode is spoken in ([code]) and what its translation is in (Japanese only since
 * the scope plan, 2026-11-27); [of] answers Japanese for every code, older German episodes included.
 */
data class Lang(
    /** ISO 639-1: the episode's `lang`, the audio track to keep. */
    val code: String,
    val name: String,
    /** The device voice. */
    val locale: Locale,
    /** Pedro's Anki note type for this language: its name now first, then older names. */
    val noteTypes: List<String>,
    /** Where mined cards go. */
    val deck: String,
    /** Anki tags: "_ja::word", "_ja::sentence". */
    val tagPrefix: String,
) {
    fun tag(name: String) = "$tagPrefix::$name"

    /** The language of the translation line and the meanings: the one the person speaks ([speaker]). */
    val translation: String get() = speaker
    val translationName: String get() = if (speaker == "es") "Spanish" else "English"

    /** [name] and [translationName] in the menu language, for display only ([name] also goes to Gemma's prompt). */
    val displayName: String get() = tr(name)
    val translationDisplayName: String get() = tr(translationName)

    companion object {
        // A word's own cards are only the ones in its language's Mined deck (user, 2026-10-11: not JPDB, grammar or
        // other decks). The note types only say which format a mined card gets (the first one in Anki).
        val JAPANESE = Lang("ja", "Japanese", Locale.JAPAN, listOf("🐻 Japanese", "🇯🇵 MvJ+"), "Japanese Mined", "_ja")
        val ALL = listOf(JAPANESE)
        val SHOWN = listOf(JAPANESE)

        /**
         * The language the person speaks, "en" or "es" (Settings > I speak; by default the phone's): Pedro English,
         * Giovanna and Jackson Spanish. Set by [Settings].
         */
        @Volatile var speaker: String = "en"

        fun of(code: String?): Lang = ALL.firstOrNull { it.code == code } ?: JAPANESE
    }
}
