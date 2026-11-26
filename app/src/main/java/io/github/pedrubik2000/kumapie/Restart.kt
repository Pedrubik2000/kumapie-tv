package io.github.pedrubik2000.kumapie

import android.os.Process
import android.os.SystemClock
import io.github.pedrubik2000.kumapie.data.Playable
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.lang.DeviceSync

object Restart {
    const val FORCE = "debug_narrow_restart"
    private const val SETTLE_MS = 2_000L
    private const val SYNC_MAX_MS = 10_000L
    private const val POLL_MS = 200L

    fun wanted(settings: Settings): Boolean = Playable.narrow || BuildConfig.DEBUG && settings.prefs.getBoolean(FORCE, false)

    fun now(settings: Settings) = Thread {
        val until = SystemClock.uptimeMillis() + SYNC_MAX_MS
        Thread.sleep(SETTLE_MS)
        while (DeviceSync.busy && SystemClock.uptimeMillis() < until) Thread.sleep(POLL_MS)
        settings.prefs.edit().commit()
        android.util.Log.i("kumapie", "restart after video: pid ${Process.myPid()}")
        Process.killProcess(Process.myPid())
    }.start()
}
