package io.github.pedrubik2000.kumapie.lang

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.pedrubik2000.kumapie.data.Settings

class SyncBoot : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val settings = Settings(context)
        if (settings.server.isEmpty() && settings.prefs.getBoolean(DeviceSync.ANSWERS, false) && settings.prefs.getBoolean(DeviceSync.BACKGROUND, false)) SyncService.start(context)
    }
}
