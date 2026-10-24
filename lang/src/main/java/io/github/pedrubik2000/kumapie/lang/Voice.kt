package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.i18n.tr
import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * The device's own text-to-speech in a language (Google's or Samsung's engine), for words without a recording:
 * works offline once the engine's voice for it is installed (Settings shows whether it is, with a button to the
 * engine's settings).
 */
class Voice(context: Context, private val lang: Lang = Lang.GERMAN) {
    private val cache = File(context.cacheDir, "voice").apply { mkdirs() }
    private var player: android.media.MediaPlayer? = null
    @Volatile private var ready = false
    private var pending: String? = null
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = tts.setLanguage(lang.locale) >= TextToSpeech.LANG_AVAILABLE
            if (ready) pending?.let { speak(it) }
        }
        pending = null
    }

    fun speak(text: String) {
        val n = neural
        if (n != null) { // the downloaded voice says it into a file (kept), played here; else the device's voice
            Thread {
                val f = File(cache, "${lang.code}-${text.hashCode().toUInt()}.wav")
                if (f.exists() || runCatching { n(lang, text, f) }.getOrDefault(false)) play(f) else speakDevice(text)
            }.start()
            return
        }
        speakDevice(text)
    }

    private fun play(f: File) = android.os.Handler(android.os.Looper.getMainLooper()).post {
        player?.release()
        player = android.media.MediaPlayer().apply {
            runCatching { setDataSource(f.path); prepare(); start() }.onFailure { f.delete() }
        }
    }

    private fun speakDevice(text: String) {
        if (!ready) {
            pending = text
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "word")
    }

    /** Says [text] into a WAV file (a mined card's word audio); false if the voice can't. */
    suspend fun toFile(text: String, out: File): Boolean {
        neural?.let { n ->
            if (kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { n(lang, text, out) }.getOrDefault(false) }) return true
        }
        repeat(20) { if (!ready) delay(150) } // the engine may still be starting
        if (!ready) return false
        val id = "file-" + System.nanoTime()
        val done = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { cont ->
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(true) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(false) }
                })
                if (tts.synthesizeToFile(text, null, out, id) != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
            }
        }
        return done == true && out.length() > 0
    }

    fun stop() {
        tts.stop()
        player?.stop()
    }

    companion object {
        /** A better offline voice the app has downloaded (kumapie mobile's NeuralVoices): text into a WAV, false when it has none. */
        @Volatile var neural: ((Lang, String, File) -> Boolean)? = null
    }

    /** For Settings: whether a German voice works without internet. */
    fun describe(): String {
        if (!ready) return tr("No %s voice yet: install one in the speech engine's settings.", lang.displayName)
        val voices = runCatching { tts.voices?.filter { it.locale.language == lang.code } }.getOrNull().orEmpty()
        val offline = voices.any {
            !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
        }
        return if (offline) tr("%1\$s voice: installed, works offline (%2\$s).", lang.displayName, tts.defaultEngine)
        else tr("%1\$s voice: needs internet. Install the %1\$s voice data in the speech engine's settings.", lang.displayName)
    }
}
