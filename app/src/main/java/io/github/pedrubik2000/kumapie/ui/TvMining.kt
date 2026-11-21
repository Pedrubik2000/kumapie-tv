package io.github.pedrubik2000.kumapie.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.view.PixelCopy
import android.view.SurfaceView
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.media3.ui.PlayerView
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.Japanese
import io.github.pedrubik2000.kumapie.lang.LangKit
import io.github.pedrubik2000.kumapie.lang.Language
import io.github.pedrubik2000.kumapie.lang.Languages
import io.github.pedrubik2000.kumapie.lang.Miner
import io.github.pedrubik2000.kumapie.lang.SensePick
import io.github.pedrubik2000.kumapie.lang.YomitanDictionaries
import io.github.pedrubik2000.kumapie.player.SceneController
import io.github.pedrubik2000.kumapie.player.WordPicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

internal fun japaneseKnown(context: Context, settings: Settings) = TvMining.languages(context, settings).japanese.known
internal fun tvLanguages(context: Context, settings: Settings) = TvMining.languages(context, settings)

internal object TvMining {
    @Volatile private var kit: LangKit? = null
    private val ready: LangKit get() = requireNotNull(kit) { "TvMining.languages(context, settings) first" }
    val languages: Languages get() = ready.languages
    val yomitan: YomitanDictionaries get() = ready.yomitan
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val mined = HashMap<String, Long>()
    @Volatile var fetching: String? = null
    @Volatile var failed: Pair<String, Long>? = null
    @Volatile var reading = false
    @android.annotation.SuppressLint("StaticFieldLeak") var web: WebView? = null

    fun recording(context: Context, settings: Settings, surface: String, lang: String): String? {
        languages(context, settings)
        return ready.dictionary.recording(surface, lang)
    }

    fun backend(context: Context, settings: Settings, lang: String) = io.github.pedrubik2000.kumapie.lang.NoPcBackend(
        io.github.pedrubik2000.kumapie.lang.Progress.of(context), lang, { languages(context, settings).of(lang) },
        { surface, l -> recording(context, settings, surface, l) ?: "tts:$surface" }, { languages(context, settings).of(lang).voice.speak(it) })

    fun languages(context: Context, settings: Settings): Languages {
        if (kit == null) {
            kit = LangKit.get(context, settings)
            if (yomitan.of(Lang.JAPANESE).isNotEmpty() && settings.server.isNotEmpty()) fetchJapanese(context, settings)
        }
        return languages
    }

    fun miner(context: Context, settings: Settings): Miner = languages(context, settings).let { ready.miner }
    fun fetchJapanese(context: Context, settings: Settings) {
        if (fetching != null) return
        if (settings.server.isEmpty()) {
            fetching = tr("Downloading the Japanese dictionaries…")
            work.launch {
                failed = null
                runCatching { yomitan.installRecommended(Lang.JAPANESE) { fetching = it }.forEach { Log.i("kumapie", "ja dictionary: $it") } }
                    .onFailure { Log.w("kumapie", "Japanese dictionaries: $it"); failed = (it.message ?: it.toString()) to System.currentTimeMillis() }
                fetching = null
            }
            return
        }
        fetching = tr("Getting the Japanese dictionaries from the PC…")
        val prefs = settings.prefs
        work.launch {
            runCatching {
                val names = yomitan.pcJapanese(settings.server) - prefs.getStringSet(FETCHED, emptySet()).orEmpty()
                names.forEachIndexed { i, name ->
                    fetching = tr("Getting the Japanese dictionaries from the PC (%1\$d/%2\$d)…", i + 1, names.size)
                    yomitan.importFromPc(settings.server, name).forEach { Log.i("kumapie", "ja dictionary: $it") }
                    prefs.edit().putStringSet(FETCHED, prefs.getStringSet(FETCHED, emptySet()).orEmpty() + name).apply()
                }
            }.onFailure { Log.w("kumapie", "Japanese dictionaries: $it") }
            fetching = null
        }
    }

    private const val FETCHED = "ja_dicts_fetched"
}

fun dictionariesState(context: Context, settings: Settings): String {
    TvMining.languages(context, settings)
    TvMining.fetching?.let { return it }
    if (TvMining.yomitan.of(Lang.JAPANESE).isNotEmpty()) return tr("✓ ready")
    val (why, at) = TvMining.failed ?: return tr("Starting…")
    if (System.currentTimeMillis() - at > 15_000) TvMining.fetchJapanese(context, settings)
    return tr("Couldn't download them (%s). Trying again…", why)
}

