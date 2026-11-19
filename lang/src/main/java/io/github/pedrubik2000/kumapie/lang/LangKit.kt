package io.github.pedrubik2000.kumapie.lang

import android.content.Context
import io.github.pedrubik2000.kumapie.data.Settings

/**
 * The language layer, made once per app run and the same on the TV and the phone/tablet: the Yomitan dictionaries,
 * [Dictionary], [Languages] (their known words) and the [Miner]. Each app made its own before (TvMining's singletons,
 * the phone's Library; the phone's condensed-audio service even had known words of its own, and the TV's
 * Dictionary had no Yomitan dictionaries).
 */
class LangKit private constructor(context: Context, settings: Settings) {
    val yomitan: YomitanDictionaries = YomitanDictionaries.get(context)
    val dictionary = Dictionary(context, yomitan)
    val languages = Languages(context, settings, yomitan, dictionary) { modelDir, fieldsJson ->
        (spacy ?: error("no spaCy in this app"))(modelDir, fieldsJson)
    }
    /** Cards mined here; the word recordings (a few MB a language) are fetched with it. The PC is read at each cut. */
    val miner by lazy {
        if (Dictionary.RECORDINGS.any { !dictionary.recordingsFile(it).exists() }) dictionary.download()
        Miner(context, languages, dictionary) { settings.server }
    }

    companion object {
        /** The phone's German parser (spaCy in Chaquopy, which lives in :mobile); the TV has none. Set before [get]. */
        @Volatile var spacy: Spacy? = null
        @Volatile private var kit: LangKit? = null

        fun get(context: Context, settings: Settings): LangKit =
            kit ?: synchronized(this) { kit ?: LangKit(context.applicationContext, settings).also { kit = it } }
    }
}
