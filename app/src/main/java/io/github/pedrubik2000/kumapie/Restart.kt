package io.github.pedrubik2000.kumapie

import android.content.Intent
import android.os.Process
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.ui.TopNote
import android.os.SystemClock
import io.github.pedrubik2000.kumapie.data.Pending
import io.github.pedrubik2000.kumapie.data.Playable
import io.github.pedrubik2000.kumapie.data.Playing
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.lang.DeviceSync
import io.github.pedrubik2000.kumapie.player.AppScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object Restart {
    const val FORCE = "debug_narrow_restart"
    const val INSIDE = "kumapie_inside"
    private const val RESTARTED = "restarted_after_video_at"
    private const val RELAUNCH = "relaunch_after_restart"
    private const val FRESH_MS = 60_000L
    private const val DISPOSE_MAX_MS = 2_000L
    private const val GRACE_MS = 150L
    private const val SYNC_MAX_MS = 15_000L
    private const val JOBS_MAX_MS = 30_000L
    private const val POLL_MS = 200L

    @Volatile private var disposed = CountDownLatch(1)
    @Volatile private var disposedAt = 0L
    @Volatile private var syncAtBegin = false

    fun begin() {
        if (SystemClock.uptimeMillis() - disposedAt > DISPOSE_MAX_MS) {
            disposedAt = 0L
            disposed = CountDownLatch(1)
        }
        syncAtBegin = DeviceSync.syncing
        Playing.drop()
    }

    fun playerDisposed() {
        disposedAt = SystemClock.uptimeMillis()
        val latch = disposed
        AppScope.launch(Dispatchers.Main) { latch.countDown() }
    }

    fun wanted(settings: Settings): Boolean = Playable.narrow || BuildConfig.DEBUG && settings.prefs.getBoolean(FORCE, false)

    @Volatile var owed = false
    @Volatile var homeSaved = true
    private const val SAVE_MAX_MS = 5_000L

    fun pay(activity: androidx.activity.ComponentActivity, settings: Settings) {
        settings.prefs.edit().putString(RELAUNCH, activity.intent.toUri(Intent.URI_INTENT_SCHEME)).commit()
        activity.setContent { Box(Modifier.fillMaxSize().background(Color.Black)) { TopNote(tr("Loading…")) } }
        quietly(settings)
    }

    fun quietly(settings: Settings) {
        owed = false
        begin()
        playerDisposed()
        now(settings)
    }

    fun consume(settings: Settings): Intent? {
        val at = settings.prefs.getLong(RESTARTED, 0L)
        val again = settings.prefs.getString(RELAUNCH, null)
        if (at != 0L || again != null) settings.prefs.edit().remove(RESTARTED).remove(RELAUNCH).commit()
        if (at == 0L || System.currentTimeMillis() - at !in 0..FRESH_MS) return null
        return again?.let { runCatching { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) }.getOrNull() }
    }

    fun now(settings: Settings) = Thread {
        val start = SystemClock.uptimeMillis()
        disposed.await(DISPOSE_MAX_MS, TimeUnit.MILLISECONDS)
        Thread.sleep(GRACE_MS)
        val disposedIn = disposedAt.takeIf { it != 0L }?.minus(start) ?: -1L
        fun waiting(): Boolean {
            val at = SystemClock.uptimeMillis() - start
            return syncAtBegin && DeviceSync.syncing && at < SYNC_MAX_MS || Pending.busy && at < JOBS_MAX_MS || !homeSaved && at < SAVE_MAX_MS
        }
        while (waiting()) Thread.sleep(POLL_MS)
        settings.prefs.edit().putLong(RESTARTED, System.currentTimeMillis()).commit()
        val waited = SystemClock.uptimeMillis() - start
        android.util.Log.i("kumapie", "restart after video: disposed in $disposedIn ms, waited $waited ms (sync=${DeviceSync.syncing}, jobs=${Pending.busy}, home saved=$homeSaved), pid ${Process.myPid()}")
        Process.killProcess(Process.myPid())
    }.start()
}
