package io.github.pedrubik2000.kumapie.lang

import com.worksap.nlp.sudachi.Config
import com.worksap.nlp.sudachi.DictionaryFactory
import com.worksap.nlp.sudachi.Tokenizer
import io.github.pedrubik2000.kumapie.lang.jiten.DeckWord
import io.github.pedrubik2000.kumapie.lang.jiten.JitenLexicon
import io.github.pedrubik2000.kumapie.lang.jiten.JitenParser
import io.github.pedrubik2000.kumapie.lang.jiten.JmDictFormType
import io.github.pedrubik2000.kumapie.lang.jiten.KanaConverter
import io.github.pedrubik2000.kumapie.lang.jiten.MorphologicalAnalyser
import io.github.pedrubik2000.kumapie.lang.jiten.PartOfSpeech
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Japanese words as Jiten splits them (kumapie_plan.md item 26b: the Kotlin port in jiten/), as [JapaneseModel.Token]s
 * covering the whole text: words, and the text between them as non-words, so a line's segments still add up to it.
 * The port loads on the first [split] (~95 MB heap, a few seconds on a phone): never call it on the main thread.
 * One split at a time (the port isn't thread-safe).
 *
 * A word's [JapaneseModel.Token.base] (its known-word key) is the JMdict form Jiten picked for it (`readingIndex`):
 * the dictionary form as written (呼ばれちゃった → 呼ぶ, わかった → わかる, 黒板消し → 黒板消し, ガク → ガク), the same kind of key
 * Sudachi's dictionary form was, so known words, marks and mined cards keyed by dictionary form keep matching.
 */
class JitenSplitter(private val lexicon: File, private val systemDic: File, private val userDic: File?) {
    private var parser: JitenParser? = null
    private var sudachi: Tokenizer? = null

    private fun parser(): JitenParser = parser ?: lexicon.inputStream().use { JitenLexicon.load(it) }.let { lex ->
        JitenParser(lex, MorphologicalAnalyser(lex, systemDic.toPath(), userDic?.toPath()))
    }.also { parser = it }

    /**
     * The other spellings of a card's word: every form of its JMdict entry ([spelling] with [reading]; the only entry
     * written so when the reading doesn't pick one), so a ガキ共 card colours ガキども and 餓鬼ども, a 分かる card わかる
     * (user, 2026-10-13). A kana form only when no other entry is written so (かみ is 紙, 神 and 髪: never added).
     */
    @Synchronized
    fun spellings(spelling: String, reading: String): Set<String> {
        val lex = parser().lexicon
        fun ids(text: String) = (lex.lookup(text)?.toList().orEmpty() + lex.lookup(KanaConverter.toHiragana(text))?.toList().orEmpty())
            .distinct().filterNot(lex::isNameOnly)
        val read = KanaConverter.toHiragana(reading)
        val entries = ids(spelling).mapNotNull(lex::getWord).filter { w -> w.forms.any { it.text == spelling } }
        val words = entries.filter { w -> w.forms.any { it.formType == JmDictFormType.KanaForm && KanaConverter.toHiragana(it.text) == read } }
            .ifEmpty { entries.takeIf { it.size == 1 }.orEmpty() }
        return words.flatMap { w ->
            w.forms.filter { f -> !f.isSearchOnly && (f.formType == JmDictFormType.KanjiForm || ids(f.text) == listOf(w.wordId)) }
                .map { it.text }
        }.toSet() - spelling
    }

    @Synchronized
    fun split(text: String): List<JapaneseModel.Token> {
        val p = parser()
        val words = runCatching { p.parseText(text) }.getOrElse { emptyList() }
        val out = ArrayList<JapaneseModel.Token>()
        var at = 0
        for (w in words) {
            val surface = w.originalText
            val found = if (surface.isEmpty()) -1 else text.indexOf(surface, at)
            if (found < 0) continue // not in the text as written: its text stays in the gap
            gap(text, at, found, out)
            out += token(p, w, found)
            at = found + surface.length
        }
        gap(text, at, text.length, out)
        return joinPrefix(out)
    }

    /** Text Jiten gave no word for: Sudachi's words there when it has Japanese in it (never dropped), else one non-word. */
    private fun gap(text: String, from: Int, to: Int, out: MutableList<JapaneseModel.Token>) {
        if (from >= to) return
        val piece = text.substring(from, to)
        if (!JAPANESE.containsMatchIn(piece)) {
            out += JapaneseModel.Token(piece, piece, "", if (piece.isBlank()) "空白" else "補助記号", from)
            return
        }
        val tok = sudachi ?: DictionaryFactory().create(Config.defaultConfig().systemDictionary(systemDic.toPath())).create()
            .also { sudachi = it }
        JapaneseModel.join(JapaneseModel.tokens(tok, piece)).forEach { out += it.copy(begin = it.begin + from) }
    }

    private fun token(p: JitenParser, w: DeckWord, begin: Int): JapaneseModel.Token {
        // Kana as written (ガク, not がく: Sudachi's dictionary forms kept the script too).
        val form = p.lexicon.getWord(w.wordId)?.forms?.getOrNull(w.readingIndex)?.text
            ?.takeUnless { katakana(it) == katakana(w.originalText) }
        val pos = w.partsOfSpeech.firstOrNull() ?: w.sudachiPartOfSpeech
        return JapaneseModel.Token(w.originalText, form ?: w.originalText, katakana(w.sudachiReading), SUDACHI_POS[pos] ?: "名詞", begin,
            if (pos == PartOfSpeech.Name) "固有名詞" else "")
    }

    companion object {
        /** Stamped on episodes split with this ([resplitEpisode]); a new one re-splits them. */
        const val ID = "jiten-2"
        private val JAPANESE = Regex("[぀-ヿ㐀-鿿々]")

        private val PREFIX = setOf("お", "ご", "御")
        private val JOINS = setOf("名詞", "形状詞", "形容詞", "動詞")

        /**
         * A bare お/ご/御 (a prefix Jiten left alone because JMdict has no お-form) + the noun, adjective or verb right
         * after it is one word keyed by that word (お料理 → 料理, ご理解 → 理解, お優しい → 優しい, お待たせ → 待たせる; not names or
         * pronouns): kumapie_plan.md item 31. Words JMdict has whole (お茶, お風呂) are already Jiten's.
         */
        fun joinPrefix(tokens: List<JapaneseModel.Token>): List<JapaneseModel.Token> {
            val out = ArrayList<JapaneseModel.Token>()
            for (t in tokens) {
                val p = out.lastOrNull()
                if (p != null && p.pos == "接頭辞" && p.surface in PREFIX && p.begin + p.surface.length == t.begin &&
                    t.pos in JOINS && t.sub != "固有名詞")
                    out[out.lastIndex] = t.copy(surface = p.surface + t.surface, reading = p.reading + t.reading, begin = p.begin)
                else out += t
            }
            return out
        }

        /** Readings in katakana, as Sudachi gives them (Jiten's are sometimes hiragana). */
        private fun katakana(s: String) = buildString { for (c in s) append(if (c in 'ぁ'..'ゖ') c + 0x60 else c) }

        /** Jiten's parts of speech as Sudachi's (what the app checks: 補助記号/空白 are not words). */
        private val SUDACHI_POS = mapOf(
            PartOfSpeech.Verb to "動詞", PartOfSpeech.IAdjective to "形容詞", PartOfSpeech.NaAdjective to "形状詞",
            PartOfSpeech.Adverb to "副詞", PartOfSpeech.AdverbTo to "副詞", PartOfSpeech.Particle to "助詞",
            PartOfSpeech.Auxiliary to "助動詞", PartOfSpeech.Conjunction to "接続詞", PartOfSpeech.Adnominal to "連体詞",
            PartOfSpeech.PrenounAdjectival to "連体詞", PartOfSpeech.Interjection to "感動詞", PartOfSpeech.Filler to "感動詞",
            PartOfSpeech.Prefix to "接頭辞", PartOfSpeech.Suffix to "接尾辞", PartOfSpeech.NounSuffix to "接尾辞",
            PartOfSpeech.Pronoun to "代名詞", PartOfSpeech.Symbol to "補助記号", PartOfSpeech.SupplementarySymbol to "補助記号",
            PartOfSpeech.BlankSpace to "空白",
        )

        /**
         * A cue's segments as newepisode.py's segments() makes them: [surface, key] per word, [text, null] between.
         */
        fun segments(text: String, tokens: List<JapaneseModel.Token>): JSONArray {
            val out = JSONArray()
            var pos = 0
            for (t in tokens.sortedBy { it.begin }) {
                if (!t.isWord || t.begin < pos) continue
                if (t.begin > pos) out.put(JSONArray().put(text.substring(pos, t.begin)).put(JSONObject.NULL))
                out.put(JSONArray().put(t.surface).put(t.base))
                pos = t.begin + t.surface.length
            }
            if (pos < text.length) out.put(JSONArray().put(text.substring(pos)).put(JSONObject.NULL))
            return out
        }
    }
}

/**
 * An episode (episode.json / .kumapie.json) split again with [split]: every target-language cue's segments from its
 * text, and the words map rebuilt as newepisode.py builds it (in order of first use; a key that was there keeps its
 * entry: s, g, lemma, ...; a new one gets newepisode's {"s": "u", "g": ""}), stamped "splitter": [JitenSplitter.ID].
 * Changes [json] in place and returns it.
 */
fun resplitEpisode(json: JSONObject, split: (String) -> List<JapaneseModel.Token>): JSONObject {
    val old = json.optJSONObject("words") ?: JSONObject()
    val words = JSONObject()
    val scenes = json.optJSONArray("scenes") ?: JSONArray()
    for (i in 0 until scenes.length()) {
        val scene = scenes.getJSONObject(i)
        if (!scene.optBoolean("german", true)) continue // a scene not in the language: one plain segment per cue
        val cues = scene.optJSONArray("cues") ?: continue
        for (j in 0 until cues.length()) {
            val cue = cues.getJSONObject(j)
            val seg = cue.optJSONArray("seg") ?: continue
            val text = buildString { for (k in 0 until seg.length()) append(seg.getJSONArray(k).getString(0)) }
            val fresh = JitenSplitter.segments(text, split(text))
            cue.put("seg", fresh)
            for (k in 0 until fresh.length()) {
                val key = fresh.getJSONArray(k).opt(1) as? String ?: continue
                if (!words.has(key)) words.put(key, old.optJSONObject(key) ?: JSONObject().put("s", "u").put("g", ""))
            }
        }
    }
    json.put("words", words)
    json.put("splitter", JitenSplitter.ID)
    return json
}

/** [resplitEpisode] with a splitter. */
fun resplitEpisode(json: JSONObject, splitter: JitenSplitter): JSONObject = resplitEpisode(json, splitter::split)
