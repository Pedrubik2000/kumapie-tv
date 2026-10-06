package io.github.pedrubik2000.kumapie.data

import java.util.Locale

/**
 * A language kumapie plays: what an episode is spoken in ([code]) and what its translation is in. German only so
 * far (kumapie_languages_plan.md: Japanese, then English); [of] answers German for episodes that don't say (older
 * ones, the PC's /api/tv).
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

    companion object {
        val GERMAN = Lang("de", "German", "en", "English", Locale.GERMANY, listOf("🐻 German", "🇩🇪 MvJ"), "Deutsch::Mined", "_de")
        val ALL = listOf(GERMAN)

        fun of(code: String?): Lang = ALL.firstOrNull { it.code == code } ?: GERMAN
    }
}
