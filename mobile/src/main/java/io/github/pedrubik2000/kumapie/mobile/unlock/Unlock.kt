package io.github.pedrubik2000.kumapie.mobile.unlock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.pedrubik2000.kumapie.data.Backend
import io.github.pedrubik2000.kumapie.data.EpisodeDetail
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.Subtitles
import io.github.pedrubik2000.kumapie.mobile.MainActivity
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.mobile.ui.MobileTheme
import io.github.pedrubik2000.kumapie.mobile.ui.ScenePlayer
import io.github.pedrubik2000.kumapie.ui.Colors
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The i+1 scenes the unlock screen picks from: (episode, scene index), saved on the device whenever kumapie has
 * read the episodes (start, feed, i+1 list), so an unlock needs no work but loading one episode.
 */
object UnlockPool {
    private fun file(context: Context) = File(context.filesDir, "unlock-pool.json")

    fun save(context: Context, episodes: List<EpisodeDetail>) {
        val a = JSONArray()
        for (ep in episodes) for (sc in ep.scenes) {
            if (sc.target && sc.level == 1) a.put(JSONObject().put("e", ep.id).put("s", sc.index))
        }
        runCatching { file(context).writeText(a.toString()) }
    }

    fun pick(context: Context): List<Pair<String, Int>> = runCatching {
        val a = JSONArray(file(context).readText())
        (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("e") to o.getInt("s") } }.shuffled()
    }.getOrDefault(emptyList())

    fun size(context: Context) = pick(context).size
}

/** Whether the unlock screen is on (Settings), kept with the app's other settings. */
var Settings.unlockScenes: Boolean
    get() = prefs.getBoolean("unlock_scenes", false)
    set(value) = prefs.edit().putBoolean("unlock_scenes", value).apply()

/**
 * Stays alive as a foreground service because "the phone was unlocked" (ACTION_USER_PRESENT) only reaches a
 * receiver registered at runtime; on each unlock it opens [UnlockActivity]. Starting an activity from the
 * background is allowed because the app holds "Display over other apps".
 */
class UnlockService : Service() {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_USER_PRESENT) return
            if (android.provider.Settings.canDrawOverlays(context)) UnlockActivity.show(context)
        }
    }

    override fun onCreate() {
        super.onCreate()
        goForeground()
        registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_PRESENT))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Unlock scenes", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Keeps kumapie listening for unlocks to show an i+1 scene"
            setShowBadge(false)
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("kumapie: a scene on every unlock")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION, notification)
    }

    companion object {
        private const val CHANNEL = "unlock"
        private const val NOTIFICATION = 2

        /** Starts or stops the listener to match the setting. */
        fun sync(context: Context) {
            val intent = Intent(context, UnlockService::class.java)
            if (Settings(context).unlockScenes) context.startForegroundService(intent) else context.stopService(intent)
        }
    }
}

/** Brings the listener back after a reboot or an app update. */
class UnlockBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Settings(context).unlockScenes) UnlockService.sync(context)
    }
}

/**
 * The unlock screen: a random i+1 scene, shown like the player (German + English by default, for this screen only),
 * waiting on its first frame; tap to play, tap words for their card, dictionary and Add to Anki; ✕ or back closes.
 * It reports no progress (an episode resumes where it was left). Episodes that can't be loaded (PC away, not
 * downloaded) are skipped.
 */
class UnlockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val library = Library(applicationContext, Settings(this))
        setContent {
            MobileTheme {
                var shown by remember { mutableStateOf<EpisodeDetail?>(null) }
                var nothing by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    for ((episode, index) in UnlockPool.pick(this@UnlockActivity).take(6)) {
                        val detail = runCatching { library.episode(episode) }.getOrNull() ?: continue
                        val scene = detail.scenes.firstOrNull { it.index == index } ?: continue
                        if (scene.level != 1) continue // known words changed since the pool was made
                        shown = detail.copy(scenes = listOf(scene), resume = scene.start)
                        return@LaunchedEffect
                    }
                    nothing = true
                    finish()
                }
                val backend = remember {
                    val real = library.backend()
                    object : Backend by real {
                        override suspend fun progress(episode: String, pos: Double, seen: Collection<String>, watched: Double) {}
                    }
                }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    shown?.let { ep ->
                        ScenePlayer(library, library.settings, backend, ep, startPaused = true, onBack = { finish() },
                            subtitles = Subtitles.BOTH)
                    }
                    if (nothing) Text("No i+1 scene to show", color = Colors.dim, modifier = Modifier.align(Alignment.Center))
                    IconButton(onClick = { finish() }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 56.dp, end = 8.dp)
                        .background(Color(0x66000000), CircleShape)) {
                        Icon(Icons.Default.Close, "Close", tint = Colors.text)
                    }
                }
            }
        }
    }

    companion object {
        fun show(context: Context) {
            context.startActivity(Intent(context, UnlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
    }
}
