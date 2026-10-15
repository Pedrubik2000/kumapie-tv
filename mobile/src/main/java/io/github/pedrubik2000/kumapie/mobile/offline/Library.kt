package io.github.pedrubik2000.kumapie.mobile.offline

import android.content.Context
import io.github.pedrubik2000.kumapie.i18n.tr
import android.net.Uri
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Backend
import io.github.pedrubik2000.kumapie.data.EpisodeDetail
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.lang.Dictionary
import io.github.pedrubik2000.kumapie.lang.Language
import io.github.pedrubik2000.kumapie.lang.Languages
import io.github.pedrubik2000.kumapie.lang.Miner
import io.github.pedrubik2000.kumapie.lang.YomitanDictionaries
import io.github.pedrubik2000.kumapie.mobile.local.LocalEpisodes
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
    /** Device-made shows' metadata (items 20-21). */
    val showMeta by lazy { io.github.pedrubik2000.kumapie.mobile.local.ShowMeta(appContext).also { m ->
        Thread { runCatching { m.fillYouTube() } }.start() // once per start: YouTube shows without their channel's picture
    } }
    val pending = Pending(context)
    /** Imported Yomitan dictionaries (Settings > Dictionaries), for every language. */
    val yomitan = YomitanDictionaries.get(context).also {
        // Episodes to and from the PC (the hub), when this device has an owner: hourly, and once now.
        if (settings.owner.isNotBlank()) io.github.pedrubik2000.kumapie.mobile.local.SyncWorker.run { schedule(context); now(context) }
        io.github.pedrubik2000.kumapie.lang.YomitanUpdateWorker.schedule(context)
        if (io.github.pedrubik2000.kumapie.mobile.local.Subscriptions(context).all().isNotEmpty()) io.github.pedrubik2000.kumapie.mobile.local.SubscriptionWorker.schedule(context)
    }
    /** Meanings without the PC: Yomitan dictionaries, else the offline Wiktionary file, Wiktionary online (cached), recordings. */
    val dictionary = Dictionary(context, yomitan).also { d ->
        // The word recordings (a few MB a language) come by themselves: nobody has to find the button.
        if (Dictionary.RECORDINGS.any { !d.recordingsFile(it).exists() }) d.download()
    }
    /** What each language does its own way: lookups, word audio, known words. */
    val languages = Languages(context, settings, yomitan, dictionary) { modelDir, fieldsJson ->
        // spaCy in Chaquopy's Python (python/german.py): Python lives in this app module, not in :lang.
        if (!com.chaquo.python.Python.isStarted()) com.chaquo.python.Python.start(com.chaquo.python.android.AndroidPlatform(appContext))
        com.chaquo.python.Python.getInstance().getModule("german").callAttr("parse_fields", modelDir, fieldsJson).toString()
    }
    /** Cards mined into kuma3 Anki. */
    val miner by lazy { Miner(context, languages, dictionary) }
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val showsCache = File(context.filesDir, "shows.json")
    /** Episodes made on this device (YouTube links processed here), listed first. */
    val local = LocalEpisodes(context)
    /** Positions, scenes seen, watch time, lookups and marks: this device's and the others' (in Anki), merged. */
    val progress = Progress.of(context).also { p ->
        // Once: positions of episodes made here, from before progress was kept in Anki.
        if (!settings.prefs.getBoolean("progress_migrated", false)) {
            settings.prefs.all.filterKeys { it.startsWith("resume_") }.forEach { (k, v) -> p.record(k.removePrefix("resume_"), (v as Float).toDouble(), emptyList(), 0.0) }
            languages.all.forEach { l -> l.known.markedWords.forEach { p.mark(l.markKey(it), true) } }
            settings.prefs.edit().putBoolean("progress_migrated", true).apply()
        }
    }

    /** Sends this device's progress to Anki and reads the others' (when Anki was read here once). */
    suspend fun syncProgress() {
        if (languages.all.none { it.known.ready }) return
        progress.sync(runCatching { api }.getOrNull())
        val marked = progress.merged.marked
        languages.all.forEach { l -> l.known.useMarked(marked.mapNotNull(l::wordOfMark).toSet()) }
        sendKnownWords()
        sendWatch()
    }

    /** The devices' own time per language to the PC (the TV's Home card adds the TV's), when it changed. */
    private suspend fun sendWatch() {
        val api = runCatching { api }.getOrNull() ?: return
        val shows = lastShows.takeIf { it.isNotEmpty() } ?: return
        val v = progress.devices()
        val watch = shows.map { it.lang }.toSet().associateWith { Progress.daysIn(it, v, sceneLang(v, shows)) }
        val hash = watch.hashCode().toString()
        if (settings.prefs.getString("watch_sent", null) == hash) return
        runCatching { api.deviceWatch(watch) }.onSuccess { settings.prefs.edit().putString("watch_sent", hash).apply() }
            .onFailure { android.util.Log.w("kumapie", "watch time not sent: $it") }
    }

    /** Scene -> the language of its show. */
    private fun sceneLang(v: Progress.View, shows: List<Show>): Map<String, String> {
        val epLang = shows.flatMap { s -> s.episodes.map { it.id to s.lang } }.toMap()
        return v.seen.mapNotNull { (scene, ep) -> epLang[ep]?.let { scene to it } }.toMap()
    }

    /** The last list [shows] gave (for [sendWatch]). */
    @Volatile private var lastShows: List<Show> = emptyList()

    /**
     * Each language's known words (white or green) to the PC, when they changed since the last send
     * (kumapie_plan.md item 6). Offline or an error: tried again at the next sync.
     */
    private suspend fun sendKnownWords() {
        val api = runCatching { api }.getOrNull() ?: return
        for (l in languages.all.filter { it.known.ready }) {
            val words = withContext(Dispatchers.Default) { l.known.knownList() }
            val hash = words.hashCode().toString()
            val key = "known_sent_${l.lang.code}"
            if (settings.prefs.getString(key, null) == hash) { android.util.Log.i("kumapie", "known words: ${l.lang.code} unchanged"); continue }
            runCatching { api.knownWords(l.lang.code, words) }
                .onSuccess {
                    settings.prefs.edit().putString(key, hash).apply()
                    android.util.Log.i("kumapie", "known words: ${l.lang.code} ${words.size} sent")
                }
                .onFailure { android.util.Log.w("kumapie", "known words: ${l.lang.code} not sent: $it") }
        }
    }

    private var syncSoon: kotlinx.coroutines.Job? = null

    /** A sync a few minutes from now (one at a time), so playing doesn't write the Anki note every 20 seconds. */
    private fun syncLater() {
        if (syncSoon?.isActive == true) return
        syncSoon = background.launch { kotlinx.coroutines.delay(180_000); syncProgress() }
    }

    val api: Api get() = Api(settings.server, settings.owner)

    /** The shows, and whether they came from the saved copy (offline). */
    suspend fun shows(): Pair<List<Show>, Boolean> {
        showMeta // starts the YouTube pictures fill once
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
        // The PC lists episodes made on devices too (for the TV); here they come through Sync, so only once.
        val pcOwn = pc.map { s -> s.copy(episodes = s.episodes.filterNot { LocalEpisodes.isLocal(it.id) }) }.filter { it.episodes.isNotEmpty() }
        // A device-made show without a match of its own: the PC's poster (the PC looks every show up).
        val pcPoster = pc.flatMap { s -> s.episodes.map { it.id to s.poster } }.toMap()
        // Its easy scenes too: only the PC counts them (against the known words this phone sent); the device's list has 0.
        val pcEasy = pc.flatMap { s -> s.episodes.map { it.id to it.easy } }.toMap()
        val mineShown = mine.map { s -> s.copy(episodes = s.episodes.map { e -> e.copy(easy = pcEasy[e.id] ?: e.easy) }) }
            .map { s -> if (s.poster.isNotEmpty()) s else s.copy(poster = s.episodes.firstNotNullOfOrNull { pcPoster[it.id]?.ifEmpty { null } } ?: "") }
        val m = progress.merged
        return (mineShown + pcOwn).also { lastShows = it }.map { s ->
            // A "not watched" mark: the PC's own count is from before it, only the merged scenes count.
            s.copy(episodes = s.episodes.map { e -> e.copy(resume = m.pos[e.id] ?: e.resume, resumedAt = maxOf(e.resumedAt, m.posAt[e.id] ?: 0),
                seen = if (e.id in m.unwatched) m.seenIn(e.id) else maxOf(e.seen, m.seenIn(e.id))) })
        } to offline
    }

    /** An episode to play: fresh from the PC if it answers, else its download. A downloaded video plays from the device. */
    suspend fun episode(id: String): EpisodeDetail {
        val api = api
        if (LocalEpisodes.isLocal(id)) {
            val json = withContext(Dispatchers.IO) { local.json(id).readText() }
            val detail = withSeen(api.parseEpisode(json).let { languages.of(it.lang).known.run { refreshDue(); apply(it) } })
            // Its copy deleted here: streamed from the PC (sync keeps every device episode there).
            val video = local.video(id).takeIf { it.exists() }?.let { Uri.fromFile(it).toString() }
                ?: "${settings.server}/api/sync/${settings.owner}/$id/video.mp4"
            return detail.copy(video = video, resume = progress.merged.pos[id])
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
        } ?: throw IOException(tr("The PC doesn't answer and this episode isn't downloaded."))
        val detail = withSeen(api.parseEpisode(json).let { languages.of(it.lang).known.run { refreshDue(); apply(it) } })
        return detail.copy(
            video = if (downloaded) Uri.fromFile(downloads.video(id)).toString() else detail.video,
            scenes = if (!downloaded || !detail.novel) detail.scenes else detail.scenes.map { s ->
                // Its music and screens from the download where they are there (else the PC's).
                fun here(url: String?) = url?.let { u -> downloads.novelFile(id, u).takeIf { it.exists() }?.let { Uri.fromFile(it).toString() } ?: u }
                s.copy(bgm = here(s.bgm), base = here(s.base), pics = s.pics.map { it.copy(url = here(it.url)!!, mask = here(it.mask)) })
            },
            resume = progress.merged.pos[id] ?: pending.lastPosition(id) ?: detail.resume,
        )
    }

    /** Scenes watched to the end on any device. */
    private fun withSeen(d: EpisodeDetail): EpisodeDetail {
        val m = progress.merged
        if (d.id in m.unwatched) return d.copy(scenes = d.scenes.map { it.copy(seen = it.id in m.seen) })
        return d.copy(scenes = d.scenes.map { if (it.seen || it.id !in m.seen) it else it.copy(seen = true) })
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

    /** Home's progress card: time in [lang] (today, 7 days, all) and the words known in it. */
    class LangProgress(val today: Double, val week: Double, val total: Double, val known: Int)

    suspend fun langProgress(lang: String, shows: List<Show>): LangProgress = withContext(Dispatchers.IO) {
        val m = progress.merged
        val days = Progress.daysIn(lang, m, sceneLang(m, shows))
        val today = java.time.LocalDate.parse(Progress.studyDay())
        LangProgress(days[today.toString()] ?: 0.0, (0L..6L).sumOf { days[today.minusDays(it).toString()] ?: 0.0 }, days.values.sum(),
            languages.of(lang).known.knownList().size)
    }

    /** Every episode of [shows] with its scenes and word colours (fetched once per app run; unreachable ones skipped). */
    suspend fun allEpisodes(shows: List<Show>, progress: (String) -> Unit = {}): List<Pair<Show, EpisodeDetail>> {
        val list = shows.flatMap { s -> s.episodes.map { s to it } }
        return list.mapIndexedNotNull { i, (show, ep) ->
            progress(tr("Reading episodes %1\$d of %2\$d…", i + 1, list.size))
            val detail = episodeCache[ep.id]?.let(::withSeen) ?: runCatching { episode(ep.id) }.getOrNull()?.also { episodeCache[ep.id] = it }
            detail?.let { show to it }
        }
    }

    /** Counts an episode as watched (seen before kumapie, or elsewhere): all its scenes become seen, on every device. */
    suspend fun markWatched(id: String) {
        val detail = episodeCache[id] ?: episode(id)
        progress.record(id, null, detail.scenes.map { it.id }, 0.0)
        syncLater()
    }

    /** Undoes [markWatched] or a real watch: no scene of it counts as seen any more, and it starts from the beginning. */
    suspend fun markUnwatched(id: String) {
        progress.unwatch(id)
        pending.add(Pending.progress(id, 0.0, emptyList(), 0.0)) // the TV starts it from the beginning too
        syncLater()
    }

    private val episodeCache = java.util.concurrent.ConcurrentHashMap<String, EpisodeDetail>()

    /** Pictures of downloaded episodes and their shows, so lists offline have them too. */
    fun thumb(id: String, url: String): Any = downloads.thumb(id).takeIf { it.exists() } ?: url
    fun poster(showId: String, url: String): Any = downloads.poster(showId).takeIf { it.exists() } ?: url

    /** The player's backend; [lang] is the episode's language, so its marks go to that language's list. */
    fun backend(lang: String = "de"): Backend = OfflineBackend(api, downloads, pending, { languages.ofWord(it, lang) }, dictionary, { languages.of(lang).voice.speak(it) }, { surface, url ->
        background.launch { dictionary.keepRecording(surface, url, lang) }
    }, lang, progress, ::syncLater)
}

