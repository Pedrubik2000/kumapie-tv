package io.github.pedrubik2000.kumapie.lang

import android.content.Context
import io.github.pedrubik2000.kumapie.data.Settings

object ColourDevice {
    fun answers(settings: Settings) = settings.prefs.getBoolean(DeviceSync.ANSWERS, false)

    fun setAnswers(context: Context, settings: Settings, on: Boolean) {
        settings.prefs.edit().putBoolean(DeviceSync.ANSWERS, on).apply()
        if (on) {
            SyncListener.start(context)
            if (settings.prefs.getBoolean(DeviceSync.BACKGROUND, false)) SyncService.start(context)
        } else {
            SyncListener.stop(context)
            SyncService.stop(context)
        }
    }

    fun peerName(settings: Settings): String? = DeviceSync.with(settings)?.name

    fun kumahonPaired(settings: Settings) = peerName(settings)?.contains("kumahon", ignoreCase = true) == true
}
