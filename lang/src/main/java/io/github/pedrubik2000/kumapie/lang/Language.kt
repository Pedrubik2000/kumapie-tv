package io.github.pedrubik2000.kumapie.lang

import android.content.Context
import androidx.compose.ui.graphics.asImageBitmap
import io.github.pedrubik2000.kumapie.data.EpisodeDetail
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.Word
import io.github.pedrubik2000.kumapie.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What kumapie does differently per language (kumapie_language_map.md), behind one interface: [Spaced] for languages
 * written with spaces (German, English), [Japanese]. Callers ask [Languages] for one and don't branch on the language.
 */
sealed interface Language {
    val lang: Lang
    /** Word colours from kuma3 Anki. */
    val known: KnownWords
    /** The device's voice in this language (words without a recording). */
    val voice: Voice
    /** Why lookups can't run yet (a parser to download), or null. */
    val missing: String?
    /** The popup's headwords for a tap (Yomitan dictionaries). */
    suspend fun lookup(tap: Tap): List<Headword>
    /** The same word as dictionary entries with senses (Wiktionary online when the dictionaries have nothing); empty when the popup is all there is. */
    suspend fun entries(tap: Tap): List<DictEntry>
    /** Says the word: a person's recording first, else [voice]. */
    fun say(hw: Headword)
    /** The key a word marked known is kept under in [io.github.pedrubik2000.kumapie.lang.Progress] (shared by every language). */
    fun markKey(word: String): String
    /** The word a Progress mark key stands for, or null when the mark is another language's. */
    fun wordOfMark(key: String): String?
    /** The parser [known] and new episodes split this language with (downloaded once). */
    val model: Model
    /** Names the parser and its version in [KnownWords]' parse cache: a new one parses again. */
    val parserId: String
    /** The field a note's words are judged by, or null to skip the note. */
    fun judgedField(note: AnkiCards.Note): String?
    /** A word note's keys in [KnownWords.cardIndex] (its own cards: [CardIndex.spacedKeys], [CardIndex.japaneseKeys]). */
    fun cardKeys(note: AnkiCards.Note): List<String>
    /** Each field's word keys (the known-word keys), in the same order. */
    fun wordKeys(fields: List<String>): List<Set<String>>
    /** Known without a card or a mark (Japanese: names and fillers, by Sudachi). */
    fun autoKnown(word: String): Boolean = false
    /** The episode as this device's dictionaries split it further (Japanese: characters' names as one word). */
    fun joinNames(episode: EpisodeDetail): EpisodeDetail = episode
}

/** A language's parser: downloaded once, then [isReady]. Its texts are English keys, translated where they are shown. */
interface Model {
    /** "German model", "Japanese dictionary". */
    val label: String
    /** What is loaded: "de_core_news_lg", "Sudachi core". */
    val name: String
    /** One line before the download (size, what it does), or "". */
    val about: String
    val downloadText: String
    val isReady: Boolean
    fun download()
    /** Download progress, a failure message, or null when no download is running. */
    fun state(): kotlinx.coroutines.flow.Flow<String?>
}

/**
 * A tapped word: [length] characters at [offset] in [text] (the subtitle line, or the word alone), with the episode's
 * morph [key] ("rufe an") and [lemma] when it has them. [length] 0: a tap inside a definition, no word picked yet.
 */
data class Tap(val text: String, val offset: Int, val length: Int = 0, val key: String? = null, val lemma: String? = null) {
    val word: String get() = text.substring(offset.coerceIn(0, text.length), (offset + length).coerceIn(0, text.length))
}

/** One headword: a word with one reading ("" for spaced languages), and every dictionary's entries for it. */
data class Headword(val expression: String, val reading: String, val terms: List<YomitanDictionaries.Term>) {
    val frequencies get() = terms.flatMap { it.frequencies }.distinctBy { it.first }
    val pitches get() = terms.flatMap { it.pitches }.distinctBy { it.first }
}

