package io.github.pedrubik2000.kumapie.mobile.local

import android.content.Context
import io.github.pedrubik2000.kumapie.i18n.tr
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.lang.AssetWorker
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder

/** "soniox" (best, paid, online) or "parakeet" (free, offline, on the tablet). */
var Settings.transcriber: String
    get() = prefs.getString("transcriber", "soniox") ?: "soniox"
    set(value) = prefs.edit().putString("transcriber", value).apply()

/**
 * NVIDIA's Parakeet TDT 0.6B v3 (int8, sherpa-onnx; CC-BY-4.0) on the device: free, offline speech recognition for
 * new episodes. Tested on the Tab S7+ with a 60 s German Short: 10x faster than real time, 11.8 % of words different
 * from Soniox (which stays the most accurate). Files (~640 MB) come once from Hugging Face; Silero VAD splits the
 * audio at pauses.
 *
 * [japanese]: NVIDIA's Parakeet TDT-CTC 0.6B ja (CTC head, int8; CC-BY-4.0) for Japanese YouTube, its own ~655 MB. The best
 * free model in a 2026-10-14 test on Japanese YouTube with human captions (16 % of characters different; Kitsune, ReazonSpeech
 * and SenseVoice did worse).
 */
class Parakeet(private val context: Context, val japanese: Boolean = false) {
    val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, if (japanese) "models/parakeet-ja" else "models/parakeet")
    private val files get() = if (japanese) FILES_JA else FILES
    val isReady: Boolean get() = files.all { File(dir, it.first).exists() }
    private val work get() = WorkManager.getInstance(context)
    private val workName get() = if (japanese) "$WORK-ja" else WORK

    fun download() {
        work.enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<ParakeetWorker>()
            .setInputData(workDataOf(JA to japanese))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }

    fun state(): Flow<String?> = AssetWorker.state(work, workName)

    /**
     * Japanese: an audio file -> cues [[start, end, text]] as Jimaku's give them: each stretch of speech cut into lines
     * by [splitLines]. No word times: Japanese words come from the splitter, as for subtitles.
     */
    suspend fun cuesJa(audio: File, progress: suspend (String) -> Unit): String {
        progress(tr("Reading the audio…"))
        val rec = OfflineRecognizer(null, OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
            nemo = OfflineNemoEncDecCtcModelConfig(model = f("model")), tokens = f("tokens.txt"), numThreads = 4, modelType = "nemo_ctc")))
        val cues = JSONArray()
        var shown = -1
        try {
            speech(audio) { start, pcm, done ->
                val percent = (done * 100).toInt()
                if (percent != shown) { shown = percent; progress(tr("Transcribing on this device: %d%%", percent)) }
                val st = rec.createStream()
                st.acceptWaveform(pcm, 16000)
                rec.decode(st)
                val r = rec.getResult(st)
                st.release()
                val t0 = start / 16000.0
                splitLines(r.tokens.toList(), r.timestamps.map { t0 + it }, t0 + pcm.size / 16000.0).forEach { (a, b, text) ->
                    cues.put(JSONArray().put(round(a)).put(round(b)).put(text))
                }
            }
        } finally {
            rec.release()
        }
        return cues.toString()
    }

    private fun round(t: Double) = "%.3f".format(java.util.Locale.ROOT, t).toDouble()

    /**
     * An audio file (any format Android decodes) -> the same JSON the Soniox path gives newepisode.cues:
     * {"words": canonical words (word/spacing with start/end), "english": []}.
     */
    suspend fun transcribe(audio: File, progress: suspend (String) -> Unit): String {
        progress(tr("Reading the audio…"))
        val rec = OfflineRecognizer(null, OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(encoder = f("encoder"), decoder = f("decoder"), joiner = f("joiner")),
            tokens = f("tokens.txt"), numThreads = 4, modelType = "nemo_transducer")))
        val words = JSONArray()
        var shown = -1
        try {
            // Decoded, cut at pauses and transcribed a piece at a time: a 104-min film decoded whole ran out of memory.
            speech(audio) { start, pcm, done ->
                val percent = (done * 100).toInt()
                if (percent != shown) { shown = percent; progress(tr("Transcribing on this device: %d%%", percent)) }
                val st = rec.createStream()
                st.acceptWaveform(pcm, 16000)
                rec.decode(st)
                val r = rec.getResult(st)
                st.release()
                val offset = start / 16000.0
                val end = offset + pcm.size / 16000.0
                r.tokens.forEachIndexed { k, raw ->
                    val tok = raw.replace('▁', ' ') // SentencePiece's word start
                    val t0 = offset + r.timestamps.getOrElse(k) { 0f }
                    val t1 = (offset + (r.timestamps.getOrNull(k + 1) ?: (r.timestamps.getOrElse(k) { 0f } + 0.3f))).coerceAtMost(end)
                    // A piece starting with a space begins a new word (as Soniox's tokens).
                    if (tok.startsWith(" ") || k == 0) words.put(word(" ", t0, t0, "spacing"))
                    val text = tok.trimStart()
                    if (text.isNotEmpty()) words.put(word(text, t0, t1, "word"))
                }
                words.put(word(" ", end, end, "spacing"))
            }
        } finally {
            rec.release()
        }
        return JSONObject().put("words", words).put("english", JSONArray()).toString()
    }

    private fun word(text: String, start: Double, end: Double, type: String) = JSONObject()
        .put("text", text).put("start", "%.3f".format(java.util.Locale.ROOT, start).toDouble())
        .put("end", "%.3f".format(java.util.Locale.ROOT, end).toDouble()).put("type", type).put("speaker_id", "speaker_0")

    private fun f(part: String) = dir.listFiles()!!.first { it.name.contains(part) }.path

    /**
     * [audio]'s speech stretches (start sample, 16 kHz samples) of at most 20 s, cut at pauses (Silero VAD), handed to
     * [onSpeech] with the share of the audio read so far, while decoding.
     */
    internal suspend fun speech(audio: File, vadModel: File = File(dir, "silero_vad.onnx"), maxSeconds: Float = 20f,
                                onSpeech: suspend (start: Int, pcm: FloatArray, done: Float) -> Unit) {
        val vad = Vad(null, VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(model = vadModel.path,
            threshold = 0.5f, minSilenceDuration = 0.3f, minSpeechDuration = 0.25f, windowSize = 512, maxSpeechDuration = maxSeconds),
            sampleRate = 16000, numThreads = 1))
        try {
            var done = 0f
            suspend fun drain() { while (!vad.empty()) { val s = vad.front(); vad.pop(); onSpeech(s.start, s.samples, done) } }
            decode16k(audio) { block, read ->
                done = read
                var i = 0
                while (i + 512 <= block.size) { vad.acceptWaveform(block.copyOfRange(i, i + 512)); i += 512 }
                drain()
            }
            vad.flush()
            drain()
        } finally {
            vad.release()
        }
    }

    /**
     * Decodes the audio track to mono 16 kHz floats (MediaCodec, then averaging channels and linear resampling), handed
     * to [block] in pieces of [BLOCK] samples (a multiple of the VAD's 512) with the share of the audio read so far.
     */
    private suspend fun decode16k(file: File, block: suspend (FloatArray, Float) -> Unit) {
        val ex = MediaExtractor().apply { setDataSource(file.path) }
        val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        ex.selectTrack(track)
        val format = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        val mono = FloatArray(BLOCK)
        var size = 0
        var n = 0L          // input samples so far
        var next = 0.0      // the next 16 kHz sample's position, in input samples
        var prev = 0f
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        while (true) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buf = codec.getInputBuffer(inIndex)!!
                    val size = ex.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else if (outIndex >= 0) {
                val shorts = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                val frames = shorts.remaining() / channels
                for (fr in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until channels) sum += shorts.get(fr * channels + c)
                    val v = sum / channels / 32768f
                    if (n == 0L) prev = v
                    while (next <= n) {
                        mono[size++] = prev + (v - prev) * (1 - (n - next)).toFloat()
                        next += rate / 16000.0
                        if (size == BLOCK) {
                            block(mono.copyOf(), if (duration > 0) (n * 1_000_000.0 / rate / duration).toFloat().coerceIn(0f, 1f) else 0f)
                            size = 0
                        }
                    }
                    prev = v
                    n++
                }
                codec.releaseOutputBuffer(outIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        codec.stop(); codec.release(); ex.release()
        block(mono.copyOf(size), 1f)
    }

    companion object {
        private const val WORK = "parakeet-model"
        internal const val JA = "ja"
        private const val HF_JA = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt_ctc-0.6b-ja-35000-int8/resolve/main/"
        private const val BLOCK = 512 * 1024 // ~33 s
        private const val HF = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/"
        /** (file, URL): the model from Hugging Face, Silero VAD from sherpa-onnx's releases. */
        val FILES = listOf(
            "encoder.int8.onnx" to HF + "encoder.int8.onnx",
            "decoder.int8.onnx" to HF + "decoder.int8.onnx",
            "joiner.int8.onnx" to HF + "joiner.int8.onnx",
            "tokens.txt" to HF + "tokens.txt",
            "silero_vad.onnx" to "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
        )
        val FILES_JA = listOf("model.int8.onnx" to HF_JA + "model.int8.onnx", "tokens.txt" to HF_JA + "tokens.txt", FILES.last())
    }
}

