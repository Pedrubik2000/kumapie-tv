package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.EpisodeDetail
import io.github.pedrubik2000.kumapie.data.Word
import io.github.pedrubik2000.kumapie.i18n.tr

enum class WaitState { NEW, DUE, MINED }

data class WaitWord(
    val word: String,
    val state: WaitState,
    val reading: String = "",
    val gloss: String = "",
    val text: String = "",
    val offset: Int = 0,
    val length: Int = 0,
)

data class WaitWords(val rows: List<WaitWord>, val newCount: Int, val dueCount: Int)

object WaitPick {
    const val ROWS = 6
    private const val SHORT_KANA = 2
    private const val GLOSS_MAX = 40
    private val STOP = setOf("だって", "でも", "それ", "これ", "あれ", "ここ", "そこ", "ああ", "ええ", "うん", "はい")

    fun state(w: Word): WaitState? = when (w.shown) {
        "u", "l" -> WaitState.NEW
        "d" -> WaitState.DUE
        "b" -> WaitState.MINED
        else -> null
    }

    fun worthy(word: String): Boolean = word !in STOP && (word.any { it.code in 0x3400..0x9FFF } || word.length > SHORT_KANA)

    private class Seen(val text: String, val offset: Int, val length: Int, val first: Int, var count: Int)

    fun hideTitle(blur: Boolean, seen: Set<String>, id: String): Boolean = blur && id !in seen

    fun katakanaOnly(word: String): Boolean = word.isNotEmpty() && word.all { it in 'ァ'..'ヺ' || it == 'ー' || it == '・' }

    fun pick(ep: EpisodeDetail, max: Int = ROWS, isName: (String) -> Boolean = { false }): WaitWords {
        val states = ep.words.mapNotNull { (k, w) -> state(w)?.let { k to it } }.toMap()
        val seen = LinkedHashMap<String, Seen>()
        var order = 0
        for (scene in ep.scenes) for (cue in scene.cues) {
            var at = 0
            for (seg in cue.segments) {
                val key = seg.word
                if (key != null && key in states && worthy(key)) {
                    seen.getOrPut(key) { Seen(cue.text, at, seg.text.length, order++, 0) }.count++
                }
                at += seg.text.length
            }
        }
        val mined = { e: Map.Entry<String, Seen> -> if (states[e.key] == WaitState.MINED) 1 else 0 }
        val ranked = seen.entries.sortedWith(compareBy(mined, { -it.value.count }, { it.value.first }))
        val rows = ranked.asSequence().filterNot { isName(it.key) }.take(max)
            .map { (k, s) -> WaitWord(k, states.getValue(k), text = s.text, offset = s.offset, length = s.length) }.toList()
        return WaitWords(rows, states.values.count { it == WaitState.NEW }, states.values.count { it == WaitState.DUE })
    }

    suspend fun fill(words: WaitWords, language: Language, yomitan: YomitanDictionaries): WaitWords = words.copy(rows = words.rows.map { r ->
        val head = runCatching { language.lookup(Tap(r.text, r.offset, r.length)).firstOrNull() }.getOrNull()
        val gloss = head?.let { runCatching { WordCard.japaneseChoices(yomitan, it, null).choices.firstOrNull()?.gloss }.getOrNull() }.orEmpty()
        r.copy(reading = if (r.word.any { it.code in 0x3400..0x9FFF }) head?.reading.orEmpty() else "", gloss = gloss.substringBefore(';').take(GLOSS_MAX).trim())
    })
}

fun waitSummary(words: WaitWords?, noLines: Boolean): String {
    if (words == null) return if (noLines) tr("No Japanese subtitles for this episode") else tr("Looking for the words in the subtitles…")
    return when {
        words.newCount == 0 && words.dueCount == 0 -> tr("No new words here")
        words.dueCount == 0 -> tr("%d new", words.newCount)
        words.newCount == 0 -> tr("%d due today", words.dueCount)
        else -> tr("%1\$d new · %2\$d due today", words.newCount, words.dueCount)
    }
}