/** German and English: words are what the episode's segments say; meanings from the Yomitan dictionaries, recordings from Wiktionary. */
class Spaced(context: Context, settings: Settings, override val lang: Lang, private val dictionary: Dictionary, private val scope: CoroutineScope,
             private val spacy: Spacy,
             /** Prefix of this language's marks in Progress ("en:"); German's are bare, as they always were. */
             private val markPrefix: String = "",
             /** German: notes judged by their tags (Core 1000 and mined words by Word, Nicos Weg skipped), as morphs on the PC. */
             private val judgeByTags: Boolean = false) : Language {
    private val appContext = context.applicationContext
    /** spaCy: German de_core_news_lg, English en_core_web_md. */
    override val model = GermanModel(appContext, lang)
    override val parserId get() = "${model.name}-${GermanModel.VERSION}"
    override val known = KnownWords(context, settings, this)
    override val voice by lazy { Voice(appContext, lang) }
    override val missing: String? get() = null
    private var player: android.media.MediaPlayer? = null

    override fun cardKeys(note: AnkiCards.Note) = CardIndex.spacedKeys(note)

    override fun judgedField(note: AnkiCards.Note): String? = when {
        !judgeByTags -> if (note.fields["Word"].isNullOrBlank()) "Sentence" else "Word"
        note.hasTag(KnownWords.CORE1000) || note.hasTag(KnownWords.MINED_WORD) -> "Word"
        note.hasTag(KnownWords.NICOS_WEG) -> null
        else -> "Sentence"
    }

    /** spaCy ([spacy], the same model as morphs on the PC): each field's inflections. */
    override fun wordKeys(fields: List<String>): List<Set<String>> {
        val parsed = org.json.JSONArray(spacy(model.dir.path, org.json.JSONArray(fields).toString()))
        return (0 until parsed.length()).map { i ->
            val morphs = parsed.getJSONArray(i)
            (0 until morphs.length()).map { morphs.getJSONArray(it).getString(1) }.toSet()
        }
    }

    override suspend fun lookup(tap: Tap): List<Headword> = withContext(Dispatchers.IO) {
        if (tap.word.isBlank()) return@withContext emptyList()
        dictionary.yomitanTerms(tap.word, tap.key, tap.lemma, lang).groupBy { it.expression }.map { (e, ts) -> Headword(e, "", ts) }
    }

    override suspend fun entries(tap: Tap): List<DictEntry> =
        if (tap.word.isBlank()) emptyList() else dictionary.lookup(tap.word, tap.key, tap.lemma, lang = lang)

    override fun markKey(word: String) = markPrefix + word
    override fun wordOfMark(key: String) = markWord(key, markPrefix)

    /** A person's recording (Wiktionary / Wikimedia Commons, kept for offline) first, the device voice only without one or when it doesn't play. */
    override fun say(hw: Headword) {
        val word = hw.expression
        scope.launch {
            val rec = withContext(Dispatchers.IO) { dictionary.recording(word, lang.code) }
            val started = rec != null && runCatching {
                player?.release()
                player = android.media.MediaPlayer().apply {
                    setOnPreparedListener { it.start() }
                    setOnCompletionListener { it.release(); if (player === it) player = null }
                    setOnErrorListener { _, _, _ -> voice.speak(word); true }
                    setDataSource(rec)
                    prepareAsync()
                }
            }.isSuccess
            if (!started) voice.speak(word)
            else if (rec!!.startsWith("http")) launch(Dispatchers.IO) { dictionary.keepRecording(word, rec, lang.code) }
        }
    }
}

/** Japanese: Sudachi's word at the tap, then Yomitan's longest match ([JapaneseLookup]); recordings from JapanesePod101. */
class Japanese(context: Context, settings: Settings, val yomitan: YomitanDictionaries, private val scope: CoroutineScope) : Language {
    private val appContext = context.applicationContext
    override val lang: Lang = Lang.JAPANESE
    /** Sudachi. */
    override val model = JapaneseModel(appContext)
    override val parserId get() = "sudachi-B-${JapaneseModel.VERSION}" + if (model.splitterReady) "+${JitenSplitter.ID}" else ""
    override val known = KnownWords(context, settings, this)
    private val finder by lazy { JapaneseLookup(model, yomitan) }
    override val voice by lazy {
        Voice(appContext, lang) { t -> runCatching { finder.lookup(t, 0, t.length).firstOrNull { it.expression == t }?.reading }.getOrNull()?.takeIf { it.isNotBlank() } }
    }
    /** People's recordings of Japanese words (local collection, else JapanesePod101 online). */
    val audio by lazy { JapaneseAudio(appContext) }
    override val missing: String? get() = if (model.isReady) null else tr("Download the Japanese words dictionary (Sudachi) in Settings first.")