/** The server API with local audio files when downloaded, and reports queued when the PC can't be reached. */
private class OfflineBackend(
    private val api: Api,
    private val downloads: Downloads,
    private val pending: Pending,
    /** The language of a word marked in this episode. */
    private val languageOf: (String) -> Language,
    private val dictionary: Dictionary,
    private val say: (String) -> Unit,
    private val keepRecording: (String, String) -> Unit,
    /** The episode's language ([io.github.pedrubik2000.kumapie.data.Lang.code]): whose recordings. */
    private val lang: String,
    private val progress: Progress,
    private val syncLater: () -> Unit,
) : Backend {

    /**
     * Kept in [Progress] (Anki). The PC gets only the position (its own episodes and synced ones made on a device), so
     * the TV resumes there; scenes and time would count twice (the PC's history is part of Progress as the "pc" note).
     */
    override suspend fun progress(episode: String, pos: Double, seen: Collection<String>, watched: Double) {
        progress.record(episode, pos, seen, watched, lang)
        syncLater()
        pending.add(Pending.progress(episode, pos, emptyList(), 0.0))
        pending.flush(api)
    }

    override suspend fun lookup(word: String, scene: String): Int = progress.lookup(word).also { syncLater() }

    override suspend fun markKnown(word: String, known: Boolean): String {
        // Kept on the device too, so the colour is right offline and once the PC is no longer asked.
        val language = languageOf(word)
        val list = language.known
        progress.mark(language.markKey(word), known)
        syncLater()
        val here = if (list.ready) list.mark(word, known) else null
        if (list.lang.code != "de") return here ?: if (known) "k" else "u" // the PC only knows German
        if (pending.flush(api)) runCatching { val pc = api.markKnown(word, known); return here ?: pc }
        pending.add(Pending.known(word, known))
        return here ?: if (known) "k" else "u" // the PC's real answer comes with the next fresh episode
    }

    /**
     * A person's recording of the word in the episode's language first (saved, or from Wikimedia Commons, then saved);
     * a computer voice only without one: the PC's that a download brought, else the device's. Offline, a recording not
     * saved yet fails to play and the player says the word with the device voice.
     */
    override fun wordAudio(surface: String, lang: String): String {
        dictionary.recording(surface, lang)?.let { recording ->
            if (recording.startsWith("http")) keepRecording(surface, recording)
            return recording
        }
        downloads.wordFile(surface).takeIf { it.exists() }?.let { return it.path }
        return "tts:$surface"
    }

    override fun speak(text: String) = say(text)

    override fun definitionAudio(scene: String, line: Int, word: String): String =
        downloads.definitionFile(scene, line, word).takeIf { it.exists() }?.path
            ?: api.definitionAudio(scene, line, word)
}

