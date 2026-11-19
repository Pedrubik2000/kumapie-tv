package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.data.SrsCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where a Review screen's cards come from and where its ratings go: the PC (TV `pcReview`) or the device's own store ([ownReview]). */
class ReviewSource(val due: suspend () -> List<SrsCard>, val rate: suspend (SrsCard, Int) -> Unit, val audioUrl: (String) -> String = { it })

/**
 * No PC (kumapie for other people): the device's own cards ([SrsCards]), due now first (oldest due first), then the
 * new ones (oldest mined first); FSRS on the device. TV and phone. Only cards with the line's audio and picture on this
 * device ([CardMedia]; Pedro, 2026-11-19): a card mined or moved here has them, the PC's copies are text.
 */
fun ownReview(context: android.content.Context, lang: String): ReviewSource {
    val srs by lazy { SrsCards(context).store(Lang.of(lang)) }
    return ReviewSource(
        due = {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                srs?.cards().orEmpty().filter { (it.due ?: 0L) <= now }
                    .sortedWith(compareBy({ it.due == null }, { it.due ?: it.created }))
                    .mapNotNull { c ->
                        val (audio, image) = media(context, c.fields) ?: return@mapNotNull null
                        val reading = FURIGANA.find(c.word)?.groupValues?.get(2).orEmpty()
                        SrsCard(plain(c.word), plain(c.sentence), "", listOf(reading, plain(c.meaning)).filter { it.isNotBlank() }.joinToString(" · "),
                            if (c.due == null) "new" else "review", audio, c.id, image)
                    }
            }
        },
        rate = { c, r -> withContext(Dispatchers.IO) { requireNotNull(srs).rate(c.id, r) } },
    )
}

/** An own card's audio and picture as file URIs ([fields]: its JSON), null when either isn't on this device. */
private fun media(context: android.content.Context, fields: String): Pair<String, String>? {
    if ("[audio:" !in fields || "<img" !in fields) return null // most cards: text only, no file lookups
    val (audio, image) = CardMedia.names(org.json.JSONObject(fields).let { o -> o.keys().asSequence().map { o.getString(it) }.toList() })
    val a = audio?.let { CardMedia.file(context, it) } ?: return null
    val i = image?.let { CardMedia.file(context, it) } ?: return null
    return android.net.Uri.fromFile(a).toString() to android.net.Uri.fromFile(i).toString()
}

/**
 * Home's Review count: the device's own cards due now plus the new ones, counted only. Home used `due().size`, which made
 * every card's text with regexes: seconds of CPU on the Chromecast, still running when the title page and the stream
 * list opened (their frames took 96-163 ms each, 2026-11-19).
 */
suspend fun ownDueCount(context: android.content.Context, lang: String): Int = withContext(Dispatchers.IO) {
    val now = System.currentTimeMillis()
    SrsCards(context).store(Lang.of(lang))?.dueCount(now) ?: 0
}

/** Anki's furigana: " 支配[しはい]" (the space starts the word). */
private val FURIGANA = Regex(""" ?(\S+?)\[([^\]]*)\]""")
private val BREAK = Regex("""<br\s*/?>""")
private val TAG = Regex("<[^>]+>")

/** A field of an own card as text: furigana and HTML tags dropped (<b> marks the word in the sentence). */
private fun plain(s: String) = s.replace(BREAK, " ").replace(TAG, "").replace(FURIGANA, "$1").trim()

/**
 * Where [word] is in [sentence], to mark it: whole words in spaced scripts, anywhere in Japanese. A Japanese word not
 * found as written is looked for without its trailing kana, one at a time, while a kanji is left (仕留める in
 * 必ず仕留め…, 食べる in 食べた), so a conjugated form is marked by its stem.
 */
fun wordRanges(sentence: String, word: String): List<IntRange> {
    if (word.isEmpty()) return emptyList()
    val spaced = word.any { it.isLetter() && it.code < 0x3000 }
    if (spaced) return Regex("(?i)(?<![\\p{L}'])" + Regex.escape(word) + "(?![\\p{L}])").findAll(sentence).map { it.range }.toList()
    val kana = 'ぁ'..'ゟ'
    var w = word
    while (true) {
        val found = Regex(Regex.escape(w)).findAll(sentence).map { it.range }.toList()
        if (found.isNotEmpty() || w.last() !in kana || w.dropLast(1).all { it in kana }) return found
        w = w.dropLast(1)
    }
}
