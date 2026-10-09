package io.github.pedrubik2000.kumapie.lang

import android.content.Context
import io.github.pedrubik2000.kumapie.i18n.tr
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.worksap.nlp.sudachi.Config
import com.worksap.nlp.sudachi.Dictionary
import com.worksap.nlp.sudachi.DictionaryFactory
import com.worksap.nlp.sudachi.Tokenizer
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Japanese words for known-word colours and i+1, like spaCy for German (kumapie_languages_plan.md "Japanese parser
 * research": Sudachi Java + SudachiDict core, mode B). The dictionary (~200 MB) is downloaded once from the
 * sudachidict-core wheel on PyPI: SudachiDict's own zips are in the older format Sudachi Java 0.8 refuses.
 */
class JapaneseModel(private val context: Context) : Model {
    private val base = File(context.getExternalFilesDir(null) ?: context.filesDir, "models")
    val dic = File(base, "sudachi-core-$VERSION.dic")
    override val label = "Japanese dictionary"
    override val name = "Sudachi core"
    override val about = "The Japanese dictionary (about 80 MB to download, 200 MB on the device, once) splits Japanese into words."
    override val downloadText = "Download the Japanese dictionary"
    override val isReady: Boolean get() = dic.exists()
    private val work get() = WorkManager.getInstance(context)

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

    /** Each text's tokens (mode B), in order. Loads the dictionary on first use (~1 s, ~250 MB). */
    fun parse(texts: List<String>): List<List<Token>> {
        val tok = tokenizer()
        return texts.map { text ->
            tok.tokenize(Tokenizer.SplitMode.B, text).map {
                Token(it.surface(), it.dictionaryForm(), it.readingForm(), it.partOfSpeech()[0], it.begin(), it.partOfSpeech()[1])
            }
        }
    }

    /** Each text's words as a learner sees them ([join]): 届いた (届く), できない (できる), でも, 防御力. */
    fun words(texts: List<String>): List<List<Token>> = parse(texts).map(::join)

    private fun tokenizer(): Tokenizer = synchronized(Companion) {
        loaded?.takeIf { it.first == dic.path }?.second ?: run {
            check(isReady) { tr("The Japanese dictionary isn't downloaded yet.") }
            val d: Dictionary = DictionaryFactory().create(Config.defaultConfig().systemDictionary(dic.toPath()))
            d.create().also { loaded = dic.path to it }
        }
    }

    companion object {
        private val HEADS = setOf("動詞", "形容詞")
        private val HONORIFIC = setOf("さん", "ちゃん", "くん", "君", "様", "さま", "たち", "達", "殿", "氏")
        private val CONNECTIVE = setOf("て", "で", "ちゃ", "じゃ")

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
                // お/ご (a prefix) + noun is one word (お風呂, ご丁寧); + verb joins the verb (お待ちください → 待つ).
                if (cur != null && !boundary && cur.pos == "接頭辞" && t.pos in setOf("名詞", "形状詞", "動詞")) {
                    val noun = t.pos != "動詞"
                    out[out.lastIndex] = cur.copy(surface = cur.surface + t.surface, base = if (noun) cur.surface + t.surface else t.base,
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
    }
}

/** Downloads the sudachidict-core wheel, keeps its system.dic and deletes the wheel. */
class JapaneseModelWorker(context: Context, params: WorkerParameters) : AssetWorker(context, params) {
    private val model = JapaneseModel(context)
    override val what = tr("the Japanese dictionary")
    override val notificationId = 994

    override suspend fun run() {
        if (model.isReady) return
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
