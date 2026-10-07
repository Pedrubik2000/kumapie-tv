package io.github.pedrubik2000.kumapie.mobile.offline

import android.content.Context
import android.net.Uri
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Backend
import io.github.pedrubik2000.kumapie.data.EpisodeDetail
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.mobile.lang.Dictionary
import io.github.pedrubik2000.kumapie.mobile.lang.Voice
import io.github.pedrubik2000.kumapie.mobile.lang.KnownWords
import io.github.pedrubik2000.kumapie.mobile.lang.Miner
import io.github.pedrubik2000.kumapie.mobile.lang.YomitanDictionaries
import io.github.pedrubik2000.kumapie.mobile.local.LocalEpisodes
import io.github.pedrubik2000.kumapie.mobile.unlock.UnlockPool
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
    private val appContext = context.applicationContext
    val downloads = Downloads(context)
    val pending = Pending(context)
    /** Word colours from the device's own Anki (kuma3-anki), once read; until then the PC's. */
    val known = KnownWords(context, settings)
    /** The same for Japanese (🐻 Japanese, Sudachi); used by Japanese episodes once they exist (plan step 4). */
    val knownJa = KnownWords(context, settings, io.github.pedrubik2000.kumapie.data.Lang.JAPANESE)
    fun knownFor(lang: String) = if (lang == knownJa.lang.code) knownJa else known
    /** The known-words list a word belongs to: kana or kanji in it means Japanese. */
    private fun knownOfWord(word: String) = if (JAPANESE_TEXT.containsMatchIn(word)) knownJa else known
    /** Imported Yomitan dictionaries (Settings > Dictionaries), for every language. */
    val yomitan = YomitanDictionaries.get(context).also {
        io.github.pedrubik2000.kumapie.mobile.lang.YomitanUpdateWorker.schedule(context)
        if (io.github.pedrubik2000.kumapie.mobile.local.Subscriptions(context).all().isNotEmpty()) io.github.pedrubik2000.kumapie.mobile.local.SubscriptionWorker.schedule(context)
    }
    /** Meanings without the PC: Yomitan dictionaries, else the offline Wiktionary file, Wiktionary online (cached), recordings. */
    val dictionary = Dictionary(context, yomitan)
    /** The device's German voice, for words without a recording. */
    val voice by lazy { Voice(context) }
    /** The device's Japanese voice (the Japanese popup's 🔊). */
    val voiceJa by lazy { Voice(context, io.github.pedrubik2000.kumapie.data.Lang.JAPANESE) }
    /** Cards mined into kuma3 Anki. */
    val miner by lazy { Miner(context, known, knownJa, dictionary, { voice }, { voiceJa }) }
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val showsCache = File(context.filesDir, "shows.json")
    /** Episodes made on this device (YouTube links processed here), listed first. */
    val local = LocalEpisodes(context)
    /** Positions, scenes seen, watch time, lookups and marks: this device's and the others' (in Anki), merged. */
    val progress = Progress.of(context).also { p ->
        // Once: positions of episodes made here, from before progress was kept in Anki.
        if (!settings.prefs.getBoolean("progress_migrated", false)) {
            settings.prefs.all.filterKeys { it.startsWith("resume_") }.forEach { (k, v) -> p.record(k.removePrefix("resume_"), (v as Float).toDouble(), emptyList(), 0.0) }
            (known.markedWords + knownJa.markedWords).forEach { p.mark(it, true) }
            settings.prefs.edit().putBoolean("progress_migrated", true).apply()
        }
    }

    /** Sends this device's progress to Anki and reads the others' (when Anki was read here once). */
    suspend fun syncProgress() {
        if (!known.ready) return
        progress.sync(runCatching { api }.getOrNull())
        val (ja, other) = progress.merged.marked.partition { JAPANESE_TEXT.containsMatchIn(it) }
        known.useMarked(other.toSet())
        knownJa.useMarked(ja.toSet())
    }

    private var syncSoon: kotlinx.coroutines.Job? = null

    /** A sync a few minutes from now (one at a time), so playing doesn't write the Anki note every 20 seconds. */
    private fun syncLater() {
        if (syncSoon?.isActive == true) return
        syncSoon = background.launch { kotlinx.coroutines.delay(180_000); syncProgress() }
    }

    val api: Api get() = Api(settings.server)

    /** The shows, and whether they came from the saved copy (offline). */
    suspend fun shows(): Pair<List<Show>, Boolean> {
        val api = api
        val mine = withContext(Dispatchers.IO) { local.shows() }
        val (pc, offline) = runCatching {
            val json = api.showsJson()
            pending.flush(api, force = true)
            withContext(Dispatchers.IO) { showsCache.writeText(json) }
            api.parseShows(json) to false
        }.getOrElse { e ->
            val saved = withContext(Dispatchers.IO) { if (showsCache.exists()) showsCache.readText() else null }
            if (saved == null && mine.isEmpty()) throw e
            (saved?.let { api.parseShows(it) } ?: emptyList()) to true
        }
        val m = progress.merged
        return (mine + pc).map { s ->
            s.copy(episodes = s.episodes.map { e -> e.copy(resume = m.pos[e.id] ?: e.resume, seen = maxOf(e.seen, m.seenIn(e.id))) })
        } to offline
    }

    /** An episode to play: fresh from the PC if it answers, else its download. A downloaded video plays from the device. */
    suspend fun episode(id: String): EpisodeDetail {
        val api = api
        if (LocalEpisodes.isLocal(id)) {
            val json = withContext(Dispatchers.IO) { local.json(id).readText() }
            val detail = withSeen(api.parseEpisode(json).let { knownFor(it.lang).apply(it) })
            return detail.copy(video = Uri.fromFile(local.video(id)).toString(), resume = progress.merged.pos[id])
        }
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
        val detail = withSeen(api.parseEpisode(json).let { knownFor(it.lang).apply(it) })
        return detail.copy(
            video = if (downloaded) Uri.fromFile(downloads.video(id)).toString() else detail.video,
            resume = progress.merged.pos[id] ?: pending.lastPosition(id) ?: detail.resume,
        )
    }

    /** Scenes watched to the end on any device. */
    private fun withSeen(d: EpisodeDetail): EpisodeDetail {
        val seen = progress.merged.seen
        return d.copy(scenes = d.scenes.map { if (it.seen || it.id !in seen) it else it.copy(seen = true) })
    }

    /**
     * Stats from [Progress] (this device, the others through Anki, the PC's history), so they need no PC: watch time
     * per study day, today / 7 days / total, the streak (days with at least a minute), scenes, lookups, marks.
     */
    suspend fun stats(): io.github.pedrubik2000.kumapie.data.Stats {
        syncProgress()
        val shows = runCatching { shows().first }.getOrDefault(emptyList())
        val m = progress.merged
        val today = java.time.LocalDate.parse(Progress.studyDay())
        fun day(d: java.time.LocalDate) = m.watch[d.toString()] ?: 0.0
        var streak = 0
        var d = if (day(today) < 60) today.minusDays(1) else today // today not started: the streak counts to yesterday
        while (day(d) >= 60) { streak++; d = d.minusDays(1) }
        val top = m.lookups.entries.sortedWith(compareBy({ -it.value }, { it.key })).take(10)
            .map { io.github.pedrubik2000.kumapie.data.TopLookup(it.key, it.value, m.gloss[it.key] ?: "") }
        return io.github.pedrubik2000.kumapie.data.Stats(
            days = m.watch, today = day(today), week = (0L..6L).sumOf { day(today.minusDays(it)) }, total = m.watch.values.sum(),
            streak = streak, scenesSeen = m.seen.size, scenesTotal = shows.sumOf { s -> s.episodes.sumOf { it.scenes } },
            lookups = m.lookups.values.sum(), wordsLookedUp = m.lookups.size, topLookups = top, markedKnown = m.marked.size,
            shows = shows.map { s -> io.github.pedrubik2000.kumapie.data.ShowProgress(s.title, s.episodes.sumOf { m.seenIn(it.id) }, s.episodes.sumOf { it.scenes }) }
                .sortedByDescending { it.seen },
        )
    }

    /** Every episode of [shows] with its scenes and word colours (fetched once per app run; unreachable ones skipped). */
    suspend fun allEpisodes(shows: List<Show>, progress: (String) -> Unit = {}): List<Pair<Show, EpisodeDetail>> {
        val list = shows.flatMap { s -> s.episodes.map { s to it } }
        return list.mapIndexedNotNull { i, (show, ep) ->
            progress("Reading episodes ${i + 1} of ${list.size}…")
            val detail = episodeCache[ep.id] ?: runCatching { episode(ep.id) }.getOrNull()?.also { episodeCache[ep.id] = it }
            detail?.let { show to it }
        }.also { found -> if (found.isNotEmpty()) UnlockPool.save(appContext, found.map { it.second }) }
    }

    private val episodeCache = java.util.concurrent.ConcurrentHashMap<String, EpisodeDetail>()

    /** Pictures of downloaded episodes and their shows, so lists offline have them too. */
    fun thumb(id: String, url: String): Any = downloads.thumb(id).takeIf { it.exists() } ?: url
    fun poster(showId: String, url: String): Any = downloads.poster(showId).takeIf { it.exists() } ?: url

    fun backend(): Backend = OfflineBackend(api, downloads, pending, ::knownOfWord, dictionary, { voice.speak(it) }, { surface, url ->
        background.launch { dictionary.keepRecording(surface, url) }
    }, progress, ::syncLater)
}

