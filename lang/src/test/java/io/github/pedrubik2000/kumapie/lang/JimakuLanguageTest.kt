package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Jimaku
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Apocalypse Hotel 1 (core plan 17, Pedro 2026-11-22: Chinese subtitles), real lines from Jimaku's entry 9465: seven
 * files, one Nekomoe kissaten&LoliHouse "[CHS, JPN]" .ass whose Chinese style is "Dial_CH" (not caught by the Chinese
 * style names), so its short Chinese lines passed as Japanese (162 of 267); a LoliHouse video ranked it first.
 */
class JimakuLanguageTest {
    private val names = listOf(
        "[NanakoRaws] Apocalypse Hotel - 01 (AT-X 1920x1080 x265 AAC).ass",
        "[NanakoRaws] Apocalypse Hotel - 01 (AT-X 1920x1080 x265 AAC).srt",
        "[Nekomoe kissaten&LoliHouse] Apocalypse Hotel - 01 [WebRip][CHS, JPN].ass",
        "[Nekomoe kissaten&LoliHouse] Apocalypse Hotel - 01 [WebRip][JPN].ass",
        "[shincaps] Apocalypse Hotel - 01 (AT-X 1440x1080 MPEG2 AAC).ass",
        "[shincaps] Apocalypse Hotel - 01 (AT-X 1440x1080 MPEG2 AAC).srt",
        "アポカリプスホテル.S01E01.ホテルに物語を.WEBRip.Amazon.ja-jp[sdh].srt")
    private val japanese = listOf("大切な方とのメモリアルに", "このままでは絶滅も", "ぜひご利用ください", "また人体への被害も　報告されています",
        "建物　および室内の気密レベルを十分に確保し", "外出の際は防護マスクを　必ず着用してください")
    private val chinese = listOf("必能与重要之人共创一段难忘回忆", "若继续发展 或将面临灭绝", "敬请使用本酒店服务", "同时也出现了人类的伤亡报告",
        "要确保建筑物与室内空间的严密封闭", "外出时请务必佩戴防护面罩")
    private val times = listOf("0:01:17.56,0:01:19.81", "0:01:20.49,0:01:21.85", "0:01:21.85,0:01:23.56", "0:01:23.56,0:01:25.90",
        "0:01:25.90,0:01:29.40", "0:01:29.77,0:01:33.09")

    private fun ass(styles: List<Pair<String, List<String>>>) = ("[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
        styles.joinToString("") { (style, lines) -> lines.zip(times).joinToString("") { (l, t) -> "Dialogue: 0,$t,$style,,0,0,0,,$l\n" } }).toByteArray()

    private fun srt(rows: List<String>) = rows.zip(times).withIndex().joinToString("") { (i, p) ->
        "${i + 1}\n${p.second.replace(".", ",").split(",").let { "0${it[0]},${it[1]}0 --> 0${it[2]},${it[3]}0" }}\n${p.first}\n\n" }.toByteArray()

    @Test fun aLoliHouseVideoTakesTheJapaneseOnlyFileAndTheBilingualOneLast() {
        val ranked = Jimaku.rank(names.map { it to "" }, 1, "[LoliHouse] Apocalypse Hotel - 01 [WebRip 1080p HEVC-10bit AAC SRTx2].mkv").map { it.first }
        assertEquals(names[3], ranked.first())
        assertEquals(names[2], ranked.last())
    }

    @Test fun theBilingualAssKeepsOnlyItsJapaneseLines() {
        val cues = Jimaku.cues(names[2], ass(listOf("Dial_JP" to japanese, "Dial_CH" to chinese)))
        assertEquals(9, cues.size)
        assertEquals(japanese, Jimaku.japanese(cues)?.map { it.text })
    }

    @Test fun aChineseOnlyFileIsNotJapanese() = assertNull(Jimaku.japanese(Jimaku.cues("x.srt", srt(chinese))))

    @Test fun aJapaneseFileStaysWhole() = Jimaku.cues(names[3], ass(listOf("Dial_JP" to japanese))).let { assertEquals(it, Jimaku.japanese(it)) }

    @Test fun aBilingualSrtDropsTheChineseRowOfEachLine() = assertEquals(japanese,
        Jimaku.cues("x.srt", srt(japanese.zip(chinese).map { (j, c) -> "$j\n$c" })).map { it.text })

    @Test fun aSrtLineOfTwoJapaneseRowsStaysOneLine() = assertEquals(listOf("このままでは絶滅も ぜひご利用ください"),
        Jimaku.cues("x.srt", srt(listOf("このままでは絶滅も\nぜひご利用ください"))).map { it.text })
}
