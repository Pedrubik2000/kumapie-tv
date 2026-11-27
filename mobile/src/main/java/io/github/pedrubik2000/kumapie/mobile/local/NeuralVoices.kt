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
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.AssetWorker
import io.github.pedrubik2000.kumapie.lang.JapaneseLookup
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * The offline word voice, better than the device's (Pedro's pick, 2026-10-24): VOICEVOX ずんだもん through VOICEVOX
 * core (its ONNX Runtime ships in the APK; Pedro agreed to VOICEVOX's terms 2026-10-24; credit "VOICEVOX:ずんだもん" if
 * shared). Downloaded once (0.vvm + Open JTalk's dictionary), the archive unpacked with Python's tarfile. [io.github.pedrubik2000.kumapie.lang.Voice] uses them before the device's own voice.
 */
class NeuralVoices(private val context: Context) {
    data class Spec(val pkg: String, val name: String, val mb: Int, val sid: Int)

    fun dir(lang: Lang) = File(context.getExternalFilesDir(null) ?: context.filesDir, "models/voice-${lang.code}")
    fun isReady(lang: Lang) = lang == Lang.JAPANESE && File(dir(lang), VVM).exists() && File(dir(lang), DIC).isDirectory
    private val work get() = WorkManager.getInstance(context)

    fun download(lang: Lang) = work.enqueueUniqueWork("voice-${lang.code}", ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<VoiceWorker>().setInputData(workDataOf(LANG to lang.code))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()).build())

    fun state(lang: Lang): Flow<String?> = AssetWorker.state(work, "voice-${lang.code}")

    /**
     * [text] said into the WAV [out]; false when the voice isn't downloaded (the device's voice is used then). Japanese:
     * as written, its dictionary gives the accent; [reading] instead when it would read the word otherwise (風邪薬 カゼヤク).
     */
    fun say(lang: Lang, text: String, out: File, reading: String? = null): Boolean {
        val spec = VOICES[lang] ?: return false
        if (!isReady(lang)) return false
        val wav = synchronized(VOICEVOX) {
            val voice = zundamon()
            val said = voice.createAccentPhrases(text, spec.sid).flatMap { it.moras }.joinToString("") { it.text }
            voice.tts(if (reading != null && !JapaneseLookup.sameSound(said, reading)) reading else text, spec.sid).perform()
        }
        out.writeBytes(wav)
        return wav.size > 44
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
        val VOICES = mapOf(
            Lang.JAPANESE to Spec("voicevox", "ずんだもん", 82, 3),
        )
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
        if (spec == null) return
        val dir = voices.dir(lang).apply { mkdirs() }
        report(tr("Downloading the %s voice", lang.displayName), 0f)
        val vvm = File(dir, NeuralVoices.VVM) // the voice model as it is, then the dictionary (an archive)
        if (!vvm.exists()) File(dir, "${NeuralVoices.VVM}.part").also { fetch(NeuralVoices.VVM_URL, it, share = 0.7f); it.renameTo(vvm) }
        unpack(NeuralVoices.DIC_URL, dir, "dic.tar.gz")
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