/**
 * One stretch of speech -> subtitle lines (start, end, text): a line ends at a sentence mark, or at 、 or a pause
 * once it is long enough; past 7.5 s at the best place after its first 2 s, right after a particle if it can
 * (Silero doesn't cut long speech by itself). Never before hiragana (誤|った: a word's ending). Same rule as
 * kumapie-server's pipeline\parakeet.py.
 */
internal fun splitLines(tokens: List<String>, times: List<Double>, end: Double): List<Triple<Double, Double, String>> {
    val n = tokens.size
    fun after(i: Int) = if (i + 1 < n) times[i + 1] else end
    fun gap(i: Int) = after(i) - times[i]
    fun free(i: Int) = i + 1 < n && tokens[i + 1].trim().firstOrNull().let { it != null && it !in 'ぁ'..'ゖ' && it != 'ー' && it != 'っ' }
    fun score(i: Int) = gap(i) + if (tokens[i].trim().lastOrNull()?.let { it in "はがをにでともへや" } == true) 1.0 else 0.0
    val out = mutableListOf<Triple<Double, Double, String>>()
    var first = 0
    var i = 0
    while (i < n) {
        val dur = after(i) - times[first]
        val tok = tokens[i].trim()
        var cut = i + 1 == n || (dur >= 2.5 && tok in listOf("。", "？", "！", "?", "!")) ||
            (dur >= 3.5 && (tok == "、" || (free(i) && gap(i) >= 0.75)))
        if (!cut && dur >= 7.5) {
            i = (first..i).filter { j -> times[j] - times[first] >= 2 && (free(j) || tokens[j].trim() == "、") }.maxByOrNull(::score) ?: i
            cut = true
        }
        if (cut) {
            val text = tokens.subList(first, i + 1).joinToString("").trim()
            if (text.isNotEmpty()) out.add(Triple(times[first], after(i), text))
            first = i + 1
        }
        i++
    }
    return out
}

/** Downloads Parakeet's files (resumable), each to a .part then renamed. */
class ParakeetWorker(context: Context, params: WorkerParameters) : AssetWorker(context, params) {
    private val parakeet = Parakeet(context, params.inputData.getBoolean(Parakeet.JA, false))
    override val what = if (parakeet.japanese) tr("the Japanese speech model (Parakeet)") else tr("the speech model (Parakeet)")
    override val notificationId = if (parakeet.japanese) 997 else 996

    override suspend fun run() {
        parakeet.dir.mkdirs()
        val files = if (parakeet.japanese) Parakeet.FILES_JA else Parakeet.FILES
        files.forEachIndexed { i, (name, url) ->
            val out = File(parakeet.dir, name)
            if (out.exists()) return@forEachIndexed
            val part = File(parakeet.dir, "$name.part")
            report(tr("File %1\$d of %2\$d", i + 1, files.size), i.toFloat() / files.size)
            fetch(url, part, share = 1f)
            part.renameTo(out)
        }
    }
}
