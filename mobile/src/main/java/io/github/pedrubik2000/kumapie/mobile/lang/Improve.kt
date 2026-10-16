package io.github.pedrubik2000.kumapie.mobile.lang

import android.text.Html
import io.github.pedrubik2000.kumapie.i18n.tr
import android.text.TextUtils
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.EpisodeDetail
import io.github.pedrubik2000.kumapie.data.Scene
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.lang.*
import java.io.File

/**
 * The PC's /improve-card without Claude, for the Home language ([lang]: German, Japanese, English): finds the word cards
 * of its Mined deck that don't stick (lapses, or low stability after several reviews), or any one card asked for
 * ([forNote], [forWord]), and offers other scenes with the word from every episode (i+0 first, then shorter), a
 * person's recording of it, and the current definitions to edit. Applying changes the note in place (scheduling
 * untouched) and flags its cards orange, as the PC's skill does.
 */
class Improve(private val library: Library, lang: String) {
    private val language = library.languages.of(lang)
    private val known = language.known
    private val anki = AnkiCards(known.context)
    private val japanese = language as? Japanese

    class Struggling(val note: AnkiCards.Note, val cards: List<AnkiCards.Card>, val word: String, val reading: String?,
                     val key: String?, val lapses: Int, val stability: Double?) {
        val sentence: String get() = plain(note.fields["Sentence"] ?: "").replace(Regex("""\[[^\]]*]"""), "")
        val bilingual: String get() = note.fields[KnownWords.DEF_BI] ?: ""
        val monolingual: String get() = note.fields[KnownWords.DEF_MONO] ?: ""
    }

    /**
     * [unknown]: the sentence's red words (no studied card of their own, not marked known: the "i+" of the sentence
     * alone). [line]: the cue the word is in; [surface]: the word as written there.
     */
    class Candidate(val show: Show, val episode: Episode, val detail: EpisodeDetail, val scene: Scene, val line: Int,
                    val surface: String, val sentence: String, val english: String, val unknown: Int)

    /** Word cards with 2+ lapses, or under 3 days of stability after 4+ reviews; worst first. */
    suspend fun struggling(): List<Struggling> = all().filter { s ->
        val reviewed = s.cards.filter { it.reviewed }
        reviewed.isNotEmpty() && (s.lapses >= 2 || (reviewed.maxOf { it.reps } >= 4 && s.stability != null && s.stability < 3))
    }.sortedWith(compareBy({ -it.lapses }, { it.stability ?: 0.0 }))

    /** Any word card of the Mined deck by note id (Anki XP's "Improve this card first"), struggling or not. */
    suspend fun forNote(id: Long): Struggling? = all().firstOrNull { it.note.id == id }

    /** Any word card of the Mined deck by its word (`am start … --es improve_word helfen`), struggling or not. */
    suspend fun forWord(word: String): Struggling? = all().firstOrNull { it.word == word || it.key == word }

    /** Every word card of the language's Mined deck, as read from kuma3 now. */
    private suspend fun all(): List<Struggling> {
        known.refresh().getOrThrow()
        val r = known.lastReading ?: error(tr("Anki wasn't read."))
        val cards = r.cards.groupBy { it.noteId }
        return r.notes.mapNotNull { n ->
            // German "Komm[→ kommen]", Japanese "届く[とどく]:0-" or "すごい:2-".
            val field = plain(n.fields["Word"] ?: "")
            val word = field.substringBefore("[").substringBefore(":").trim()
            if (word.isEmpty()) return@mapNotNull null
            val reading = if (japanese != null) field.substringAfter("[", "").substringBefore("]").takeIf { it.isNotEmpty() } else null
            val reviewed = cards[n.id].orEmpty().filter { it.reviewed }
            // Japanese episodes key words by their dictionary form, as the Word field has it.
            val key = if (japanese != null) word else r.morphs[n.id]?.firstOrNull()
            Struggling(n, cards[n.id].orEmpty(), word, reading, key, reviewed.maxOfOrNull { it.lapses } ?: 0,
                reviewed.mapNotNull { it.stability }.minOrNull())
        }
    }

