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
 * Kokoro Michael, both through sherpa-onnx (already here for Parakeet). Each is a sherpa-onnx package (model + espeak-ng
 * data) downloaded once and unpacked with Python's tarfile. [io.github.pedrubik2000.kumapie.lang.Voice] uses them
 * before the device's own voice. Japanese (VOICEVOX ずんだもん) comes from the PC for now.
 */
class NeuralVoices(private val context: Context) {
    data class Spec(val pkg: String, val name: String, val mb: Int, val sid: Int)

    fun dir(lang: Lang) = File(context.getExternalFilesDir(null) ?: context.filesDir, "models/voice-${lang.code}")
    private fun home(lang: Lang) = VOICES[lang]?.let { File(dir(lang), it.pkg) }
    fun isReady(lang: Lang) = home(lang)?.let { File(it, "tokens.txt").exists() } == true
    private val work get() = WorkManager.getInstance(context)

    fun download(lang: Lang) = work.enqueueUniqueWork("voice-${lang.code}", ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<VoiceWorker>().setInputData(workDataOf(LANG to lang.code))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()).build())

    fun state(lang: Lang): Flow<String?> = AssetWorker.state(work, "voice-${lang.code}")

    /** [text] said into the WAV [out]; false when the voice isn't downloaded (the device's voice is used then). */
    fun say(lang: Lang, text: String, out: File): Boolean {
        val spec = VOICES[lang] ?: return false
        if (!isReady(lang)) return false
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

    companion object {
        const val LANG = "lang"
        private const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"
        val VOICES = mapOf(
            Lang.GERMAN to Spec("vits-piper-de_DE-thorsten-high", "Thorsten", 115, 0),
            Lang.ENGLISH to Spec("kokoro-en-v0_19", "Michael", 320, 6),
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
        val part = File(dir, "${spec.pkg}.tar.bz2.part")
        report(tr("Downloading the %s voice", lang.displayName), 0f)
        fetch(NeuralVoices.url(spec), part, share = 0.9f)
        report(tr("Unpacking the %s voice", lang.displayName), 0.95f)
        if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
        val tar = Python.getInstance().getModule("tarfile").callAttr("open", part.path)
        try { tar.callAttr("extractall", dir.path) } finally { tar.callAttr("close") }
        part.delete()
    }
}
