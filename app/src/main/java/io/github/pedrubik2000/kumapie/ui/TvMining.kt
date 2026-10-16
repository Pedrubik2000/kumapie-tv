package io.github.pedrubik2000.kumapie.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.util.Log
import android.widget.Toast
import androidx.media3.ui.PlayerView
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.AnkiCards
import io.github.pedrubik2000.kumapie.lang.Dictionary
import io.github.pedrubik2000.kumapie.lang.Headword
import io.github.pedrubik2000.kumapie.lang.Japanese
import io.github.pedrubik2000.kumapie.lang.JapaneseLookup
import io.github.pedrubik2000.kumapie.lang.Languages
import io.github.pedrubik2000.kumapie.lang.Miner
import io.github.pedrubik2000.kumapie.lang.SensePick
import io.github.pedrubik2000.kumapie.lang.YomitanDictionaries
import io.github.pedrubik2000.kumapie.player.SceneController
import io.github.pedrubik2000.kumapie.player.WordPicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.URL
import kotlin.coroutines.resume

/** The TV's mining: built on the first hold ↑ (no spaCy, no Sudachi: the PC's word data is the card's). */
private object TvMining {
    private var miner: Miner? = null
    lateinit var languages: Languages
    lateinit var yomitan: YomitanDictionaries
    /** Outlives the player: the Japanese dictionaries keep coming after it closes. */
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Words mined on this TV since kumapie started -> their notes: hold ↑ again shows the card. */
    val mined = HashMap<String, Long>()
    /** What the Japanese dictionaries' download is doing; null when it isn't running. */
    @Volatile var fetching: String? = null

    fun miner(context: Context, settings: Settings): Miner = miner ?: run {
        val app = context.applicationContext
        yomitan = YomitanDictionaries.get(app)
        val dictionary = Dictionary(app).also { d -> if (Dictionary.RECORDINGS.any { !d.recordingsFile(it).exists() }) d.download() }
        languages = Languages(app, settings, yomitan, dictionary) { _, _ -> error("no spaCy on the TV") }
        Miner(app, languages, dictionary).also { miner = it }
    }

    /** Once: the PC's Japanese Yomitan zips (state\dicts\yomitan-ja, the phone's set) into this TV's dictionaries. */
    fun fetchJapanese(context: Context, server: String) {
        if (fetching != null) return
        fetching = tr("Getting the Japanese dictionaries from the PC…")
        val app = context.applicationContext
        work.launch {
            runCatching {
                val names = JSONArray(URL("$server/api/models/ja-dicts").readText())
                for (i in 0 until names.length()) {
                    val name = names.getString(i)
                    fetching = tr("Getting the Japanese dictionaries from the PC (%1\$d/%2\$d)…", i + 1, names.length())
                    // Named as on the PC: "[Bilingual] JMdict….zip" gives the import its group.
                    val zip = File(app.cacheDir, name)
                    URL("$server/api/models/ja-dicts/${Uri.encode(name)}").openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
                    yomitan.import(listOf(Uri.fromFile(zip)), Lang.JAPANESE) {}.forEach { Log.i("kumapie", "ja dictionary: $it") }
                    zip.delete()
                }
            }.onFailure { Log.w("kumapie", "Japanese dictionaries: $it") }
            fetching = null
        }
    }
}

/**
 * A Japanese word card's word, as the phone's automatic card picks it: the PC's dictionary form ([lemma]) looked up
 * (else the longest match from the word), with the PC's [reading] (else the first) and its pitch, the bilingual meaning that fits the line's
 * [english] (else the first), the first monolingual definition.
 */
private fun japaneseWord(y: YomitanDictionaries, text: String, offset: Int, surface: String, key: String, lemma: String?, reading: String,
                         english: String): Miner.Word {
    val ja = Lang.JAPANESE
    val terms = lemma?.let { y.query(ja, it) }.orEmpty().ifEmpty { y.scan(ja, text.substring(offset.coerceIn(0, text.length))).map { it.second } }
    // The PC's reading picks the entry (行く: いく, not ゆく), else the dictionaries' first.
    val first = terms.firstOrNull { reading.isNotBlank() && JapaneseLookup.hiragana(it.reading) == reading } ?: terms.firstOrNull()
        ?: return Miner.Word(surface, key, lemma ?: surface, "")
    val hw = Headword(first.expression, first.reading, terms.filter { it.expression == first.expression && it.reading == first.reading })
    val glossaries = hw.terms.flatMap { it.glossaries }
    fun group(dict: String) = y.groupOf(ja, dict)
    val bilingual = glossaries.filter { group(it.dict).contains("bilingual", true) }.ifEmpty {
        glossaries.filterNot { group(it.dict).contains("mono", true) || group(it.dict).contains("form", true) || it.dict.contains("form", true) }
    }
    val meanings = bilingual.flatMap { g -> g.senses.flatMap { JapaneseLookup.bilingualMeanings(it) }.distinct().take(12) }.distinct().take(40)
    val gloss = meanings.getOrNull(SensePick.bestMeaning(meanings, english) ?: 0).orEmpty()
    val mono = glossaries.filter { group(it.dict).contains("mono", true) }
        .firstNotNullOfOrNull { g -> g.senses.firstNotNullOfOrNull { JapaneseLookup.monolingualDefinition(it) } }
    return Miner.Word(surface, key, hw.expression, gloss, reading = hw.reading.takeIf { it.isNotBlank() },
        pitch = hw.pitches.flatMap { it.second }.firstOrNull(), mono = mono)
}

