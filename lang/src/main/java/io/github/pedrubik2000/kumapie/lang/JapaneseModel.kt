package io.github.pedrubik2000.kumapie.lang

import android.content.Context
import android.util.Log
import io.github.pedrubik2000.kumapie.i18n.tr
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.worksap.nlp.sudachi.Config
import com.worksap.nlp.sudachi.Dictionary
import com.worksap.nlp.sudachi.Tokenizer
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import org.json.JSONObject

/**
 * Japanese words for known-word colours and i+1, like spaCy for German (kumapie_languages_plan.md "Japanese parser
 * research": Sudachi Java + SudachiDict core, mode B). The dictionary (~200 MB) is downloaded once from the
 * sudachidict-core wheel on PyPI: SudachiDict's own zips are in the older format Sudachi Java 0.8 refuses.
 * Jiten's splitter ([JitenSplitter], item 26b) needs two more files from the device's PC (dojo-feed /api/models/):
 * optional, [words] uses it once they are here and Sudachi + [join] until then.
 */
class JapaneseModel(private val context: Context) : Model {
    private val base = File(context.getExternalFilesDir(null) ?: context.filesDir, "models")
    val dic = File(base, "sudachi-core-$VERSION.dic")
    override val label = "Japanese dictionary"
    override val name get() = if (splitterReady) "Sudachi core + Jiten" else "Sudachi core"
    override val about = "The Japanese dictionary (about 80 MB to download, 200 MB on the device, once) splits Japanese into words."
    override val downloadText = "Download the Japanese dictionary"
    override val isReady: Boolean get() = dic.exists()
    /** Jiten's word database and Sudachi user dictionary ([JitenSplitter]). */
    val splitterFiles = SPLITTER_FILES.map { File(base, it) }
    val splitterReady: Boolean get() = isReady && splitterFiles.all { it.exists() }
    private val work get() = WorkManager.getInstance(context)

    /**
     * Leaving the PC (Use without the PC): Jiten's files from [server] now, since without a PC they never come
     * ([JapaneseModelWorker] gets them only from the device's PC) and Sudachi alone split far worse (the no-PC TV,
     * 2026-11-18). Network: off the main thread.
     */
    fun fetchSplitter(server: String) {
        base.mkdirs()
        for (f in splitterFiles) if (!f.exists()) {
            val part = File(f.path + ".part")
            (java.net.URL("$server/api/models/${f.name}").openConnection() as java.net.HttpURLConnection).run {
                connectTimeout = 15_000
                readTimeout = 60_000
                try {
                    if (responseCode != 200) throw java.io.IOException("${f.name}: HTTP $responseCode")
                    inputStream.use { i -> part.outputStream().use { i.copyTo(it) } }
                } finally { disconnect() }
            }
            if (!part.renameTo(f)) throw java.io.IOException("${f.name}: couldn't move it into place")
        }
    }

    override fun download() {
        val request = OneTimeWorkRequestBuilder<JapaneseModelWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
    }

    override fun state(): Flow<String?> = AssetWorker.state(work, WORK)

    /** A word as Sudachi splits it: [base] is its dictionary form (the known-word key), [reading] in katakana. */
    data class Token(val surface: String, val base: String, val reading: String, val pos: String, val begin: Int, val sub: String = "") {
        /** Punctuation, symbols and blanks: not words. */
        val isWord: Boolean get() = pos != "補助記号" && pos != "空白"
    }

    /**
     * Each text's tokens (mode B), in order. Loads the dictionary on first use (~1 s, ~250 MB). One at a time: Sudachi's
     * tokenizer isn't thread-safe (the word card's lookup and its rating rows parse at once: NPE in its lattice).
     */
    fun parse(texts: List<String>): List<List<Token>> = synchronized(Companion) {
        val tok = tokenizer()
        texts.map { tokens(tok, it) }
    }

    /** Each text's words as a learner sees them ([join]): 届いた (届く), できない (できる), でも, 防御力. */
    fun words(texts: List<String>, background: Boolean = false): List<List<Token>> {
        if (!background) building.incrementAndGet()
        try { return wordsOf(texts, background) } finally { if (!background) building.decrementAndGet() }
    }

