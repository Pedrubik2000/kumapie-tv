package io.github.pedrubik2000.kumapie.data

import io.github.pedrubik2000.kumapie.i18n.tr
import java.util.Locale

/**
 * A language kumapie plays: what an episode is spoken in ([code]) and what its translation is in
 * (kumapie_languages_plan.md: German, then Japanese, then English); [of] answers German for episodes that don't say
 * (older ones, the PC's /api/tv).
 */
data class Lang(
    /** ISO 639-1: the episode's `lang`, the audio track to keep, Wiktionary's section. */
    val code: String,
    val name: String,
    val translation: String,
    val translationName: String,
    /** The device voice. */
    val locale: Locale,
    /** Pedro's Anki note type for this language: its name now first, then older names. */
    val noteTypes: List<String>,
    /** Where mined cards go. */
    val deck: String,
    /** Anki tags: "_de::word", "_de::sentence", "_de::core1000". */
    val tagPrefix: String,
) {
    fun tag(name: String) = "$tagPrefix::$name"

    /** [name] and [translationName] in the menu language, for display only ([name] also goes to Gemma's prompt). */
    val displayName: String get() = tr(name)
    val translationDisplayName: String get() = tr(translationName)

    companion object {
        val GERMAN = Lang("de", "German", "en", "English", Locale.GERMANY, listOf("🐻 German", "🇩🇪 MvJ"), "Deutsch::Mined", "_de")
        /** Kaishi 1.5k came in as 🇯🇵 MvJ+; mined cards go next to it under Japanese::. */
        val JAPANESE = Lang("ja", "Japanese", "en", "English", Locale.JAPAN, listOf("🐻 Japanese", "🇯🇵 MvJ+"), "Japanese::Mined", "_ja")
        /** Pedro's English (accent work): English Core 1000 came in as 🇺🇸 MvJ; no translation track. */
        val ENGLISH = Lang("en", "English", "en", "English", Locale.US, listOf("🐻 English", "🇺🇸 MvJ"), "English::Mined", "_en")
        val ALL = listOf(GERMAN, JAPANESE, ENGLISH)

        fun of(code: String?): Lang = ALL.firstOrNull { it.code == code } ?: GERMAN
    }
}
