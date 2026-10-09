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
 * The PC's /improve-card without Claude: finds the German word cards that don't stick (lapses, or low stability after
 * several reviews) and offers other scenes with the exact word form from every episode (i+0 first, then shorter), the
 * word's recording, and the current definition to edit. Applying changes the note in place (scheduling untouched) and
 * flags its cards orange, as the PC's skill does.
 */
class Improve(private val library: Library) {
    private val anki = AnkiCards(library.languages.german.known.context)
    private val cut = File(library.languages.german.known.context.cacheDir, "mining")

    class Struggling(val note: AnkiCards.Note, val cards: List<AnkiCards.Card>, val word: String, val key: String?,
                     val lapses: Int, val stability: Double?) {
        val sentence: String get() = plain(note.fields["Sentence"] ?: "")
        val bilingual: String get() = note.fields[KnownWords.DEF_BI] ?: ""
        val monolingual: String get() = note.fields[KnownWords.DEF_MONO] ?: ""
    }

    /** [unknown]: words of the sentence never studied (the "i+" of the sentence alone). */
    class Candidate(val show: Show, val episode: Episode, val detail: EpisodeDetail, val scene: Scene, val sentence: String,
                    val english: String, val unknown: Int, val lineStartMs: Long, val lineEndMs: Long)

    /** Word cards (Core 1000, mined words) with 2+ lapses, or under 3 days of stability after 4+ reviews; worst first. */
    suspend fun struggling(): List<Struggling> {
        library.languages.german.known.refresh().getOrThrow()
        val r = library.languages.german.known.lastReading ?: error(tr("Anki wasn't read."))
        val cards = r.cards.groupBy { it.noteId }
        return r.notes.mapNotNull { n ->
            val word = plain(n.fields["Word"] ?: "").substringBefore("[").trim()
            if (word.isEmpty()) return@mapNotNull null
            val reviewed = cards[n.id].orEmpty().filter { it.reviewed }
            if (reviewed.isEmpty()) return@mapNotNull null
            val lapses = reviewed.maxOf { it.lapses }
            val stability = reviewed.mapNotNull { it.stability }.minOrNull()
            val shaky = reviewed.maxOf { it.reps } >= 4 && stability != null && stability < 3
            if (lapses < 2 && !shaky) return@mapNotNull null
            Struggling(n, cards[n.id].orEmpty(), word, r.morphs[n.id]?.firstOrNull(), lapses, stability)
        }.sortedWith(compareBy({ -it.lapses }, { it.stability ?: 0.0 }))
    }

    /** Sentences with the card's exact form (its morph key) in every episode: i+0 first, then i+1…, then shorter. */
    suspend fun candidates(s: Struggling, shows: List<Show>, progress: (String) -> Unit): List<Candidate> {
        val key = s.key ?: return emptyList()
        val out = ArrayList<Candidate>()
        for ((show, detail) in library.allEpisodes(shows, progress)) {
            val episode = show.episodes.firstOrNull { it.id == detail.id } ?: continue
            for (scene in detail.scenes) {
                if (!scene.target) continue
                val line = scene.cues.indexOfFirst { c -> c.segments.any { it.word == key } }
                if (line < 0) continue
                val (sentence, cues) = Miner.sentence(scene, line)
                if (sentence.trim() == s.sentence.trim()) continue // the card's own sentence
                val unknown = cues.flatMap { c -> c.segments.mapNotNull { it.word } }.toSet().count { detail.words[it]?.status == "u" }
                out += Candidate(show, episode, detail, scene, sentence, library.miner.english(scene, cues), unknown,
                    ((cues.first().start - 0.25).coerceAtLeast(0.0) * 1000).toLong(), ((cues.last().end + 0.25) * 1000).toLong())
            }
        }
        return out.sortedWith(compareBy({ it.unknown }, { it.sentence.length }))
    }

    /**
     * Writes the choices into the note: the new scene ([c], with its clip, English and context) and its [sentence] as
     * edited, the definitions as edited, the word's recording when [recording]; then flags the cards orange.
     */
    suspend fun apply(s: Struggling, c: Candidate?, sentence: String, bilingual: String, monolingual: String, recording: Boolean, progress: (String) -> Unit): String {
        val pkg = library.languages.german.known.ankiApp() ?: error(tr("No kuma3 Anki on this device."))
        val fields = LinkedHashMap(s.note.fields)
        if (c != null) {
            progress(tr("Cutting the scene…"))
            cut.mkdirs()
            val slug = library.miner.slug(c.detail.show)
            // Like a mined card: the line's audio, then the scene's clip (word audio -> sentence audio -> video).
            val line = File(cut, "line.m4a").apply { delete() }
            library.miner.audioClip(c.detail.video, c.lineStartMs, c.lineEndMs, line)
            val lineAudio = anki.addMedia(pkg, line, "${slug}_${c.lineStartMs}-${c.lineEndMs}")
            line.delete()
            val file = File(cut, "clip.webm").apply { delete() }
            val start = (c.scene.start * 1000).toLong()
            val end = (c.scene.end * 1000).toLong()
            library.miner.clip(c.detail.video, start, end, file)
            val clip = anki.addMedia(pkg, file, "${slug}_$start-$end")
            file.delete()
            fields["Video"] = "[audio:$lineAudio][audio:$clip]"
            fields["Notes"] = esc(c.english)
            fields["Context"] = esc("Improved in kumapie: ${c.detail.show} · ${c.detail.title}, scene ${c.scene.index + 1}.")
        }
        if (sentence.trim() != s.sentence.trim()) fields["Sentence"] = esc(sentence.trim())
        if (bilingual != s.bilingual) fields[KnownWords.DEF_BI] = bilingual
        if (monolingual != s.monolingual) fields[KnownWords.DEF_MONO] = monolingual
        if (recording) {
            progress(tr("Getting the recording…"))
            library.miner.wordAudio(s.word)?.let { fields["Word Audio"] = "[audio:${anki.addMedia(pkg, it, "kumapie-${library.miner.slug(s.word)}")}]" }
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