    /**
     * [words]: one line at a time under [SPLIT] (Sudachi isn't thread-safe; the warm-up and Home's [background] splits run
     * beside a stream's); a [background] split waits while a stream's runs, so it never slows one.
     */
    private fun wordsOf(texts: List<String>, background: Boolean): List<List<Token>> {
        val s = splitter() ?: return parse(texts).map(::join)
        // An episode split before (reopened, resumed): its words from the device. The Chromecast splits ~20 ms a line
        // (Dandadan 9: 374 lines in 7.6-9.7 s of the 10.4 s to its words, 2026-11-19); the key changes with Jiten's files.
        val saved = if (texts.size > 1) File(context.cacheDir, "jiten/" + key(texts, splitterFiles.sumOf { it.lastModified() }) + ".json") else null
        saved?.takeIf { it.exists() }?.let { f -> runCatching { decode(f.readText()) }.getOrNull()?.takeIf { it.size == texts.size } }?.let {
            android.util.Log.i("kumapie", "jiten split: ${texts.size} lines from the device")
            return it
        }
        val t = android.os.SystemClock.elapsedRealtime()
        var first = 0L
        return texts.mapIndexed { i, x ->
            if (background) while (building.get() > 0) Thread.sleep(200)
            synchronized(SPLIT) { s.split(x) }.also { if (i == 0) first = android.os.SystemClock.elapsedRealtime() - t }
        }
            .also { if (texts.size > 1) android.util.Log.i("kumapie", "jiten split: ${texts.size} lines in ${android.os.SystemClock.elapsedRealtime() - t} ms (first $first)") }
            .also { words -> saved?.let { f -> runCatching { f.parentFile?.mkdirs(); f.writeText(encode(words)) } } }
    }

    /** Jiten's splitter once its files are here (one per process: ~95 MB), else null. */
    fun splitter(): JitenSplitter? = if (!splitterReady) null else synchronized(Companion) {
        jiten ?: JitenSplitter(splitterFiles[0], dic, splitterFiles[1]).also { jiten = it }
    }

    /**
     * No PC: Jiten's splitter loads in the background now. Its first split loads the lexicon: 22 s of the 27.5 s the
     * Chromecast took to make a Frieren episode's words (2026-11-18, 228 lines; the other 227 took 5.5 s), paid when the
     * first stream's words were made. Called when the app opens; the build waits on the same lock if it comes first.
     */
    fun warm() {
        if (isReady) download()
        if (!splitterReady) return
        Thread {
            val t = android.os.SystemClock.elapsedRealtime()
            runCatching { splitter()?.let { s -> synchronized(SPLIT) { s.split("あ") } } }.onFailure { android.util.Log.w("kumapie", "jiten splitter warm failed: $it") }
            android.util.Log.i("kumapie", "jiten splitter warm in ${android.os.SystemClock.elapsedRealtime() - t} ms")
        }.apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
    }

    private fun tokenizer(): Tokenizer = synchronized(Companion) {
        loaded?.takeIf { it.first == dic.path }?.second ?: run {
            check(isReady) { tr("The Japanese dictionary isn't downloaded yet.") }
            val d: Dictionary = Dictionary.load(Config.defaultConfig().systemDictionary(SudachiSystem.of(dic.toPath())))
            d.tokenizer().also { loaded = dic.path to it }
        }
    }

