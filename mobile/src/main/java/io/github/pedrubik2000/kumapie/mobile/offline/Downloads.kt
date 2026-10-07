package io.github.pedrubik2000.kumapie.mobile.offline

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Show
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.security.MessageDigest

/** A download's state for the episode list. */
sealed interface DownloadState {
    data object None : DownloadState
    data object Waiting : DownloadState
    /** [stage] "video" or "audio"; [progress] 0..1 for the whole download. */
    data class Running(val stage: String, val progress: Float) : DownloadState
    data object Done : DownloadState
    data class Failed(val message: String) : DownloadState
}

/**
 * Episodes saved on the device for watching with no connection, in the app's own external folder (no storage
 * permission needed, removed with the app):
 *
 *   episodes/<id>/episode.json   the server's /api/tv/episode answer, refreshed whenever it is played online
 *   episodes/<id>/video.mp4      the original file from the PC
 *   episodes/<id>/thumb.jpg      the episode's picture
 *   episodes/<id>/done           written last: the download is complete
 *   posters/<show id>.jpg        the show's poster
 *   audio/w/<hash>.mp3           word audio (shared by all episodes)
 *   audio/d/<hash>.mp3           definition audio
 */
class Downloads(private val context: Context) {
    private val base = context.getExternalFilesDir(null) ?: context.filesDir
    private val episodes = File(base, "episodes")
    private val audio = File(base, "audio")
    private val work get() = WorkManager.getInstance(context)

    fun dir(id: String) = File(episodes, id)
    fun video(id: String) = File(dir(id), "video.mp4")
    fun episodeJson(id: String) = File(dir(id), "episode.json")
    fun thumb(id: String) = File(dir(id), "thumb.jpg")
    /** A novel's music track ([url] = the scene's bgm), kept as bgm/<name>.ogg beside the video. */
    fun bgm(id: String, url: String) = File(dir(id), "bgm/" + url.substringAfterLast('/'))
    fun poster(showId: String) = File(base, "posters/$showId.jpg")
    fun isComplete(id: String) = File(dir(id), "done").exists()
    fun markComplete(id: String) = File(dir(id), "done").writeText("ok")

    fun wordFile(surface: String) = File(audio, "w/" + hash(surface.lowercase()) + ".mp3")
    fun definitionFile(scene: String, line: Int, word: String) = File(audio, "d/" + hash("$scene|$line|$word") + ".mp3")

    /** Ids of the complete downloads. */
    fun completed(): Set<String> =
        episodes.listFiles()?.filter { File(it, "done").exists() }?.map { it.name }?.toSet() ?: emptySet()

    fun start(show: Show, episode: Episode) {
        val id = episode.id
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.ID to id, DownloadWorker.TITLE to "${show.title} · ${episode.title}",
                DownloadWorker.THUMB to episode.thumb, DownloadWorker.SHOW to show.id, DownloadWorker.POSTER to show.poster))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(TAG).addTag(TAG + id)
            .build()
        work.enqueueUniqueWork(TAG + id, ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(id: String) {
        work.cancelUniqueWork(TAG + id)
    }

    /** Removes an episode (its word audio stays: it is shared and small). */
    fun delete(id: String) {
        cancel(id)
        dir(id).deleteRecursively()
    }

    fun deleteAll() {
        work.cancelAllWorkByTag(TAG)
        episodes.deleteRecursively()
        audio.deleteRecursively()
        File(base, "posters").deleteRecursively()
    }

    fun bytesUsed(): Long =
        listOf(episodes, audio).sumOf { d -> d.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    /** Every episode's state: running/waiting/failed downloads from WorkManager, the rest from the files. */
    fun states(): Flow<Map<String, DownloadState>> = work.getWorkInfosByTagFlow(TAG).map { infos ->
        val states = mutableMapOf<String, DownloadState>()
        completed().forEach { states[it] = DownloadState.Done }
        infos.forEach { info ->
            val id = info.tags.firstOrNull { it.startsWith(TAG) && it != TAG }?.removePrefix(TAG) ?: return@forEach
            if (states[id] == DownloadState.Done) return@forEach
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> states[id] = DownloadState.Waiting
                WorkInfo.State.RUNNING -> states[id] = DownloadState.Running(
                    info.progress.getString(DownloadWorker.STAGE) ?: "video",
                    info.progress.getFloat(DownloadWorker.PROGRESS, 0f))
                WorkInfo.State.FAILED ->
                    states[id] = DownloadState.Failed(info.outputData.getString(DownloadWorker.ERROR) ?: "failed")
                else -> Unit
            }
        }
        states
    }

    companion object {
        const val TAG = "download:"

        private fun hash(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).take(10).joinToString("") { "%02x".format(it) }
    }
}
