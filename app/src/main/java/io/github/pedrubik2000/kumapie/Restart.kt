package io.github.pedrubik2000.kumapie

import android.os.Process
import android.os.SystemClock
import io.github.pedrubik2000.kumapie.data.Pending
import io.github.pedrubik2000.kumapie.data.Playable
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.lang.DeviceSync

object Restart {
    const val FORCE = "debug_narrow_restart"
    private const val RESTARTED = "restarted_after_video"
    private const val SETTLE_MS = 2_000L
    private const val SYNC_MAX_MS = 15_000L
    private const val JOBS_MAX_MS = 30_000L
    private const val POLL_MS = 200L

    fun wanted(settings: Settings): Boolean = Playable.narrow || BuildConfig.DEBUG && settings.prefs.getBoolean(FORCE, false)

    fun consume(settings: Settings): Boolean = settings.prefs.getBoolean(RESTARTED, false).also { if (it) settings.prefs.edit().remove(RESTARTED).commit() }

    fun now(settings: Settings) = Thread {
        val start = SystemClock.uptimeMillis()
        Thread.sleep(SETTLE_MS)
        fun waiting(): Boolean {
            val at = SystemClock.uptimeMillis() - start
            return DeviceSync.syncing && at < SYNC_MAX_MS || Pending.busy && at < JOBS_MAX_MS
        }
        while (waiting()) Thread.sleep(POLL_MS)
        settings.prefs.edit().putBoolean(RESTARTED, true).commit()
        android.util.Log.i("kumapie", "restart after video: pid ${Process.myPid()}")
        Process.killProcess(Process.myPid())
    }.start()
}