    companion object {
        private val SPLIT = Any()
        private val building = java.util.concurrent.atomic.AtomicInteger()

        /** [words]' file name for [texts] split with Jiten's files of [version]. */
        fun key(texts: List<String>, version: Long): String = java.security.MessageDigest.getInstance("SHA-1")
            .digest((version.toString() + "\n" + texts.joinToString("\n")).toByteArray()).joinToString("") { "%02x".format(it) }

        // Lines, tokens and fields split by the ASCII separators (RS, US, GS): never in subtitle text.
        fun encode(words: List<List<Token>>): String = words.joinToString("\u001E") { line ->
            line.joinToString("\u001F") { listOf(it.surface, it.base, it.reading, it.pos, it.begin.toString(), it.sub).joinToString("\u001D") } }

        fun decode(text: String): List<List<Token>> = text.split('\u001E').map { line ->
            if (line.isEmpty()) emptyList() else line.split('\u001F').map { t ->
                t.split('\u001D').let { f -> Token(f[0], f[1], f[2], f[3], f[4].toInt(), f[5]) } } }

        private val HEADS = setOf("動詞", "形容詞")
        val HONORIFIC = setOf("さん", "ちゃん", "くん", "君", "様", "さま", "たち", "達", "殿", "氏")
        private val CONNECTIVE = setOf("て", "で", "ちゃ", "じゃ")

        /** Sudachi's mode-B tokens of [text]. */
        fun tokens(tok: Tokenizer, text: String): List<Token> = tok.tokenize(Tokenizer.SplitMode.B, text).map {
            Token(it.surface(), modern(it.surface(), it.dictionaryForm()), it.readingForm(), it.partOfSpeech()[0], it.begin(), it.partOfSpeech()[1])
        }

        /**
         * Sudachi reads 感じ (感じました, 感じた) as a form of the old verb 感ずる; the word a learner knows, and the one the
         * popup and Jiten's splitter give, is 感じる (信じる, 生じる, 応じる alike). The old forms themselves (感ずる, 感ぜず)
         * keep 感ずる. Found in kumahon, whose code is a port of this one (its 2a768966df, 2026-11-28).
         */
        internal fun modern(surface: String, dictionary: String): String =
            if (dictionary.endsWith("ずる") && surface.startsWith(dictionary.dropLast(2) + "じ")) dictionary.dropLast(2) + "じる" else dictionary

        /**
         * Sudachi's mode-B pieces joined into learner words (prototype and checks: C:\dojo\work\ja-parser\chunks.py):
         * a verb or adjective takes its auxiliaries, て/で and the helper verbs after them (届い+た, 教え+て+い+ませ+ん,
         * し+なさい), keyed by its dictionary form; a noun takes its suffix (防御+力, 素早+さ; not さん/ちゃん), keyed by the
         * whole; だ/で + particle opening a phrase is a conjunction (でも, だって, だけど). Particles stay words of their own.
         */
        fun join(tokens: List<Token>): List<Token> {
            val out = ArrayList<Token>()
            var last = ""          // part of speech of the piece just joined
            var opens = true       // the current word opens a phrase (start, after a space or punctuation)
            var boundary = true
            for (t in tokens) {
                val cur = out.lastOrNull()
                // お/ご (a prefix) + noun or verb is one word keyed by it (お風呂 → 風呂, ご丁寧 → 丁寧, お待ちください → 待つ; item 31).
                if (cur != null && !boundary && cur.pos == "接頭辞" && t.pos in setOf("名詞", "形状詞", "動詞")) {
                    out[out.lastIndex] = cur.copy(surface = cur.surface + t.surface, base = t.base,
                        reading = cur.reading + t.reading, pos = t.pos, sub = t.sub)
                    last = t.pos
                    continue
                }
                var joins: String? = null
                if (cur != null && !boundary) {
                    val head = cur.pos
                    joins = when {
                        t.pos == "接尾辞" && t.surface !in HONORIFIC && head != "助詞" && head != "助動詞" -> "noun"
                        head in HEADS && (t.pos == "助動詞" ||
                            (t.pos == "助詞" && t.sub == "接続助詞" && t.surface in CONNECTIVE && last != "助詞") ||
                            (t.sub == "非自立可能" && t.pos in HEADS && last in setOf("助詞", "助動詞", "動詞"))) -> "inflect"
                        head == "助動詞" && (t.pos == "助動詞" || (t.pos == "形容詞" && t.sub == "非自立可能")) -> "inflect"
                        opens && cur.surface in setOf("で", "だ") && t.pos == "助詞" && t.surface in setOf("も", "って", "けど", "から", "けれど") -> "conj"
                        else -> null
                    }
                }
                when (joins) {
                    "noun" -> out[out.lastIndex] = cur!!.copy(surface = cur.surface + t.surface, base = cur.surface + t.surface,
                        reading = cur.reading + t.reading, pos = "名詞", sub = "")
                    "conj" -> out[out.lastIndex] = cur!!.copy(surface = cur.surface + t.surface, base = cur.surface + t.surface,
                        reading = cur.reading + t.reading, pos = "接続詞", sub = "")
                    "inflect" -> out[out.lastIndex] = cur!!.copy(surface = cur.surface + t.surface, reading = cur.reading + t.reading)
                    else -> { out += t; opens = boundary }
                }
                if (joins == "conj") opens = false
                last = t.pos
                boundary = t.pos == "補助記号" || t.pos == "空白"
            }
            return out
        }

        const val VERSION = "20260723.1"
        const val URL = "https://files.pythonhosted.org/packages/85/af/ba8419f684865b8cca587e01cedb41ba83fbdc985d75ab9e6ff38fdedf1a/" +
            "sudachidict_core-$VERSION-py3-none-any.whl"
        private const val WORK = "japanese-model"
        @Volatile private var loaded: Pair<String, Tokenizer>? = null
        @Volatile private var jiten: JitenSplitter? = null
        /** On the PC: state\dicts, served by dojo-feed's GET /api/models/<file>. */
        val SPLITTER_FILES = listOf("jiten-lexicon.tsv.gz", "jiten_user_core.dic")
    }
}

