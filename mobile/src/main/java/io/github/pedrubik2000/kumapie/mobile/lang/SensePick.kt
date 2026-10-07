package io.github.pedrubik2000.kumapie.mobile.lang

import io.github.pedrubik2000.kumapie.data.Scene

/**
 * Which dictionary sense fits a word in its line: the one sharing the most words with the line's English subtitle
 * ("Der Boden ist glatt" / "the floor is slippery" → glatt "slippery"); for a Spanish speaker the Spanish line
 * ("free" / "es gratis" → "gratis (without needing to pay)"). Instant and offline. In a test of 15 tricky
 * words it chose right 10 times (the first sense: 3); Gemma 4 E2B on the tablet chose 9 and took seconds per word.
 */
object SensePick {
    /** (entry, sense) indexes of the best sense, or null when no sense shares a word with [english]. */
    fun best(entries: List<DictEntry>, english: String): Pair<Int, Int>? {
        val en = words(english)
        if (en.isEmpty()) return null
        var best: Pair<Int, Int>? = null
        var top = 0
        entries.forEachIndexed { i, e ->
            e.senses.forEachIndexed { j, s ->
                // Words before a bracket are the translation itself; the bracket only explains.
                val score = 2 * (words(s.gloss.substringBefore('(')) intersect en).size + (words(s.gloss) intersect en).size
                if (score > top) { top = score; best = i to j }
            }
        }
        return best
    }

    /** The index of the meaning (short English text, Japanese dictionaries) sharing the most words with [english], or null. */
    fun bestMeaning(meanings: List<String>, english: String): Int? {
        val en = words(english)
        if (en.isEmpty()) return null
        var best: Int? = null
        var top = 0
        meanings.forEachIndexed { i, m ->
            val score = 2 * (words(m.substringBefore('(')) intersect en).size + (words(m) intersect en).size
            if (score > top) { top = score; best = i }
        }
        return best
    }

    /** The English subtitles shown with [line] of [scene] (those overlapping its time; else the scene's). */
    fun english(scene: Scene, line: Int): String {
        val cue = scene.cues.getOrNull(line) ?: return ""
        val near = scene.english.filter { it.end > cue.start && it.start < cue.end }
        return (near.ifEmpty { scene.english }).joinToString(" ") { it.text }
    }

    private val STOP = ("to a an the of or and be is in on for with by as e g etc someone something one's it that this at from " +
        "up out not used i you he she we they me my your his her its our their do does have has").split(" ").toSet()

    private val STOP_ES = ("a al de del el la los las lo un una unos unas y o u e ni que en con por para se su sus mi mis tu " +
        "es son ser está estar fue era muy más no sí ya le les me te nos yo él ella ellos ellas usted algo alguien como pero").split(" ").toSet()

    private fun words(text: String): Set<String> {
        if (io.github.pedrubik2000.kumapie.data.Lang.speaker == "es") {
            // Spanish: accents off; the first 5 letters stand for the word (elefantes/elefante, corre/correr).
            val plain = java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            return Regex("[a-zñ]+").findAll(plain).map { it.value }.filter { it !in STOP_ES && it.length > 1 }.map { it.take(5) }.toSet()
        }
        return Regex("[a-z']+").findAll(text.lowercase()).map { it.value }.filter { it !in STOP }.map(::stem).toSet()
    }

    private fun stem(w: String): String {
        for (suffix in listOf("ing", "ed", "es", "s", "ly")) if (w.length > 4 && w.endsWith(suffix)) return w.dropLast(suffix.length)
        return w
    }
}
