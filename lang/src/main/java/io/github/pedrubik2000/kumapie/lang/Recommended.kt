package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Lang

/**
 * Starter sets per language for [YomitanDictionaries.installRecommended]: index.json URLs (updatable), or a dictionary zip
 * without an index of its own ([ZIPS], known by the title inside). German = wty-de-en (Wiktionary, the source of the old
 * de-en.sqlite) + its IPA.
 */
object Recommended {
    private const val JMDICT = "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download"
    private const val WTY = "https://huggingface.co/datasets/daxida/wty-release/resolve/main/latest/index"

    /** JPDB v2.2, Kuuube's Yomitan build: a rank per reading (私 わたし before わたくし, core plan 5f). Its list has no licence: a link only. */
    const val JPDB = "https://github.com/Kuuuube/yomitan-dictionaries/releases/download/yomitan-permalink/JPDB_v2.2_Frequency_Kana.zip"
    val ZIPS = mapOf(JPDB to "JPDBv2㋕")

    val SETS = mapOf(
        // German-German last: the monolingual definition for mining and the fallback when wty-de-en has nothing.
        "de" to listOf("$WTY/wty-de-en-index.json?download=true", "$WTY/wty-de-en-ipa-index.json?download=true",
            "$WTY/wty-de-de-index.json?download=true"),
        // English for Pedro: English meanings and IPA. For a Spanish speaker (the parents): Spanish meanings per sense (gloss), the Spanish Wiktionary's English entries, IPA - 93% of their episodes' words, 99% heard.
        "en-es" to listOf("$WTY/wty-en-es-gloss-index.json?download=true", "$WTY/wty-en-es-index.json?download=true",
            "$WTY/wty-en-es-ipa-index.json?download=true", "$WTY/wty-en-en-index.json?download=true"),
        "en" to listOf("$WTY/wty-en-en-index.json?download=true", "$WTY/wty-en-en-ipa-index.json?download=true"),
        // Japanese without a PC (kumapie for other people): JMdict and JMnedict, Yomitan's own builds (EDRDG licence, CC BY-SA 4.0: the attribution comes in each index), then JPDB. Spanish JMdict is small: English behind it.
        "ja" to listOf("$JMDICT/JMdict_english.json", "$JMDICT/JMnedict.json", JPDB),
        "ja-es" to listOf("$JMDICT/JMdict_spanish.json", "$JMDICT/JMdict_english.json", "$JMDICT/JMnedict.json", JPDB),
    )

    fun of(lang: Lang): List<String> = (SETS["${lang.code}-${Lang.speaker}"] ?: SETS[lang.code]).orEmpty()

    /** The zips of [urls] none of [installed] has (by title): a device that installed the set before JPDB joined it still gets JPDB, and only it. */
    fun missingZips(urls: List<String>, installed: List<YomitanDictionaries.Dict>): List<String> =
        urls.filter { u -> ZIPS[u]?.let { t -> installed.none { it.title == t } } == true }
}
