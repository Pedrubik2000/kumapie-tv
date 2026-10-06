package io.github.pedrubik2000.kumapie.mobile.offline

import android.content.Context
import android.net.Uri
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Backend
import io.github.pedrubik2000.kumapie.data.EpisodeDetail
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.mobile.german.Dictionary
import io.github.pedrubik2000.kumapie.mobile.german.GermanVoice
import io.github.pedrubik2000.kumapie.mobile.german.KnownWords
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Where the phone app gets everything: the PC when it answers, else what is saved on the device (the last
 * show list, downloaded episodes). Reports go through [Pending], so they reach the PC later when offline.
 */
class Library(context: Context, val settings: Settings) {
    val downloads = Downloads(context)
    val pending = Pending(context)
    /** Word colours from the device's own Anki (kuma3-anki), once read; until then the PC's. */
    val known = KnownWords(context, settings)
    /** Meanings without the PC: the offline Wiktionary file, Wiktionary online (cached), recordings. */
    val dictionary = Dictionary(context)
    /** The device's German voice, for words without a recording. */
    val voice by lazy { GermanVoice(context) }
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val showsCache = File(context.filesDir, "shows.json")

    val api: Api get() = Api(settings.server)

    /** The shows, and whether they came from the saved copy (offline). */
    suspend fun shows(): Pair<List<Show>, Boolean> {
        val api = api
        return runCatching {
            val json = api.showsJson()
            pending.flush(api, force = true)
            withContext(Dispatchers.IO) { showsCache.writeText(json) }
            api.parseShows(json) to false
        }.getOrElse { e ->
            val saved = withContext(Dispatchers.IO) { if (showsCache.exists()) showsCache.readText() else null } ?: throw e
            api.parseShows(saved) to true
        }
    }

    /** An episode to play: fresh from the PC if it answers, else its download. A downloaded video plays from the device. */
    suspend fun episode(id: String): EpisodeDetail {
        val api = api
        val downloaded = downloads.isComplete(id)
        // When the PC answers, send what waited, then fetch again so the episode has that progress in it.
        val fresh = runCatching {
            val first = api.episodeJson(id)
            if (pending.count() > 0 && pending.flush(api, force = true)) api.episodeJson(id) else first
        }.getOrNull()
        val json = withContext(Dispatchers.IO) {
            if (fresh != null && downloaded) downloads.episodeJson(id).writeText(fresh) // fresher word colours offline
            fresh ?: if (downloaded) downloads.episodeJson(id).readText() else null
        } ?: throw IOException("The PC doesn't answer and this episode isn't downloaded.")
        val detail = known.apply(api.parseEpisode(json))
        return detail.copy(
            video = if (downloaded) Uri.fromFile(downloads.video(id)).toString() else detail.video,
            resume = pending.lastPosition(id) ?: detail.resume,
        )
    }

    /** Pictures of downloaded episodes and their shows, so lists offline have them too. */
    fun thumb(id: String, url: String): Any = downloads.thumb(id).takeIf { it.exists() } ?: url
    fun poster(showId: String, url: String): Any = downloads.poster(showId).takeIf { it.exists() } ?: url

    fun backend(): Backend = OfflineBackend(api, downloads, pending, known, dictionary, { voice.speak(it) }) { surface, url ->
        background.launch { dictionary.keepRecording(surface, url) }
    }
}

/** The server API with local audio files when downloaded, and reports queued when the PC can't be reached. */
private class OfflineBackend(
    private val api: Api,
    private val downloads: Downloads,
    private val pending: Pending,
    private val known: KnownWords,
    private val dictionary: Dictionary,
    private val say: (String) -> Unit,
    private val keepRecording: (String, String) -> Unit,
) : Backend {

    override suspend fun progress(episode: String, pos: Double, seen: Collection<String>, watched: Double) {
        pending.add(Pending.progress(episode, pos, seen, watched))
        pending.flush(api)
    }

    override suspend fun lookup(word: String, scene: String): Int {
        if (pending.flush(api)) runCatching { return api.lookup(word, scene) }
        pending.add(Pending.lookup(word, scene))
        throw IOException("offline: lookup queued")
    }

    override suspend fun markKnown(word: String, known: Boolean): String {
        // Kept on the device too, so the colour is right offline and once the PC is no longer asked.
        val here = if (this.known.ready) this.known.mark(word, known) else null
        if (pending.flush(api)) runCatching { val pc = api.markKnown(word, known); return here ?: pc }
        pending.add(Pending.known(word, known))
        return here ?: if (known) "k" else "u" // the PC's real answer comes with the next fresh episode
    }

    /**
     * A human recording of the word (saved, or from Wikimedia Commons, then saved), else the audio a download brought
     * from the PC, else the device's German voice. No longer asks the PC for words.
     */
    override fun wordAudio(surface: String): String {
        val recording = dictionary.recording(surface)
        if (recording != null && !recording.startsWith("http")) return recording
        downloads.wordFile(surface).takeIf { it.exists() }?.let { return it.path }
        if (recording != null) {
            keepRecording(surface, recording)
            return recording
        }
        return "tts:$surface"
    }

    override fun speak(text: String) = say(text)

    override fun definitionAudio(scene: String, line: Int, word: String): String =
        downloads.definitionFile(scene, line, word).takeIf { it.exists() }?.path
            ?: api.definitionAudio(scene, line, word)
}
