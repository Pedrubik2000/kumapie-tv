package io.github.pedrubik2000.kumapie.lang

import org.junit.Assert.assertEquals
import org.junit.Test

/** [JapaneseModel.join] on Sudachi's real mode-B output (sudachipy, SudachiDict core) for Bofuri 01 lines. */
class JapaneseJoinTest {
    /** "surface/base/pos/sub" pieces, as Sudachi gives them. */
    private fun words(vararg pieces: String): String {
        var at = 0
        val tokens = pieces.map { p ->
            val (s, b, pos, sub) = p.split("/")
            JapaneseModel.Token(s, b, "", pos, at, sub).also { at += s.length }
        }
        return JapaneseModel.join(tokens).filter { it.isWord }.joinToString(" | ") { if (it.surface == it.base) it.surface else "${it.surface}→${it.base}" }
    }

    @Test fun verbsTakeTheirEndings() {
        assertEquals("そう | だ | よ | だって | もう | 届いた→届く | ん | でしょ→です", words(
            "そう/そう/副詞/*", "だ/だ/助動詞/*", "よ/よ/助詞/終助詞", " / /空白/*", "だ/だ/助動詞/*", "って/って/助詞/副助詞",
            " / /空白/*", "もう/もう/副詞/*", "届い/届く/動詞/一般", "た/た/助動詞/*", "ん/ん/助詞/準体助詞", "でしょ/です/助動詞/*"))
        assertEquals("教えていません→教える", words(
            "教え/教える/動詞/一般", "て/て/助詞/接続助詞", "い/いる/動詞/非自立可能", "ませ/ます/助動詞/*", "ん/ぬ/助動詞/*"))
        assertEquals("集中 | しなさい→する", words("集中/集中/名詞/普通名詞", "し/する/動詞/非自立可能", "なさい/なさる/動詞/非自立可能"))
    }

    @Test fun conjunctionsSuffixesAndCopula() {
        assertEquals("でも | 理沙 | は | まだ | できない→できる", words(
            "で/で/助詞/格助詞", "も/も/助詞/係助詞", " / /空白/*", "理沙/理沙/名詞/固有名詞", "は/は/助詞/係助詞",
            "まだ/まだ/副詞/*", "でき/できる/動詞/非自立可能", "ない/ない/助動詞/*"))
        assertEquals("防御力 | は | 初心者 | さん", words(
            "防御/防御/名詞/普通名詞", "力/力/接尾辞/名詞的", "は/は/助詞/係助詞", "初心者/初心者/名詞/普通名詞", "さん/さん/接尾辞/名詞的"))
        assertEquals("お風呂 | わいた→わく | から", words(
            "お/お/接頭辞/*", "風呂/風呂/名詞/普通名詞", "わい/わく/動詞/一般", "た/た/助動詞/*", "から/から/助詞/接続助詞"))
        assertEquals("お待ちください→待つ", words("お/お/接頭辞/*", "待ち/待つ/動詞/一般", "ください/くださる/動詞/非自立可能"))
        assertEquals("ご丁寧 | に→だ", words("ご/ご/接頭辞/*", "丁寧/丁寧/形状詞/一般", "に/だ/助動詞/*"))
        assertEquals("ふう | に→だ | なってる→なる", words(
            "ふう/ふう/名詞/普通名詞", "に/だ/助動詞/*", "なっ/なる/動詞/非自立可能", "てる/てる/助動詞/*"))
    }
}
