package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Lang

/**
 * Starter sets per language for [YomitanDictionaries.installRecommended]: index.json URLs (updatable), or a dictionary zip
 * without an index of its own ([ZIPS], known by the title inside).
 */
object Recommended {
    private const val JMDICT = "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download"

    /** JPDB v2.2, Kuuube's Yomitan build: a rank per reading (私 わたし before わたくし, core plan 5f). Its list has no licence: a link only. */
    const val JPDB = "https://github.com/Kuuuube/yomitan-dictionaries/releases/download/yomitan-permalink/JPDB_v2.2_Frequency_Kana.zip"
    val ZIPS = mapOf(JPDB to "JPDBv2㋕")

    val SETS = mapOf(
        // Japanese without a PC (kumapie for other people): JMdict and JMnedict, Yomitan's own builds (EDRDG licence, CC BY-SA 4.0: the attribution comes in each index), then JPDB. Spanish JMdict is small: English behind it.
        "ja" to listOf("$JMDICT/JMdict_english.json", "$JMDICT/JMnedict.json", JPDB),
        "ja-es" to listOf("$JMDICT/JMdict_spanish.json", "$JMDICT/JMdict_english.json", "$JMDICT/JMnedict.json", JPDB),
    )

    fun of(lang: Lang): List<String> = (SETS["${lang.code}-${Lang.speaker}"] ?: SETS[lang.code]).orEmpty()

    /** The zips of [urls] none of [installed] has (by title): a device that installed the set before JPDB joined it still gets JPDB, and only it. */
    fun missingZips(urls: List<String>, installed: List<YomitanDictionaries.Dict>): List<String> =
        urls.filter { u -> ZIPS[u]?.let { t -> installed.none { it.title == t } } == true }

    const val UNPACKED = 7

    fun needed(zipBytes: Long) = zipBytes * (UNPACKED + 1)

    fun fits(zipBytes: Long, freeBytes: Long) = needed(zipBytes) <= freeBytes

    fun mb(bytes: Long) = (bytes + (1 shl 19)) shr 20
}