suspend fun startJapaneseDictionaries(context: Context, settings: Settings) = withContext(Dispatchers.IO) {
    TvMining.languages(context, settings)
    if (TvMining.yomitan.of(Lang.JAPANESE).isEmpty()) TvMining.fetchJapanese(context, settings)
}

internal suspend fun screenFrame(view: PlayerView?, dir: File, name: String = "tv-shot.webp"): File? {
    val surface = view?.videoSurfaceView as? SurfaceView ?: return null
    val main = surface.handler ?: return null
    if (surface.width == 0 || surface.height == 0) return null
    val bitmap = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
    var copied = false
    for (attempt in 0 until 3) {
        if (attempt > 0) kotlinx.coroutines.delay(300)
        copied = suspendCancellableCoroutine { cont ->
            PixelCopy.request(surface, bitmap, { cont.resume(it == PixelCopy.SUCCESS) }, main)
        } && !flat(bitmap)
        if (copied) break
    }
    if (!copied) return null.also { Log.w("kumapie", "screen frame: nothing on the video surface") }
    return withContext(Dispatchers.IO) {
        val out = File(File(dir, "mining").apply { mkdirs() }, name)
        @Suppress("DEPRECATION")
        val webp = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        out.outputStream().use { Bitmap.createScaledBitmap(bitmap, 480 * bitmap.width / bitmap.height, 480, true).compress(webp, 80, it) }
        out
    }
}

internal fun flat(b: Bitmap): Boolean {
    val px = (0 until 64).map { i -> b.getPixel((i % 8 * 2 + 1) * b.width / 16, (i / 8 * 2 + 1) * b.height / 16) }
    fun spread(shift: Int) = px.maxOf { it shr shift and 0xff } - px.minOf { it shr shift and 0xff }
    return spread(16) < 6 && spread(8) < 6 && spread(0) < 6
}