    /** Kaishi and mined words: the card's word when it has one, else its sentence. */
    override fun judgedField(note: AnkiCards.Note): String? = if (note.fields["Word"].isNullOrBlank()) "Sentence" else "Word"
    /**
     * [CardIndex.japaneseKeys], the same for the word's other spellings ([JitenSplitter.spellings]) once Jiten's here,
     * and the word bold in its sentence as the subtitles key it ([tapped]).
     */
    override fun cardKeys(note: AnkiCards.Note): List<String> {
        val keys = CardIndex.japaneseKeys(note)
        val (spelling, reading) = keys.firstOrNull()?.split('	') ?: return keys
        val other = runCatching { model.splitter()?.spellings(spelling, reading) }.getOrNull().orEmpty()
        return (keys + other.flatMap { listOf(CardIndex.japaneseKey(it, reading), it) } + tapped(note, spelling)).distinct()
    }

    /**
     * The word bold in the card's sentence (mined: what was tapped, `<b>ワナ</b>` on a 罠[わな] card) and its dictionary
     * form as [JapaneseModel.words] splits it, when it is one word: a word the subtitles write in kana finds its card.
     * Nothing when the bold is the card's own [spelling]: its split can be another word (the noun 覚え as the verb
     * 覚える's stem made 覚え's card colour 覚える, 2026-11-07).
     */
    private fun tapped(note: AnkiCards.Note, spelling: String): List<String> {
        val bold = Regex("""<b>([^<\[]+)""").find(note.fields["Sentence"].orEmpty())?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotEmpty() && it != spelling } ?: return emptyList()
        // No Sudachi (the TVs): the bold as written, or まひ on a 麻痺 card went back to red after mining (user, 2026-11-17).
        if (!model.isReady) return listOf(bold)
        val word = runCatching { model.words(listOf(bold)).first().filter { it.isWord }.singleOrNull() }.getOrNull() ?: return emptyList()
        return listOf(bold, word.base)
    }

    private val auto = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * Names (固有名詞; a character only a character dictionary has) and interjections/fillers (感動詞: えっと, ああ) count as
     * known: Sudachi's part of speech of the word alone.
     */
    override fun autoKnown(word: String): Boolean = model.isReady && auto.getOrPut(word) {
        yomitan.characterName(lang, word) != null || runCatching { model.parse(listOf(word)).first() }.getOrNull()?.singleOrNull()
            ?.let { it.pos == "感動詞" || it.sub == "固有名詞" } ?: false
    }

    /**
     * Characters' names as one word ([JapaneseLookup.joinNames]). Here when an episode opens, not in its stored split: the
     * dictionary is this device's and changes with the person's AniList list.
     */
    override fun joinNames(episode: EpisodeDetail): EpisodeDetail {
        val started = System.currentTimeMillis()
        val scenes = episode.scenes.map { s -> s.copy(cues = s.cues.map { c ->
            c.copy(segments = JapaneseLookup.joinNames(c.segments) { yomitan.characterName(lang, it) })
        }) }
        val names = scenes.flatMap { s -> s.cues.flatMap { c -> c.segments.mapNotNull { it.word } } }.toSet() - episode.words.keys
        // Faces (user, 2026-11-12): names only a character dictionary has, and words Sudachi calls a name (エリス).
        val faceStart = System.currentTimeMillis()
        val keys = scenes.flatMap { s -> s.cues.flatMap { c -> c.segments.mapNotNull { it.word } } }.toSet()
        val portraits = keys.mapNotNull { k ->
            // autoKnown asks characterName too and keeps the answer: the colours need it anyway (the TV has no parser).
            if (!yomitan.isCharacter(lang, k) || !(if (model.isReady) autoKnown(k) else yomitan.characterName(lang, k) != null)) null
            else face(k, episode.work)?.let { k to it }
        }.toMap()
        // Pictures (user, 2026-11-16: イナゴ like the faces): words not known yet that a [Pictures] dictionary has. Known by
        // this device too ([KnownWords.status]): an episode just built here (a no-PC stream) has every word "u", and every
        // word of Dandadan 10 was looked up (pictures 4.4 s of its 8.4 s open, Chromecast 2026-11-19).
        val pictureStart = System.currentTimeMillis()
        val unknown = (keys - portraits.keys - names).filter { episode.words[it]?.let { w -> w.status == "k" || w.marked } != true && known.status(it) != "k" }
        val pictures = unknown.mapNotNull { k -> picture(k)?.let { k to it } }.toMap()
        android.util.Log.i("kumapie", "names: ${names.size} in ${faceStart - started} ms, faces: ${portraits.keys} of ${keys.size} words in " +
            "${pictureStart - faceStart} ms, pictures: ${pictures.size} of ${unknown.size} asked in ${System.currentTimeMillis() - pictureStart} ms")
        // Joined names (猫猫 from the PC's 猫|猫) are characters' names: known, as [autoKnown] says. Red here ignored every
        // mark on the TV, whose colours come only from the PC's split (user 2026-11-16: "marked mao mao a lot of times").
        return episode.copy(scenes = scenes, words = episode.words + names.associateWith { Word("k", "") }, portraits = pictures + portraits)
    }

    /**
     * Faces and pictures of this app run, "work|word" (pictures "|pic|word") -> the small picture or null (none): a
     * dictionary look-up and its media were ~330 ms a face on the Chromecast (Dandadan 10's 9 faces: 3 s of its open,
     * 2026-11-19); Home's prefetch fills it ahead ([warmFaces]). At most [FACES] kept.
     */
    private val faces = object : LinkedHashMap<String, androidx.compose.ui.graphics.ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, androidx.compose.ui.graphics.ImageBitmap>) = size > FACES
    }
    /** The keys with no face or picture (most words): kept apart, so they never push a picture out of [faces]. */
    private val noFace = HashSet<String>()

    private fun cached(key: String, load: () -> ByteArray?): androidx.compose.ui.graphics.ImageBitmap? {
        synchronized(faces) { faces[key]?.let { return it }; if (key in noFace) return null }
        val bitmap = runCatching { load()?.let(::small)?.asImageBitmap() }.getOrNull()
        synchronized(faces) { if (bitmap != null) faces[key] = bitmap else noFace += key }
        return bitmap
    }

    private fun face(word: String, work: String?) = cached("${work.orEmpty()}|$word") { yomitan.characterPortrait(lang, word, work) }
    private fun picture(word: String) = cached("|pic|$word") { yomitan.wordPicture(lang, word) }

    /**
     * Faces and pictures for [words] (an episode's, split ahead on Home: [AloneStream.prefetch]) into [faces], so its
     * open finds them ready. Characters as [joinNames] asks ([autoKnown] keeps its answers); pictures for words not known.
     */
    fun warmFaces(words: Set<String>) {
        for (k in words) {
            if (yomitan.isCharacter(lang, k)) { if (if (model.isReady) autoKnown(k) else yomitan.characterName(lang, k) != null) face(k, "") }
            else if (known.status(k) != "k") picture(k)
        }
    }

    /** A face or picture decoded at 128-255 px (drawn about a line high), not the dictionary's full size. */
    private fun small(bytes: ByteArray): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 128) sample *= 2
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /**
     * Each field's word keys: Sudachi's dictionary forms of its pieces (the keys episodes had before Jiten), and the
     * episodes' own keys ([JapaneseModel.words]: Jiten's words once its splitter is here), so a 黒板消し card makes
     * both 黒板消し and 黒板 known.
     */
    override fun wordKeys(fields: List<String>): List<Set<String>> {
        val texts = fields.map(::plain)
        val words = if (model.splitterReady) model.words(texts) else null
        return model.parse(texts).mapIndexed { i, tokens ->
            (tokens + words?.get(i).orEmpty()).filter { it.isWord }.map { it.base }.toSet()
        }
    }

    /** A Japanese field as plain text: no HTML, no MvJ furigana (" 私[わたし]" -> "私"), no pitch ("私[わたし]:0-" in Word fields), no spaces. */
    private fun plain(field: String): String = android.text.Html.fromHtml(field, 0).toString()
        .replace(Regex("""\[[^\]]*]"""), "").replace(Regex(""":[0-9A-Za-z\-,]*"""), "").replace(Regex("""\s+"""), "")

    override suspend fun lookup(tap: Tap): List<Headword> =
        withContext(Dispatchers.Default) { finder.lookup(tap.text, tap.offset, tap.length) }

    override suspend fun entries(tap: Tap): List<DictEntry> = emptyList()

    /** The rows a tap at [offset] in [text] rates ([JapaneseLookup.forms]). */
    suspend fun forms(text: String, offset: Int): List<JapaneseLookup.Form> =
        withContext(Dispatchers.Default) { finder.forms(text, offset) }

    override fun markKey(word: String) = word
    override fun wordOfMark(key: String) = markWord(key, null)

    override fun say(hw: Headword) {
        // As written, so the voice gives the accent (箸 vs 橋; Pedro 2026-10-24); the reading only for a word it misreads.
        scope.launch { if (!audio.play(hw.expression, hw.reading)) voice.speak(hw.expression, hw.reading.takeIf { it.isNotBlank() }) }
    }
}

