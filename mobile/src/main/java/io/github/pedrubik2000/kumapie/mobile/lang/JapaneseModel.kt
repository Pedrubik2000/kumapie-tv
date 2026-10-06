package io.github.pedrubik2000.kumapie.mobile.lang

import android.content.Context
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
class JapaneseModel(private val context: Context) {
    private val base = File(context.getExternalFilesDir(null) ?: context.filesDir, "models")
    val dic = File(base, "sudachi-core-$VERSION.dic")
    val isReady: Boolean get() = dic.exists()
    private val work get() = WorkManager.getInstance(context)

    fun download() {
        val request = OneTimeWorkRequestBuilder<JapaneseModelWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
    }

    fun state(): Flow<String?> = AssetWorker.state(work, WORK)

    /** A word as Sudachi splits it: [base] is its dictionary form (the known-word key), [reading] in katakana. */
    data class Token(val surface: String, val base: String, val reading: String, val pos: String, val begin: Int) {
        /** Punctuation, symbols and blanks: not words. */
        val isWord: Boolean get() = pos != "補助記号" && pos != "空白"
    }

    /** Each text's tokens (mode B), in order. Loads the dictionary on first use (~1 s, ~250 MB). */
    fun parse(texts: List<String>): List<List<Token>> {
        val tok = tokenizer()
        return texts.map { text ->
            tok.tokenize(Tokenizer.SplitMode.B, text).map {
                Token(it.surface(), it.dictionaryForm(), it.readingForm(), it.partOfSpeech()[0], it.begin())
            }
        }
    }

    private fun tokenizer(): Tokenizer = synchronized(Companion) {
        loaded?.takeIf { it.first == dic.path }?.second ?: run {
            check(isReady) { "The Japanese dictionary isn't downloaded yet." }
            val d: Dictionary = DictionaryFactory().create(Config.defaultConfig().systemDictionary(dic.toPath()))
            d.create().also { loaded = dic.path to it }
        }
    }

    companion object {
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
    override val what = "the Japanese dictionary"
    override val notificationId = 994

    override suspend fun run() {
        if (model.isReady) return
        val base = model.dic.parentFile!!.apply { mkdirs() }
        val wheel = File(base, "sudachidict-core.whl.part")
        fetch(JapaneseModel.URL, wheel)
        report("Unpacking…", 0.95f)
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
        if (tmp.length() < 10_000_000) throw IOException("the download has no Sudachi dictionary")
        if (!tmp.renameTo(model.dic)) throw IOException("couldn't move the dictionary into place")
        wheel.delete()
    }
}