/** The server API with local audio files when downloaded, and reports queued when the PC can't be reached. */
private class OfflineBackend(
    private val api: Api,
    private val downloads: Downloads,
    private val pending: Pending,
    /** The known-words list of a word (German or Japanese). */
    private val knownOf: (String) -> KnownWords,
    private val dictionary: Dictionary,
    private val say: (String) -> Unit,
    private val keepRecording: (String, String) -> Unit,
    private val progress: Progress,
    private val syncLater: () -> Unit,
) : Backend {

    /**
     * Kept in [Progress] (Anki). The PC gets only the position of its own episodes, so the TV resumes there; its
     * scenes and time would count twice (the PC's history is part of Progress as the "pc" note).
     */
    override suspend fun progress(episode: String, pos: Double, seen: Collection<String>, watched: Double) {
        progress.record(episode, pos, seen, watched)
        syncLater()
        if (LocalEpisodes.isLocal(episode)) return // made on this device: the PC doesn't know it
        pending.add(Pending.progress(episode, pos, emptyList(), 0.0))
        pending.flush(api)
    }

    override suspend fun lookup(word: String, scene: String): Int = progress.lookup(word).also { syncLater() }

    override suspend fun markKnown(word: String, known: Boolean): String {
        // Kept on the device too, so the colour is right offline and once the PC is no longer asked.
        progress.mark(word, known)
        syncLater()
        val list = knownOf(word)
        val here = if (list.ready) list.mark(word, known) else null
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

/** Hiragana, katakana or kanji: a Japanese word. */
private val JAPANESE_TEXT = Regex("[぀-ヿ㐀-鿿]")
