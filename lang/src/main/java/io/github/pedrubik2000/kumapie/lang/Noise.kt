package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Lang

/**
 * "Noise" in the known-word lists (Pedro, 2026-11-25): single kana, bare numbers and number+counter, symbols and
 * subtitle marks, Latin letters. They stay known for colouring and for stopping on unknown words, but are left out of
 * every count and list shown ([KnownWords.knownCount]). Japanese only; other languages are going away.
 */
object Noise {
    private val KANA_OR_KANJI = Regex("[぀-ヿㇰ-ㇿ㐀-鿿ｦ-ﾟ]")
    private val KANA = Regex("[぀-ヿㇰ-ㇿｦ-ﾟ]")
    /** One-kana particles Pedro marks on purpose: words, not noise. */
    private const val PARTICLES = "はがをにのへでともやかて"
    private val NUMBER = Regex("第?[0-9０-９]+[第人日歳階つ目万回話個本枚年月時分]*")

    /** [words] without noise: what counts and lists show. */
    fun countable(words: Collection<String>, lang: Lang): List<String> = words.filterNot { isNoise(it, lang) }

    fun isNoise(word: String, lang: Lang): Boolean {
        if (lang != Lang.JAPANESE) return false
        val w = word.trim()
        if (w.isEmpty()) return true
        if (w.any { Character.getType(it) == Character.FORMAT.toInt() }) return true
        if (!KANA_OR_KANJI.containsMatchIn(w)) return true
        if (w.codePointCount(0, w.length) == 1 && KANA.matches(w)) return w !in PARTICLES.map(Char::toString)
        return NUMBER.matches(w)
    }
}