fun mineWord(context: Context, api: io.github.pedrubik2000.kumapie.data.Backend, settings: Settings, ctl: SceneController, picker: WordPicker, scope: CoroutineScope, view: PlayerView?) {
    fun say(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    val segment = picker.selected
    val key = segment?.word ?: return say(tr("Pick a word first"))
    if (ctl.episode.srs) return mineOnPc(context, api as Api, ctl, picker, scope, key)
    val miner = TvMining.miner(context, settings)
    val japanese = TvMining.languages.of(ctl.lang) is Japanese
    if (japanese) {
        TvMining.fetching?.let { return say(it) }
        if (TvMining.yomitan.of(Lang.JAPANESE).isEmpty()) {
            TvMining.fetchJapanese(context, settings)
            return say(if (settings.server.isEmpty()) tr("Downloading the Japanese dictionaries (once): hold ↑ again after")
                else tr("Getting the Japanese dictionaries from the PC (once, a few minutes): hold ↑ again after"))
        }
    }
    val anki = io.github.pedrubik2000.kumapie.lang.Cards.get(context)
    val pkg = TvMining.languages.of(ctl.lang).known.ankiApp() ?: return say(tr("No kuma3 Anki on this device."))
    if (!anki.hasPermission(pkg)) {
        (context as? Activity)?.requestPermissions(arrayOf(anki.permission(pkg)), 1)
        return say(tr("Allow kumapie to use Anki, then hold ↑ again"))
    }
    TvMining.mined[key]?.let { note ->
        if (pkg == io.github.pedrubik2000.kumapie.lang.SrsCards.PKG) return say(tr("Added to %s", ctl.lang.deck))
        val preview = android.content.Intent("com.ichi2.anki.kuma3.OPEN_SCREEN").setPackage(pkg)
            .putExtra("screen", "preview").putExtra("note_id", note)
        return runCatching { context.startActivity(preview) }.getOrElse { say(it.message ?: it.toString()) }
    }
    val scene = ctl.scene
    val line = picker.line
    val word = ctl.words[key]
    val def = scene.def(line, key)
    val lemma = def?.lemma?.takeIf { it.isNotBlank() } ?: word?.lemma?.takeIf { it.isNotBlank() }
    say(if (pkg == io.github.pedrubik2000.kumapie.lang.SrsCards.PKG) tr("Adding the card…") else tr("Adding to Anki…"))
    scope.launch {
        runCatching {
            val w = if (japanese) {
                val cue = scene.cues[line]
                val offset = cue.segments.take(picker.seg).sumOf { it.text.length }
                withContext(Dispatchers.IO) { io.github.pedrubik2000.kumapie.lang.WordCard.japaneseWord(TvMining.languages.japanese, cue.text, offset, segment.text, key, lemma ?: key, word?.reading.orEmpty(), SensePick.english(scene, line)) }
            } else withContext(Dispatchers.IO) {
                io.github.pedrubik2000.kumapie.lang.WordCard.auto(TvMining.languages.of(ctl.lang), TvMining.yomitan, ctl, line, segment.text, key)
            }
            val picture = runCatching { screenFrame(view, context.cacheDir) }.getOrNull()
            miner.mine(Miner.Request(ctl.episode, scene, line, w, picture)) {}
        }
            .onSuccess {
                TvMining.mined[key] = miner.lastNote
                say(if (pkg == io.github.pedrubik2000.kumapie.lang.SrsCards.PKG) it else it + tr(" · hold ↑ again: see the card"))
                ctl.words[key]?.let { w -> ctl.words[key] = w.copy(card = "b") }
                val language = TvMining.languages(context, settings).of(ctl.lang)
                runCatching { language.known.mined(miner.lastNote, ctl, pcColours(ctl)) }.onFailure { Log.w("kumapie", "mined card not read: $it") }
            }
            .onFailure { say(it.message ?: it.toString()) }
    }
}

suspend fun paintCards(context: Context, settings: Settings, ctl: SceneController) {
    val language = TvMining.languages(context, settings).of(ctl.lang)
    if (!runCatching { language.known.readCards() }.onFailure { Log.w("kumapie", "cards: $it") }.getOrDefault(false)) return
    repaint(language, ctl)
    val due = ctl.words.filterValues { it.card == "d" }.keys
    Log.i("kumapie", "kuma3 queue: ${language.known.cardIndex.due?.size} cards (${language.known.cardIndex.due?.values?.groupingBy { it }?.eachCount()}); due words in this episode: " + ctl.episode.scenes.withIndex().flatMap { (i, s) ->
        s.cues.flatMap { it.segments }.mapNotNull { it.word }.filter { it in due }.distinct().map { "$it@${i + 1}" } }.take(40))
}

suspend fun openCards(context: Context, settings: Settings, ctl: SceneController) {
    if (ctl.episode.srs) return
    val language = TvMining.languages(context, settings).of(ctl.lang)
    TvMining.reading = true
    try {
        val t0 = System.currentTimeMillis()
        val saved = runCatching { language.known.loadCards() }.getOrDefault(false)
        if (saved) {
            repaint(language, ctl)
            Log.i("kumapie", "saved cards: words coloured in ${System.currentTimeMillis() - t0} ms, ${ctl.words.values.count { it.card == "d" }} due in this episode")
            runCatching { language.known.syncAnki(90_000) }.onFailure { Log.w("kumapie", "kuma3 sync: $it") }
            paintCards(context, settings, ctl)
        } else {
            paintCards(context, settings, ctl)
            if (runCatching { language.known.syncAnki(90_000) }.getOrDefault(false)) paintCards(context, settings, ctl)
        }
    } finally {
        TvMining.reading = false
    }
}

suspend fun syncAnki(context: Context, settings: Settings, ctl: SceneController, wait: Boolean, busy: () -> Boolean = { false }) {
    if (ctl.episode.srs) return
    val language = TvMining.languages(context, settings).of(ctl.lang)
    val synced = runCatching { language.known.syncAnki(if (wait) 90_000 else 0) }.onFailure { Log.w("kumapie", "kuma3 sync: $it") }.getOrDefault(false)
    if (synced && !busy()) paintCards(context, settings, ctl)
}

fun moveRated(rating: TvRating) {
    val flow = rating.flow ?: return
    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) { flow.leave() }
}

fun syncAnkiLater(context: Context, settings: Settings, ctl: SceneController) {
    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) { syncAnki(context.applicationContext, settings, ctl, wait = false) }
}

internal fun repaint(language: Language, ctl: SceneController) = language.known.repaint(ctl, pcColours(ctl))

internal fun pcColours(ctl: SceneController) = !ctl.episode.id.startsWith("alone:")

suspend fun joinNames(context: Context, settings: Settings, episode: io.github.pedrubik2000.kumapie.data.EpisodeDetail): io.github.pedrubik2000.kumapie.data.EpisodeDetail {
    val language = TvMining.languages(context, settings).of(episode.lang)
    return withContext(Dispatchers.Default) { language.joinNames(episode) }
}
