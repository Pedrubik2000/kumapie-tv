package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Lang

object Noise {
    private val KANA_OR_KANJI = Regex("[぀-ヿㇰ-ㇿｦ-ﾟ\\p{script=Han}〆〇々]")
    private val KANA = Regex("[぀-ヿㇰ-ㇿｦ-ﾟ]")
    private const val PARTICLES = "はがをにのへでともやかて"
    private val NUMBER = Regex("第?(?:[0-9０-９]++[\\p{script=Han}ヶかつ]{0,3}+)++")

    fun countable(words: Collection<String>, lang: Lang): List<String> = words.filterNot { isNoise(it, lang) }

    fun white(w: io.github.pedrubik2000.kumapie.data.Word) =
        w.copy(status = "k", card = w.card?.takeUnless { it == "u" }, forms = w.forms?.mapValues { (_, s) -> if (s == "u") "" else s })

    fun isNoise(word: String, lang: Lang): Boolean {
        if (lang != Lang.JAPANESE) return false
        val w = word.trim()
        if (w.isEmpty()) return true
        if (w.any { Character.getType(it) == Character.FORMAT.toInt() }) return true
        if (!KANA_OR_KANJI.containsMatchIn(w)) return true
        if (w.codePointCount(0, w.length) == 1 && KANA.matches(w)) return w[0] !in PARTICLES
        return w == "々" || NUMBER.matches(w)
    }
}
