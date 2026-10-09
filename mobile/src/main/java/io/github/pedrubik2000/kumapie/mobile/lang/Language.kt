package io.github.pedrubik2000.kumapie.mobile.lang

import android.content.Context
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.data.Settings
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
    /** The key a word marked known is kept under in [io.github.pedrubik2000.kumapie.mobile.offline.Progress] (shared by every language). */
    fun markKey(word: String): String
    /** The word a Progress mark key stands for, or null when the mark is another language's. */
    fun wordOfMark(key: String): String?
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
             /** Prefix of this language's marks in Progress ("en:"); German's are bare, as they always were. */
             private val markPrefix: String = "") : Language {
    private val appContext = context.applicationContext
    override val known = KnownWords(context, settings, lang)
    override val voice by lazy { Voice(appContext, lang) }
    override val missing: String? get() = null
    private var player: android.media.MediaPlayer? = null

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
class Japanese(context: Context, settings: Settings, yomitan: YomitanDictionaries, private val scope: CoroutineScope) : Language {
    private val appContext = context.applicationContext
    override val lang: Lang = Lang.JAPANESE
    override val known = KnownWords(context, settings, lang)
    /** Sudachi. */
    val model: JapaneseModel get() = known.japanese
    private val finder by lazy { JapaneseLookup(model, yomitan) }
    override val voice by lazy { Voice(appContext, lang) }
    /** People's recordings of Japanese words (local collection, else JapanesePod101 online). */
    val audio by lazy { JapaneseAudio(appContext) }
    override val missing: String? get() = if (known.modelReady) null else tr("Download the Japanese words dictionary (Sudachi) in Settings first.")

    override suspend fun lookup(tap: Tap): List<Headword> =
        withContext(Dispatchers.Default) { finder.lookup(tap.text, tap.offset, tap.length) }

    override suspend fun entries(tap: Tap): List<DictEntry> = emptyList()

    override fun markKey(word: String) = word
    override fun wordOfMark(key: String) = markWord(key, null)

    override fun say(hw: Headword) {
        scope.launch { if (!audio.play(hw.expression, hw.reading)) voice.speak(hw.reading.ifBlank { hw.expression }) }
    }
}

/** Every language kumapie plays, built once each. */
class Languages(context: Context, settings: Settings, yomitan: YomitanDictionaries, dictionary: Dictionary) {
    /** Word audio plays from here (the main thread). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val german = Spaced(context, settings, Lang.GERMAN, dictionary, scope)
    val english = Spaced(context, settings, Lang.ENGLISH, dictionary, scope, markPrefix = "en:")
    val japanese = Japanese(context, settings, yomitan, scope)
    val all: List<Language> = listOf(german, japanese, english)

    fun of(lang: Lang): Language = all.firstOrNull { it.lang == lang } ?: german
    fun of(code: String): Language = of(Lang.of(code))
    /** The language of a word marked in a [code] episode: kana or kanji in it means Japanese. */
    fun ofWord(word: String, code: String): Language = if (JAPANESE_TEXT.containsMatchIn(word)) japanese else of(code)
}

/** Hiragana, katakana or kanji: a Japanese word. */
private val JAPANESE_TEXT = Regex("[぀-ヿ㐀-鿿]")
/** A Progress mark with a language prefix ("en:word"). */
private val PREFIXED = Regex("^[a-z]{2}:")

/**
 * The word of Progress mark [key] when it is the language's with mark [prefix] ("en:"; "" German, whose marks are bare;
 * null Japanese, told by its script), else null.
 */
internal fun markWord(key: String, prefix: String?): String? = when {
    prefix == null -> key.takeIf { !PREFIXED.containsMatchIn(it) && JAPANESE_TEXT.containsMatchIn(it) }
    prefix.isNotEmpty() -> key.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
    PREFIXED.containsMatchIn(key) || JAPANESE_TEXT.containsMatchIn(key) -> null
    else -> key
}
