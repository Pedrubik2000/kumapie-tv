package io.github.pedrubik2000.kumapie

import android.os.Process
import android.os.SystemClock
import io.github.pedrubik2000.kumapie.data.Pending
import io.github.pedrubik2000.kumapie.data.Playable
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.lang.DeviceSync
import io.github.pedrubik2000.kumapie.player.AppScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object Restart {
    const val FORCE = "debug_narrow_restart"
    private const val RESTARTED = "restarted_after_video_at"
    private const val FRESH_MS = 60_000L
    private const val DISPOSE_MAX_MS = 2_000L
    private const val GRACE_MS = 150L
    private const val SYNC_MAX_MS = 15_000L
    private const val JOBS_MAX_MS = 30_000L
    private const val POLL_MS = 200L

    @Volatile private var disposed = CountDownLatch(1)
    @Volatile private var disposedAt = 0L

    fun begin() {
        disposedAt = 0L
        disposed = CountDownLatch(1)
    }

    fun playerDisposed() {
        disposedAt = SystemClock.uptimeMillis()
        val latch = disposed
        AppScope.launch(Dispatchers.Main) { latch.countDown() }
    }

    fun wanted(settings: Settings): Boolean = Playable.narrow || BuildConfig.DEBUG && settings.prefs.getBoolean(FORCE, false)

    fun consume(settings: Settings): Boolean {
        val at = settings.prefs.getLong(RESTARTED, 0L)
        if (at != 0L) settings.prefs.edit().remove(RESTARTED).commit()
        return at != 0L && System.currentTimeMillis() - at in 0..FRESH_MS
    }

    fun now(settings: Settings) = Thread {
        val start = SystemClock.uptimeMillis()
        disposed.await(DISPOSE_MAX_MS, TimeUnit.MILLISECONDS)
        Thread.sleep(GRACE_MS)
        val disposedIn = disposedAt.takeIf { it != 0L }?.minus(start) ?: -1L
        fun waiting(): Boolean {
            val at = SystemClock.uptimeMillis() - start
            return DeviceSync.syncing && at < SYNC_MAX_MS || Pending.busy && at < JOBS_MAX_MS
        }
        while (waiting()) Thread.sleep(POLL_MS)
        settings.prefs.edit().putLong(RESTARTED, System.currentTimeMillis()).commit()
        val waited = SystemClock.uptimeMillis() - start
        android.util.Log.i("kumapie", "restart after video: disposed in $disposedIn ms, waited $waited ms (sync=${DeviceSync.syncing}, jobs=${Pending.busy}), pid ${Process.myPid()}")
        Process.killProcess(Process.myPid())
    }.start()
}
