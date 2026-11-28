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

    fun state(w: Word): WaitState? = state(w.shown)

    fun state(shown: String?): WaitState? = when (shown) {
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

/**
 * The words of the last episode of a show (of its season) a person watched, kept on the device for the wait before
 * the next one (Pedro, 2026-11-28: the waiting screen "looks boring"; he picked "While you wait"): the ones still not
 * known are shown again, in the colour their cards give them now. A text file: `number|episode id`, then one word a
 * line, `word|STATE|offset|length|its line`. Whoever shows them checks the episode was watched to its end: the file is
 * written a while into an episode, and its words would spoil one that was only opened.
 * Keeping them looks nothing up: the bedroom TV's 32-bit process is at its memory's end once a video plays (a native
 * out-of-memory crash there on 2026-11-28 and 2026-11-26), so readings and meanings are found when they are shown,
 * on the wait, before the next video ([WaitPick.fill]).
 */
object WaitLast {
    const val KEEP = 12
    const val SHOWN = 5

    class Kept(val number: Int?, val id: String, val rows: List<WaitWord>)

    /** [id] without its episode: `kitsu:46474:27` -> `kitsu:46474`, `tt123:2:5` -> `tt123:2`; a film's id as it is. */
    fun season(id: String): String {
        val parts = id.split(':')
        val film = parts.size <= (if (parts[0] == "kitsu") 2 else 1) || parts.last().toIntOrNull() == null
        return if (film) id else parts.dropLast(1).joinToString(":")
    }

    fun file(dir: java.io.File, id: String) = java.io.File(dir, "wait_words/" + season(id).replace(Regex("[^A-Za-z0-9]+"), "_") + ".txt")

    private fun clean(text: String) = text.replace('|', '/').replace('\n', ' ')

    fun encode(number: Int?, id: String, rows: List<WaitWord>): String =
        (listOf("${number ?: 0}|${clean(id)}") + rows.map { listOf(it.word, it.state.name, it.offset.toString(), it.length.toString(), it.text).joinToString("|", transform = ::clean) }).joinToString("\n")

    /** Null when [text] isn't such a file. */
    fun decode(text: String): Kept? {
        val lines = text.lines()
        val head = lines.firstOrNull()?.split('|')?.takeIf { it.size == 2 && it[1].isNotEmpty() } ?: return null
        val number = head[0].toIntOrNull() ?: return null
        return Kept(number.takeIf { it > 0 }, head[1], lines.drop(1).mapNotNull { line ->
            val p = line.split('|', limit = 5)
            val state = WaitState.entries.firstOrNull { it.name == p.getOrNull(1) }
            val (offset, length) = (p.getOrNull(2)?.toIntOrNull() ?: -1) to (p.getOrNull(3)?.toIntOrNull() ?: -1)
            if (p.size < 5 || state == null || p[0].isEmpty() || offset < 0 || length <= 0 || offset + length > p[4].length) null
            else WaitWord(p[0], state, text = p[4], offset = offset, length = length)
        })
    }

    /** The kept [rows] to show again: each in the state [shown] gives its word now ([KnownWords.shown]: "k" known, so it goes). */
    fun still(rows: List<WaitWord>, max: Int = SHOWN, shown: (String) -> String?): List<WaitWord> =
        rows.mapNotNull { r -> WaitPick.state(shown(r.word))?.let { r.copy(state = it) } }.take(max)
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
