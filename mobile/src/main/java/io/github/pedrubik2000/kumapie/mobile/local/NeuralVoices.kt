package io.github.pedrubik2000.kumapie.mobile.local

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.AssetWorker
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Offline word voices better than the device's (Pedro's picks, 2026-10-24): German = Piper Thorsten (high), English =
 * Kokoro Michael, both through sherpa-onnx (already here for Parakeet), Japanese = VOICEVOX ずんだもん through VOICEVOX
 * core (its ONNX Runtime ships in the APK; Pedro agreed to VOICEVOX's terms 2026-10-24; credit "VOICEVOX:ずんだもん" if
 * shared). Each is downloaded once (sherpa-onnx packages / 0.vvm + Open JTalk's dictionary), archives unpacked with
 * Python's tarfile. [io.github.pedrubik2000.kumapie.lang.Voice] uses them before the device's own voice.
 */
class NeuralVoices(private val context: Context) {
    data class Spec(val pkg: String, val name: String, val mb: Int, val sid: Int)

    fun dir(lang: Lang) = File(context.getExternalFilesDir(null) ?: context.filesDir, "models/voice-${lang.code}")
    private fun home(lang: Lang) = VOICES[lang]?.let { File(dir(lang), it.pkg) }
    fun isReady(lang: Lang) = if (lang == Lang.JAPANESE) File(dir(lang), VVM).exists() && File(dir(lang), DIC).isDirectory
        else home(lang)?.let { File(it, "tokens.txt").exists() } == true
    private val work get() = WorkManager.getInstance(context)

    fun download(lang: Lang) = work.enqueueUniqueWork("voice-${lang.code}", ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<VoiceWorker>().setInputData(workDataOf(LANG to lang.code))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()).build())

    fun state(lang: Lang): Flow<String?> = AssetWorker.state(work, "voice-${lang.code}")

    /** [text] said into the WAV [out]; false when the voice isn't downloaded (the device's voice is used then). */
    fun say(lang: Lang, text: String, out: File): Boolean {
        val spec = VOICES[lang] ?: return false
        if (!isReady(lang)) return false
        if (lang == Lang.JAPANESE) {
            val wav = synchronized(VOICEVOX) { zundamon().tts(text, spec.sid).perform() }
            out.writeBytes(wav)
            return wav.size > 44
        }
        val tts = synchronized(engines) {
            engines.getOrPut(lang) {
                val d = home(lang)!!
                val model = if (lang == Lang.GERMAN) OfflineTtsModelConfig(vits = OfflineTtsVitsModelConfig(
                    model = File(d, "de_DE-thorsten-high.onnx").path, tokens = File(d, "tokens.txt").path,
                    dataDir = File(d, "espeak-ng-data").path), numThreads = 2)
                else OfflineTtsModelConfig(kokoro = OfflineTtsKokoroModelConfig(model = File(d, "model.onnx").path,
                    voices = File(d, "voices.bin").path, tokens = File(d, "tokens.txt").path,
                    dataDir = File(d, "espeak-ng-data").path), numThreads = 2)
                OfflineTts(null, OfflineTtsConfig(model = model, maxNumSentences = 1))
            }
        }
        val audio = synchronized(tts) { tts.generate(text, spec.sid, 1.0f) }
        return audio.samples.isNotEmpty() && audio.save(out.path)
    }

    /** VOICEVOX core with ずんだもん's model, made once (loading takes a few seconds). */
    private fun zundamon(): jp.hiroshiba.voicevoxcore.blocking.Synthesizer = synthesizer ?: run {
        val ort = jp.hiroshiba.voicevoxcore.blocking.Onnxruntime.loadOnce()
            .filename(File(context.applicationInfo.nativeLibraryDir, "libvoicevox_onnxruntime.so").path).perform()
        val s = jp.hiroshiba.voicevoxcore.blocking.Synthesizer.builder(ort,
            jp.hiroshiba.voicevoxcore.blocking.OpenJtalk(File(dir(Lang.JAPANESE), DIC).path)).cpuNumThreads(4).build()
        val model = jp.hiroshiba.voicevoxcore.blocking.VoiceModelFile(File(dir(Lang.JAPANESE), VVM).path)
        s.loadVoiceModel(model).perform() // the model file is dropped by its finalizer (closing it too logs a double free)
        s.also { synthesizer = it }
    }

    companion object {
        const val LANG = "lang"
        const val VVM = "0.vvm" // 四国めたん, ずんだもん, 春日部つむぎ, 雨晴はう (voicevox_vvm)
        const val DIC = "open_jtalk_dic_utf_8-1.11"
        val VVM_URL = "https://github.com/VOICEVOX/voicevox_vvm/releases/download/0.17.0/$VVM"
        val DIC_URL = "https://github.com/r9y9/open_jtalk/releases/download/v1.11.1/$DIC.tar.gz"
        private val VOICEVOX = Any()
        @Volatile private var synthesizer: jp.hiroshiba.voicevoxcore.blocking.Synthesizer? = null
        private const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"
        val VOICES = mapOf(
            Lang.GERMAN to Spec("vits-piper-de_DE-thorsten-high", "Thorsten", 115, 0),
            Lang.ENGLISH to Spec("kokoro-en-v0_19", "Michael", 320, 6),
            Lang.JAPANESE to Spec("voicevox", "ずんだもん", 82, 3),
        )
        private val engines = HashMap<Lang, OfflineTts>()
        fun url(spec: Spec) = "$URL${spec.pkg}.tar.bz2"
    }
}

/** Downloads a voice package (resumable) and unpacks it next to where it's used. */
class VoiceWorker(context: Context, params: WorkerParameters) : AssetWorker(context, params) {
    private val lang = Lang.of(params.inputData.getString(NeuralVoices.LANG))
    private val voices = NeuralVoices(context)
    private val spec = NeuralVoices.VOICES[lang]
    override val what = tr("the %s voice", lang.displayName)
    override val notificationId = 995

    override suspend fun run() {
        val spec = spec ?: return
        val dir = voices.dir(lang).apply { mkdirs() }
        report(tr("Downloading the %s voice", lang.displayName), 0f)
        if (lang == Lang.JAPANESE) { // the voice model as it is, then the dictionary (an archive)
            val vvm = File(dir, NeuralVoices.VVM)
            if (!vvm.exists()) File(dir, "${NeuralVoices.VVM}.part").also { fetch(NeuralVoices.VVM_URL, it, share = 0.7f); it.renameTo(vvm) }
            unpack(NeuralVoices.DIC_URL, dir, "dic.tar.gz")
        } else unpack(NeuralVoices.url(spec), dir, "${spec.pkg}.tar.bz2")
    }

    private suspend fun unpack(url: String, dir: File, name: String) {
        val part = File(dir, "$name.part")
        fetch(url, part, share = 0.9f)
        report(tr("Unpacking the %s voice", lang.displayName), 0.95f)
        if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
        val tar = Python.getInstance().getModule("tarfile").callAttr("open", part.path)
        try { tar.callAttr("extractall", dir.path) } finally { tar.callAttr("close") }
        part.delete()
    }
}
