package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.data.SrsCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where a Review screen's cards come from and where its ratings go: the PC (TV `pcReview`) or the device's own store ([ownReview]). */
class ReviewSource(val due: suspend () -> List<SrsCard>, val rate: suspend (SrsCard, Int) -> Unit, val audioUrl: (String) -> String = { it })

/**
 * No PC (kumapie for other people): the device's own cards ([SrsCards]), due now first (oldest due first), then the
 * new ones (oldest mined first); FSRS on the device. Own cards are text only: no audio. TV and phone.
 */
fun ownReview(context: android.content.Context, lang: String): ReviewSource {
    val srs by lazy { SrsCards(context).store(Lang.of(lang)) }
    return ReviewSource(
        due = {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                srs?.cards().orEmpty().filter { (it.due ?: 0L) <= now }
                    .sortedWith(compareBy({ it.due == null }, { it.due ?: it.created }))
                    .map { c ->
                        val reading = FURIGANA.find(c.word)?.groupValues?.get(2).orEmpty()
                        SrsCard(plain(c.word), plain(c.sentence), "", listOf(reading, plain(c.meaning)).filter { it.isNotBlank() }.joinToString(" · "),
                            if (c.due == null) "new" else "review", "", c.id)
                    }
            }
        },
        rate = { c, r -> withContext(Dispatchers.IO) { requireNotNull(srs).rate(c.id, r) } },
    )
}

/** Anki's furigana: " 支配[しはい]" (the space starts the word). */
private val FURIGANA = Regex(""" ?(\S+?)\[([^\]]*)\]""")

/** A field of an own card as text: furigana and HTML tags dropped (<b> marks the word in the sentence). */
private fun plain(s: String) = s.replace(Regex("""<br\s*/?>"""), " ").replace(Regex("<[^>]+>"), "").replace(FURIGANA, "$1").trim()

/** Where [word] is in [sentence], to mark it: whole words in spaced scripts, anywhere in Japanese. */
fun wordRanges(sentence: String, word: String): List<IntRange> {
    if (word.isEmpty()) return emptyList()
    val spaced = word.any { it.isLetter() && it.code < 0x3000 }
    return Regex(if (spaced) "(?i)(?<![\\p{L}'])" + Regex.escape(word) + "(?![\\p{L}])" else Regex.escape(word)).findAll(sentence).map { it.range }.toList()
}