/** Downloads the sudachidict-core wheel, keeps its system.dic and deletes the wheel. */
class JapaneseModelWorker(context: Context, params: WorkerParameters) : AssetWorker(context, params) {
    private val model = JapaneseModel(context)
    override val what = tr("the Japanese dictionary")
    override val notificationId = 994
    override val showAtStart = false

    override suspend fun run() {
        if (!model.isReady) sudachi()
        splitterFiles()
        resplitLocal()
    }

    /** Jiten's files: the device's PC first when one is set, else (or when it fails) the public pre-release, verified. */
    private suspend fun splitterFiles() {
        val server = io.github.pedrubik2000.kumapie.data.Settings(applicationContext).server
        if (server.isNotEmpty()) {
            try { splitterFromPc(server) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Log.w("kumapie", "PC splitter files: $e") }
            if (model.splitterFiles.all { it.exists() }) return
        }
        splitterFromRelease()
    }

    private suspend fun splitterFromPc(server: String) {
        for (f in model.splitterFiles) {
            if (f.exists()) continue
            val part = File(f.path + ".part")
            fetch("$server/api/models/${f.name}", part)
            if (!part.renameTo(f)) throw IOException(tr("couldn't move the dictionary into place"))
        }
    }

    private suspend fun splitterFromRelease() {
        val dir = model.dic.parentFile!!
        val stamp = File(dir, "jiten-splitter.version")
        val json = File(dir, SplitterManifest.NAME + ".part").apply { delete() }
        val manifest = try {
            fetch(SplitterManifest.BASE + SplitterManifest.NAME, json, quiet = true)
            SplitterManifest.parse(json.readText())
        } catch (e: IOException) {
            keepFilesOr(e)
            return
        } catch (e: org.json.JSONException) {
            keepFilesOr(IOException(tr("the download is damaged, trying again")))
            return
        } finally { json.delete() }
        if (stamp.exists() && stamp.readText() == manifest.version && model.splitterFiles.all { it.exists() }) return
        val fetched = ArrayList<Pair<File, File>>()
        for (entry in manifest.files) {
            val f = File(dir, entry.name)
            if (f.exists() && entry.matches(f)) continue
            val part = File(f.path + "." + entry.sha256.take(12) + ".part")
            if (part.length() > entry.size) part.delete()
            fetch(SplitterManifest.BASE + entry.name, part)
            if (!entry.matches(part)) {
                part.delete()
                throw IOException(tr("the download is damaged, trying again"))
            }
            fetched += part to f
        }
        for ((part, f) in fetched) {
            f.delete()
            if (!part.renameTo(f)) throw IOException(tr("couldn't move the dictionary into place"))
        }
        stamp.writeText(manifest.version)
        val keep = manifest.files.map { "${it.name}.${it.sha256.take(12)}.part" }.toSet()
        dir.listFiles { f -> f.name.endsWith(".part") && f.name !in keep && model.splitterFiles.any { f.name.startsWith(it.name) } }?.forEach { it.delete() }
    }

    private fun keepFilesOr(e: IOException) {
        if (!model.splitterFiles.all { it.exists() }) throw e
    }

    /**
     * Japanese episodes made on this device (local/<id>/episode.json) split before Jiten's splitter: split again once
     * ([resplitEpisode]; stamped, so never twice).
     */
    private suspend fun resplitLocal() {
        val splitter = model.splitter() ?: return
        val local = File(applicationContext.getExternalFilesDir(null) ?: applicationContext.filesDir, "local")
        val files = local.listFiles().orEmpty().map { File(it, "episode.json") }.filter { it.exists() }
        for ((i, f) in files.withIndex()) {
            val json = runCatching { JSONObject(f.readText()) }.getOrNull() ?: continue
            if (json.optString("lang") != "ja" || json.optString("splitter") == JitenSplitter.ID) continue
            report(tr("Splitting episodes again: %1\$d of %2\$d", i + 1, files.size), 0.99f)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(resplitEpisode(json, splitter).toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    private suspend fun sudachi() {
        val base = model.dic.parentFile!!.apply { mkdirs() }
        val wheel = File(base, "sudachidict-core.whl.part")
        fetch(JapaneseModel.URL, wheel)
        report(tr("Unpacking…"), 0.95f)
        val tmp = File(base, "${model.dic.name}.tmp").apply { delete() }
        ZipInputStream(wheel.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "sudachidict_core/resources/system.dic") {
                    tmp.outputStream().use { zip.copyTo(it) }
                    break
                }
            }
        }
        if (tmp.length() < 10_000_000) throw IOException(tr("the download has no Sudachi dictionary"))
        if (!tmp.renameTo(model.dic)) throw IOException(tr("couldn't move the dictionary into place"))
        wheel.delete()
    }
}
