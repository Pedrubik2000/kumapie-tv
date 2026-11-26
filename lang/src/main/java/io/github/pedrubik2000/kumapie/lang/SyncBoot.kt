package io.github.pedrubik2000.kumapie.lang

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SyncBoot : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) DeviceSync.listen(context)
    }
}
