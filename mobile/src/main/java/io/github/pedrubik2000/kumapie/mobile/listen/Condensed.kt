package io.github.pedrubik2000.kumapie.mobile.listen

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.pedrubik2000.kumapie.data.EpisodeDetail
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.pedrubik2000.kumapie.i18n.tr

/** Padding around each line, as the PC's condensed audio (subs2cia -p 500). */
private const val PAD = 0.5

/** The episode's speech as (start, end) seconds: every line padded by [PAD], overlapping ones joined. */
fun speech(episode: EpisodeDetail): List<Pair<Double, Double>> {
    val lines = episode.scenes.flatMap { it.cues }.map { (it.start - PAD).coerceAtLeast(0.0) to it.end + PAD }.sortedBy { it.first }
    val out = ArrayList<Pair<Double, Double>>()
    for ((s, e) in lines) {
        val last = out.lastOrNull()
        if (last != null && s <= last.second) out[out.size - 1] = last.first to maxOf(last.second, e) else out += s to e
    }
    return out
}

/**
 * "Condensed audio" without files: the episode's sound with the silence between lines left out, in the background
 * (screen off, lock-screen and headphone controls), like the PC's condensed MP3s in Audiobookshelf. One clipped item
 * per stretch of speech in one playlist, so ExoPlayer joins them without gaps; video off. Where it stopped is kept per
 * episode.
 */
class CondensedService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var episodeId: String? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).setHandleAudioBecomingNoisy(true).build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
        player.setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
        session = MediaSession.Builder(this, player).build().also { addSession(it) } // its notification, without a controller
        scope.launch { while (true) { delay(5000); save() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EPISODE)
        if (id != null && id != episodeId) {
            save()
            episodeId = id
            val art = intent.getStringExtra(ART)
            scope.launch {
                val settings = Settings(this@CondensedService)
                val episode = runCatching { Library(this@CondensedService, settings).episode(id) }.getOrElse { e ->
                    android.util.Log.w("kumapie", "condensed $id: $e")
                    android.widget.Toast.makeText(this@CondensedService, tr("Couldn't open the episode: %s", e.message), android.widget.Toast.LENGTH_LONG).show()
                    episodeId = null
                    return@launch
                }
                val player = session?.player ?: return@launch
                val meta = MediaMetadata.Builder().setTitle(episode.title).setArtist(tr("%s · condensed", episode.show))
                    .setArtworkUri(art?.let(Uri::parse)).build()
                val items = speech(episode).map { (s, e) ->
                    MediaItem.Builder().setUri(episode.video).setMediaMetadata(meta).setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder().setStartPositionMs((s * 1000).toLong()).setEndPositionMs((e * 1000).toLong()).build(),
                    ).build()
                }
                if (items.isEmpty()) return@launch stopSelf()
                val (index, pos) = settings.prefs.getString(KEY + id, null)?.split(":")?.let { it[0].toInt() to it[1].toLong() } ?: (0 to 0L)
                player.setMediaItems(items, index.coerceIn(0, items.size - 1), pos)
                player.prepare()
                player.play()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /** Where it is in the episode ("<stretch>:<ms>"), or the start again once it has played to the end. */
    private fun save() {
        val id = episodeId ?: return
        val p = session?.player ?: return
        if (p.mediaItemCount == 0) return
        val end = p.playbackState == Player.STATE_ENDED
        Settings(this).prefs.edit().putString(KEY + id, if (end) "0:0" else "${p.currentMediaItemIndex}:${p.currentPosition}").apply()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        save()
        scope.cancel()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    companion object {
        private const val EPISODE = "episode"
        private const val ART = "art"
        private const val KEY = "condensed_"

        fun play(context: Context, episode: String, art: String?) {
            context.startService(Intent(context, CondensedService::class.java).putExtra(EPISODE, episode).putExtra(ART, art))
        }
    }
}
