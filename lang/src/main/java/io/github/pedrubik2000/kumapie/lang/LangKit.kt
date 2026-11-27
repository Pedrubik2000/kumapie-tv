package io.github.pedrubik2000.kumapie.lang

import android.content.Context
import io.github.pedrubik2000.kumapie.data.Settings

/**
 * The language layer, made once per app run and the same on the TV and the phone/tablet: the Yomitan dictionaries,
 * [Languages] (their known words) and the [Miner]. Each app made its own before (TvMining's singletons, the phone's
 * Library; the phone's condensed-audio service even had known words of its own).
 */
class LangKit private constructor(context: Context, settings: Settings) {
    val yomitan: YomitanDictionaries = YomitanDictionaries.get(context)
    val languages = Languages(context, settings, yomitan)
    /** Cards mined here. The PC is read at each cut. */
    val miner by lazy { Miner(context, languages) { settings.server } }

    companion object {
        @Volatile private var kit: LangKit? = null

        fun get(context: Context, settings: Settings): LangKit =
            kit ?: synchronized(this) { kit ?: LangKit(context.applicationContext, settings).also { kit = it } }
    }
}