    /** Lines with the card's word (its key) in every episode: i+0 first, then i+1…, then shorter. */
    suspend fun candidates(s: Struggling, shows: List<Show>, progress: (String) -> Unit): List<Candidate> {
        val key = s.key ?: return emptyList()
        val out = ArrayList<Candidate>()
        val episodes = library.allEpisodes(shows, progress)
        android.util.Log.i("kumapie", "improve: ${s.word} key '$key', ${episodes.size} episodes of ${shows.size} shows")
        for ((show, detail) in episodes) {
            val episode = show.episodes.firstOrNull { it.id == detail.id } ?: continue
            for (scene in detail.scenes) {
                if (!scene.target) continue
                val line = scene.cues.indexOfFirst { c -> c.segments.any { it.word == key } }
                if (line < 0) continue
                val surface = scene.cues[line].segments.first { it.word == key }.text
                val (sentence, cues) = if (japanese != null) scene.cues[line].text.trim() to listOf(scene.cues[line]) else Miner.sentence(scene, line)
                if (sentence.trim() == s.sentence.trim()) continue // the card's own sentence
                val unknown = io.github.pedrubik2000.kumapie.data.unknownIn(cues, detail.words)
                out += Candidate(show, episode, detail, scene, line, surface, sentence, library.miner.english(scene, cues), unknown)
            }
        }
        return out.sortedWith(compareBy({ it.unknown }, { it.sentence.length }))
    }

    /** A person's recording of the card's word (Wiktionary, or NHK/JapanesePod101/Forvo for Japanese), else null. */
    suspend fun recording(s: Struggling): File? = runCatching {
        japanese?.audio?.recording(s.word, s.reading.orEmpty()) ?: (language as? Spaced)?.let { library.miner.recording(s.word, it) }
    }.getOrNull()

    /**
     * Writes the choices into the note: the new scene ([c]: its audio, clip or picture, English and context, as mining
     * writes them) and its [sentence] as edited, the definitions as edited, the word's recording when [recording];
     * then flags the cards orange.
     */
    suspend fun apply(s: Struggling, c: Candidate?, sentence: String, bilingual: String, monolingual: String, recording: Boolean, progress: (String) -> Unit): String {
        val pkg = known.ankiApp() ?: error(tr("No kuma3 Anki on this device."))
        val fields = LinkedHashMap(s.note.fields)
        if (c != null) {
            val r = Miner.Request(c.detail, c.scene, c.line, Miner.Word(c.surface, s.key, s.word, ""))
            library.miner.sceneFields(r, pkg, fields.keys, "Improved", progress = progress).forEach { (k, v) -> if (k in fields) fields[k] = v }
        }
        // A sentence typed by hand (Japanese: the word in bold again).
        val shown = c?.sentence ?: s.sentence
        if (sentence.trim() != shown.trim()) {
            val text = sentence.trim()
            val at = if (japanese != null) text.indexOf(c?.surface ?: s.word) else -1
            fields["Sentence"] = if (at < 0) esc(text) else (c?.surface ?: s.word).let { w ->
                esc(text.substring(0, at)) + "<b>" + esc(w) + "</b>" + esc(text.substring(at + w.length)) }
        }
        if (bilingual != s.bilingual) fields[KnownWords.DEF_BI] = bilingual
        if (monolingual != s.monolingual) fields[KnownWords.DEF_MONO] = monolingual
        if (recording) {
            progress(tr("Getting the recording…"))
            recording(s)?.let { fields["Word Audio"] = "[audio:${library.miner.addAudio(pkg, it, "kumapie-${library.miner.slug(s.word)}")}]" }
        }
        progress(tr("Saving…"))
        if (fields != s.note.fields) anki.updateNote(pkg, s.note.id, s.note.tags, fields.values.toList())
        s.cards.forEach { anki.flag(pkg, s.note.id, it.ord, 2) }
        return tr("Saved \"%s\" and flagged it orange.", s.word)
    }

    companion object {
        fun plain(html: String): String = Html.fromHtml(html, 0).toString().trim()
        private fun esc(s: String) = TextUtils.htmlEncode(s)
    }
}
