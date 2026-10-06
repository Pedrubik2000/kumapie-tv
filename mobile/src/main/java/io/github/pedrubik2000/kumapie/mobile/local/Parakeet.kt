package io.github.pedrubik2000.kumapie.mobile.local

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.mobile.lang.AssetWorker
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
 */
class Parakeet(private val context: Context) {
    val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "models/parakeet")
    val isReady: Boolean get() = FILES.all { File(dir, it.first).exists() }
    private val work get() = WorkManager.getInstance(context)

    fun download() {
        work.enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<ParakeetWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }

    fun state(): Flow<String?> = AssetWorker.state(work, WORK)

    /**
     * An audio file (any format Android decodes) -> the same JSON the Soniox path gives newepisode.cues:
     * {"words": canonical words (word/spacing with start/end), "english": []}.
     */
    suspend fun transcribe(audio: File, progress: suspend (String) -> Unit): String {
        progress("Reading the audio…")
        val samples = decode16k(audio)
        progress("Finding speech…")
        val segments = speech(samples)
        val rec = OfflineRecognizer(null, OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(encoder = f("encoder"), decoder = f("decoder"), joiner = f("joiner")),
            tokens = f("tokens.txt"), numThreads = 4, modelType = "nemo_transducer")))
        val words = JSONArray()
        try {
            segments.forEachIndexed { i, (start, pcm) ->
                if (i % 5 == 0) progress("Transcribing on the tablet: ${i * 100 / segments.size}%")
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

    /** Speech stretches (start sample, 16 kHz samples) of at most 20 s, cut at pauses (Silero VAD). */
    internal fun speech(audio: FloatArray, vadModel: File = File(dir, "silero_vad.onnx")): List<Pair<Int, FloatArray>> {
        val vad = Vad(null, VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(model = vadModel.path,
            threshold = 0.5f, minSilenceDuration = 0.3f, minSpeechDuration = 0.25f, windowSize = 512, maxSpeechDuration = 20f),
            sampleRate = 16000, numThreads = 1))
        val out = ArrayList<Pair<Int, FloatArray>>()
        try {
            var i = 0
            while (i + 512 <= audio.size) {
                vad.acceptWaveform(audio.copyOfRange(i, i + 512))
                while (!vad.empty()) { val s = vad.front(); out += s.start to s.samples; vad.pop() }
                i += 512
            }
            vad.flush()
            while (!vad.empty()) { val s = vad.front(); out += s.start to s.samples; vad.pop() }
        } finally {
            vad.release()
        }
        return out
    }

    /** Decodes the audio track to mono 16 kHz floats (MediaCodec, then averaging channels and linear resampling). */
    internal fun decode16k(file: File): FloatArray {
        val ex = MediaExtractor().apply { setDataSource(file.path) }
        val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        ex.selectTrack(track)
        val format = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        // Resampled to 16 kHz while decoding (linear): a whole episode at 48 kHz didn't fit in memory.
        val mono = FloatArrayBuilder()
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
                        mono.add(prev + (v - prev) * (1 - (n - next)).toFloat())
                        next += rate / 16000.0
                    }
                    prev = v
                    n++
                }
                codec.releaseOutputBuffer(outIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        codec.stop(); codec.release(); ex.release()
        return mono.toArray()
    }

    private class FloatArrayBuilder {
        private var data = FloatArray(1 shl 20)
        private var size = 0
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }
        fun toArray() = data.copyOf(size)
    }

    companion object {
        private const val WORK = "parakeet-model"
        private const val HF = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/"
        /** (file, URL): the model from Hugging Face, Silero VAD from sherpa-onnx's releases. */
        val FILES = listOf(
            "encoder.int8.onnx" to HF + "encoder.int8.onnx",
            "decoder.int8.onnx" to HF + "decoder.int8.onnx",
            "joiner.int8.onnx" to HF + "joiner.int8.onnx",
            "tokens.txt" to HF + "tokens.txt",
            "silero_vad.onnx" to "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
        )
    }
}

/** Downloads Parakeet's files (resumable), each to a .part then renamed. */
class ParakeetWorker(context: Context, params: WorkerParameters) : AssetWorker(context, params) {
    private val parakeet = Parakeet(context)
    override val what = "the speech model (Parakeet)"
    override val notificationId = 996

    override suspend fun run() {
        parakeet.dir.mkdirs()
        Parakeet.FILES.forEachIndexed { i, (name, url) ->
            val out = File(parakeet.dir, name)
            if (out.exists()) return@forEachIndexed
            val part = File(parakeet.dir, "$name.part")
            report("File ${i + 1} of ${Parakeet.FILES.size}", i.toFloat() / Parakeet.FILES.size)
            fetch(url, part, share = 1f)
            part.renameTo(out)
        }
    }
}
