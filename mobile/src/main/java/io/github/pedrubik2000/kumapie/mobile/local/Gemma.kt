package io.github.pedrubik2000.kumapie.mobile.local

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.pedrubik2000.kumapie.mobile.lang.AssetWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/**
 * Google's Gemma 4 E2B (Q4_0 GGUF, ~2.8 GB, Apache 2.0) on the device through llama.cpp: free, offline English
 * subtitles for new episodes. Tested on the Tab S7+: about 3 s a line, close to Soniox's translation and far better
 * than Google's on-device translator. llama.cpp's llama-completion ships in the APK as `libllamacli.so`
 * (tools/llama/build.sh) and runs as its own process, one per batch of lines.
 */
class Gemma(private val context: Context) {
    val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "models/gemma")
    val model = File(dir, FILE)
    val isReady: Boolean get() = model.exists()
    private val work get() = WorkManager.getInstance(context)

    fun download() {
        work.enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<GemmaWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }

    fun state(): Flow<String?> = AssetWorker.state(work, WORK)

    /**
     * [language] (German, Japanese…) lines → English, the same count; null where Gemma gave no line (the caller fills those in another way).
     * Batches of [BATCH] consecutive lines, so each line has its neighbours as context.
     */
    suspend fun translate(lines: List<String>, progress: suspend (Int) -> Unit, language: String = "German"): List<String?> {
        val out = ArrayList<String?>(lines.size)
        for (start in lines.indices step BATCH) {
            progress(start * 100 / lines.size.coerceAtLeast(1))
            val batch = lines.subList(start, minOf(start + BATCH, lines.size))
            val prompt = "Translate each numbered $language subtitle into natural English. Keep the meaning faithful. " +
                "Answer with exactly one line per number, in this format and nothing else:\nN. English translation\n\n" +
                batch.mapIndexed { i, l -> "${i + 1}. ${l.replace('\n', ' ')}" }.joinToString("\n")
            val answer = runCatching { complete(prompt, "1.", maxTokens = 60 * batch.size) }
                .onFailure { Log.w("kumapie", "gemma: $it") }.getOrDefault("")
            val got = HashMap<Int, String>()
            "1.$answer".lineSequence().forEach { l ->
                Regex("""^\s*(\d+)\.\s*(.+)$""").find(l)?.let { m -> got.putIfAbsent(m.groupValues[1].toInt(), m.groupValues[2].trim()) }
            }
            batch.indices.forEach { i -> out += got[i + 1]?.takeIf { it.isNotBlank() } }
        }
        return out
    }

    /**
     * One user turn → the model's answer after [prefill] (greedy). Gemma 4's raw turn format; prefilling the answer's
     * first characters keeps it from thinking first.
     */
    suspend fun complete(prompt: String, prefill: String = "", maxTokens: Int = 400): String = withContext(Dispatchers.IO) {
        val input = File(context.cacheDir, "gemma-prompt.txt")
        input.writeText("<|turn>user\n$prompt<turn|>\n<|turn>model\n$prefill")
        val log = File(context.cacheDir, "gemma.log")
        val bin = File(context.applicationInfo.nativeLibraryDir, "libllamacli.so")
        val proc = ProcessBuilder(bin.path, "-m", model.path, "-t", "4", "-c", "4096", "-n", maxTokens.toString(),
            "--temp", "0", "-no-cnv", "--no-display-prompt", "-f", input.path).redirectError(log).start()
        try {
            val text = runInterruptible { proc.inputStream.bufferedReader().readText() }
            val code = runInterruptible { proc.waitFor() }
            if (code != 0) error("llama.cpp exited with $code: " + log.readLines().lastOrNull { it.isNotBlank() })
            text.replace(Regex("""\s*\[end of text]\s*$"""), "").trimEnd() // llama.cpp's end marker
        } finally {
            proc.destroy()
        }
    }

    companion object {
        private const val WORK = "gemma-model"
        private const val BATCH = 12
        const val FILE = "gemma-4-E2B-it-Q4_0.gguf"
        const val URL = "https://huggingface.co/ggml-org/gemma-4-E2B-it-GGUF/resolve/main/$FILE"
    }
}

/** Downloads Gemma's model file (resumable). */
class GemmaWorker(context: Context, params: WorkerParameters) : AssetWorker(context, params) {
    private val gemma = Gemma(context)
    override val what = "the translation model (Gemma)"
    override val notificationId = 995

    override suspend fun run() {
        gemma.dir.mkdirs()
        val part = File(gemma.dir, Gemma.FILE + ".part")
        fetch(Gemma.URL, part, share = 1f)
        part.renameTo(gemma.model)
    }
}

/** For JSON callers: the English cues [[start, end, text]] for [language] cues, with [fill] for lines Gemma missed. */
internal suspend fun Gemma.englishCues(cues: JSONArray, progress: suspend (Int) -> Unit, language: String = "German", fill: suspend (String) -> String): String {
    val german = (0 until cues.length()).map { cues.getJSONArray(it).getString(2) }
    val english = translate(german, progress, language)
    val out = JSONArray()
    german.forEachIndexed { i, de ->
        val c = cues.getJSONArray(i)
        out.put(JSONArray().put(c.getDouble(0)).put(c.getDouble(1)).put(english[i] ?: fill(de)))
    }
    return out.toString()
}