/**
 * The video frame on screen, 480 p WebP: a Japanese card's picture. The phone's way (a frame from the stream) gives
 * none on the bedroom TV. Null when the TV won't copy it.
 */
private suspend fun screenFrame(view: PlayerView?, dir: File): File? {
    val surface = view?.videoSurfaceView as? SurfaceView ?: return null
    if (surface.width == 0 || surface.height == 0) return null
    val bitmap = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
    val copied = suspendCancellableCoroutine { cont ->
        PixelCopy.request(surface, bitmap, { cont.resume(it == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
    }
    if (!copied) return null
    return withContext(Dispatchers.IO) {
        val out = File(File(dir, "mining").apply { mkdirs() }, "tv-shot.webp") // the folder kuma3 may read (file_paths.xml)
        @Suppress("DEPRECATION")
        val webp = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        out.outputStream().use { Bitmap.createScaledBitmap(bitmap, 480 * bitmap.width / bitmap.height, 480, true).compress(webp, 80, it) }
        out
    }
}

/**
 * Hold ↑ in the word picker: the selected word as a word card into kuma3 Anki on this TV (its Mined deck), as the
 * phone's automatic card: this line, the scene clip (Japanese: the line's audio and a picture), the word's audio, the
 * meaning; hold ↑ again on it: the card in kuma3's previewer (Back returns). No ratings on the TV (user, 2026-10-16:
 * he only mines there). Japanese meanings come from the phone's
 * dictionaries, fetched from the PC the first time.
 */
fun mineWord(context: Context, settings: Settings, ctl: SceneController, picker: WordPicker, scope: CoroutineScope, view: PlayerView?) {
    fun say(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    val segment = picker.selected
    val key = segment?.word ?: return say(tr("Pick a word first"))
    val miner = TvMining.miner(context, settings)
    val japanese = TvMining.languages.of(ctl.lang) is Japanese
    if (japanese) {
        TvMining.fetching?.let { return say(it) }
        if (TvMining.yomitan.of(Lang.JAPANESE).isEmpty()) {
            TvMining.fetchJapanese(context, settings.server)
            return say(tr("Getting the Japanese dictionaries from the PC (once, a few minutes): hold ↑ again after"))
        }
    }
    val anki = AnkiCards(context)
    val pkg = anki.installed().firstOrNull() ?: return say(tr("No kuma3 Anki on this device."))
    if (!anki.hasPermission(pkg)) {
        (context as? Activity)?.requestPermissions(arrayOf(anki.permission(pkg)), 1)
        return say(tr("Allow kumapie to use Anki, then hold ↑ again"))
    }
    TvMining.mined[key]?.let { note ->
        // kuma3's Kuma3Screens "preview" (kuma3 builds before 2026-10-16 ignore it)
        val preview = android.content.Intent("com.ichi2.anki.kuma3.OPEN_SCREEN").setPackage(pkg)
            .putExtra("screen", "preview").putExtra("note_id", note)
        return runCatching { context.startActivity(preview) }.getOrElse { say(it.message ?: it.toString()) }
    }
    val scene = ctl.scene
    val line = picker.line
    val word = ctl.words[key]
    val def = scene.def(line, key)
    val lemma = def?.lemma?.takeIf { it.isNotBlank() } ?: word?.lemma?.takeIf { it.isNotBlank() }
    say(tr("Adding to Anki…"))
    scope.launch {
        runCatching {
            val w = if (japanese) {
                val cue = scene.cues[line]
                val offset = cue.segments.take(picker.seg).sumOf { it.text.length }
                withContext(Dispatchers.IO) { japaneseWord(TvMining.yomitan, cue.text, offset, segment.text, key, lemma, word?.reading.orEmpty(), SensePick.english(scene, line)) }
            } else {
                val gloss = def?.english?.takeIf { it.isNotBlank() } ?: (scene.meanings[key] ?: word?.meaning)?.substringAfter(" = ").orEmpty()
                Miner.Word(segment.text, key, lemma, gloss, null, def)
            }
            val picture = if (japanese) runCatching { screenFrame(view, context.cacheDir) }.getOrNull() else null
            miner.mine(Miner.Request(ctl.episode, scene, line, w, picture)) {}
        }
            .onSuccess {
                TvMining.mined[key] = miner.lastNote
                say(it + tr(" · hold ↑ again: see the card"))
                // Blue now (mined, never reviewed); the PC learns it from the phone after kuma3 syncs.
                ctl.words[key]?.let { w -> ctl.words[key] = w.copy(card = "b") }
            }
            .onFailure { say(it.message ?: it.toString()) }
    }
}
