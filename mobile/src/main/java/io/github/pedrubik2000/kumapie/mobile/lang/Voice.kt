package io.github.pedrubik2000.kumapie.mobile.lang

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
        if (!ready) {
            pending = text
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "word")
    }

    /** Says [text] into a WAV file (a mined card's word audio); false if the voice can't. */
    suspend fun toFile(text: String, out: File): Boolean {
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