/**
 * spaCy, run by the app (Chaquopy works in one module per app): python/german.py's parse_fields(model folder, fields
 * as a JSON list) -> per field, a JSON list of [lemma, inflection] pairs.
 */
typealias Spacy = (modelDir: String, fieldsJson: String) -> String

/** Every language kumapie plays, built once each. */
class Languages(context: Context, settings: Settings, yomitan: YomitanDictionaries, dictionary: Dictionary, spacy: Spacy) {
    /** Word audio plays from here (the main thread). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val german = Spaced(context, settings, Lang.GERMAN, dictionary, scope, spacy, judgeByTags = true)
    val english = Spaced(context, settings, Lang.ENGLISH, dictionary, scope, spacy, markPrefix = "en:")
    val japanese = Japanese(context, settings, yomitan, scope)
    val all: List<Language> = listOf(german, japanese, english)

    fun of(lang: Lang): Language = all.firstOrNull { it.lang == lang } ?: german
    fun of(code: String): Language = of(Lang.of(code))
    /** The language of a word marked in a [code] episode: kana or kanji in it means Japanese. */
    fun ofWord(word: String, code: String): Language = if (JAPANESE_TEXT.containsMatchIn(word)) japanese else of(code)
}

/** Faces and pictures a Japanese language keeps decoded (128-255 px: 150 are ~10-40 MB, outside the Java heap). */
private const val FACES = 150

/** Hiragana, katakana or kanji: a Japanese word. */
private val JAPANESE_TEXT = Regex("[぀-ヿ㐀-鿿]")
/** A Progress mark with a language prefix ("en:word"). */
private val PREFIXED = Regex("^[a-z]{2}:")
/** A Latin letter (German/English words; fullwidth ＫＯ isn't one). */
private val LATIN = Regex("[A-Za-zÀ-ɏ]")

/**
 * The word of Progress mark [key] when it is the language's with mark [prefix] ("en:"; "" German, whose marks are bare;
 * null Japanese, told by its script), else null.
 */
internal fun markWord(key: String, prefix: String?): String? = when {
    // Also 3, ～, ＫＯ (no Latin letter): German claims those bare marks too, harmless; without this Japanese lost them on
    // every sync and they came back red (user 2026-11-16).
    prefix == null -> key.takeIf { !PREFIXED.containsMatchIn(it) && (JAPANESE_TEXT.containsMatchIn(it) || !LATIN.containsMatchIn(it)) }
    prefix.isNotEmpty() -> key.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
    PREFIXED.containsMatchIn(key) || JAPANESE_TEXT.containsMatchIn(key) -> null
    else -> key
}
