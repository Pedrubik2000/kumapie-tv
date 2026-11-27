package io.github.pedrubik2000.kumapie.mobile.offline

import android.content.Context
import io.github.pedrubik2000.kumapie.lang.Progress
import io.github.pedrubik2000.kumapie.i18n.tr
import android.net.Uri
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Backend
import io.github.pedrubik2000.kumapie.data.EpisodeDetail
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.lang.Dictionary
import io.github.pedrubik2000.kumapie.lang.Languages
import io.github.pedrubik2000.kumapie.lang.Miner
import io.github.pedrubik2000.kumapie.lang.NoPcBackend
import io.github.pedrubik2000.kumapie.lang.YomitanDictionaries
import io.github.pedrubik2000.kumapie.mobile.local.LocalEpisodes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
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
    /** The language layer, shared with the TV's code and every Library of this app run (the condensed-audio service's too). */
    private val kit = run {
        io.github.pedrubik2000.kumapie.lang.LangKit.get(context, settings)
    }
    /** Imported Yomitan dictionaries (Settings > Dictionaries), for every language. */
    val yomitan = kit.yomitan.also {
        // Episodes to and from the PC (the hub), when this device has an owner: hourly, and once now.
        if (settings.owner.isNotBlank()) io.github.pedrubik2000.kumapie.mobile.local.SyncWorker.run { schedule(context); now(context) }
        io.github.pedrubik2000.kumapie.lang.YomitanUpdateWorker.schedule(context)
        if (io.github.pedrubik2000.kumapie.mobile.local.Subscriptions(context).all().isNotEmpty()) io.github.pedrubik2000.kumapie.mobile.local.SubscriptionWorker.schedule(context)
    }
    /** Meanings without the PC: Yomitan dictionaries, else the offline Wiktionary file, Wiktionary online (cached), recordings. */
    val dictionary = kit.dictionary.also { d ->
        // The word recordings (a few MB a language) come by themselves: nobody has to find the button.
        if (Dictionary.RECORDINGS.any { !d.recordingsFile(it).exists() }) d.download()
    }
    /** What each language does its own way: lookups, word audio, known words. */
    val languages: Languages = kit.languages
    /** Cards mined (kumapie's own store); the PC cuts what the device can't. */
    val miner: Miner get() = kit.miner
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val showsCache = File(context.filesDir, "shows.json")
    /** Episodes made on this device (YouTube links processed here), listed first. */
    val local = LocalEpisodes(context)
    /** Positions, scenes seen, watch time, lookups and marks: this device's and the others' (in Anki), merged. */
    val progress = Progress.of(context).also { p ->
        // Once: positions of episodes made here, from before progress was kept in Anki.
        if (!settings.prefs.getBoolean("progress_migrated", false)) {
            settings.prefs.all.filterKeys { it.startsWith("resume_") }.forEach { (k, v) -> p.record(k.removePrefix("resume_"), (v as Float).toDouble(), 0.0) }
            languages.all.forEach { l -> l.known.markedWords.forEach { p.mark(l.markKey(it), true) } }
            settings.prefs.edit().putBoolean("progress_migrated", true).apply()
        }
    }

    /** kumapie's own cards per language (kumapie_plan.md "own Anki"; the app doesn't read them yet, step 4d). */
    val srs: Map<String, io.github.pedrubik2000.kumapie.lang.Srs> by lazy {
        languages.all.associate { it.lang.code to io.github.pedrubik2000.kumapie.lang.Srs(appContext, settings.owner, it.lang) }
    }

    /** Each language's new ratings to the PC and the other devices' back; offline they wait in the store's outbox. */
    private suspend fun sendSrs() {
        if (settings.owner.isEmpty()) return
        val api = runCatching { api }.getOrNull() ?: return
        for ((lang, s) in srs) runCatching { s.sync { api.srsSync(lang, it) } }
            .onSuccess { n ->
                val cards = withContext(Dispatchers.IO) { s.cards() }
                android.util.Log.i("kumapie", "srs $lang: $n rows in, ${cards.size} cards, ${cards.count { io.github.pedrubik2000.kumapie.lang.Srs.state(it.due) == "d" }} due, seq ${s.meta("sync_seq")}")
            }
            .onFailure { android.util.Log.w("kumapie", "srs $lang: not synced: $it") }
    }

    /** Sends this device's progress to Anki and reads the others' (when Anki was read here once). */
    suspend fun syncProgress() {
        sendPersonal()
        sendSrs()
        sendKnownLists()
        if (languages.all.none { it.known.ready }) return
        progress.sync(runCatching { api }.getOrNull())
        val marked = progress.merged.marked
        languages.all.forEach { l -> l.known.useMarked(marked.mapNotNull(l::wordOfMark).toSet()) }
        sendKnownWords()
        sendWatch()
    }

    /** Leaving the PC (lang/LeavePc.kt, shared with the TV), then no server. */
    suspend fun leavePc() {
        val api = api
        io.github.pedrubik2000.kumapie.lang.LeavePc.run(appContext, api, settings.server, settings.owner, languages.all,
            io.github.pedrubik2000.kumapie.data.Addons(settings.addons, settings.prefs))
        settings.server = ""
    }

    /** Your personal dictionary to the PC, and what your other devices added; never held up by the Anki sync above. */
    private suspend fun sendPersonal() {
        val api = runCatching { api }.getOrNull() ?: return
        yomitan.personal.sync(api::personalDictionary)
    }

    /**
     * Each language's imported known words to the PC, and what another device changed
     * later: the Jiten import done on one device colours the others too. Offline: the local lists, sent next sync.
     */
    private suspend fun sendKnownLists() {
        val api = runCatching { api }.getOrNull() ?: return
        for (l in languages.all) runCatching { l.known.useLists(api.knownLists(l.lang.code, l.known.listsBody())) }
            .onFailure { android.util.Log.w("kumapie", "known lists: ${l.lang.code} not synced: $it") }
    }

    /** After an import: in the background. */
    fun syncKnownLists() { background.launch { sendKnownLists() } }

    /** After an entry changed: in the background (offline, it goes with the next sync). */
    fun syncPersonal() { background.launch { sendPersonal() } }

    /** The devices' own time per language to the PC (the TV's Home card adds the TV's), when it changed. */
    private suspend fun sendWatch() {
        val api = runCatching { api }.getOrNull() ?: return
        val shows = lastShows.takeIf { it.isNotEmpty() } ?: return
        val v = progress.devices()
        val watch = shows.map { it.lang }.toSet().associateWith { Progress.daysIn(it, v, Progress.sceneLang(v, shows)) }
        val hash = watch.hashCode().toString()
        if (settings.prefs.getString("watch_sent", null) == hash) return
        runCatching { api.deviceWatch(watch) }.onSuccess { settings.prefs.edit().putString("watch_sent", hash).apply() }
            .onFailure { android.util.Log.w("kumapie", "watch time not sent: $it") }
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
            val key = "known_sent2_${l.lang.code}" // 2: once more, now with the device (one file per device on the PC)
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

    private var addonsFor: Pair<List<String>, io.github.pedrubik2000.kumapie.data.Addons>? = null
    /** What Discover asks: the PC, else (no server: kumapie for other people) the user's own Stremio addons. */
    val discovery: io.github.pedrubik2000.kumapie.data.Discovery get() = if (settings.server.isNotEmpty()) api else settings.addons.let { urls ->
        addonsFor?.takeIf { it.first == urls }?.second ?: io.github.pedrubik2000.kumapie.data.Addons(urls, settings.prefs).also { addonsFor = urls to it }
    }

    private fun playableHere(shows: List<Show>) = if (settings.server.isNotEmpty()) shows else shows.map { s -> s.copy(episodes = s.episodes.filter { e ->
        s.kind == "visual_novels" || if (LocalEpisodes.isLocal(e.id)) local.video(e.id).exists() else downloads.isComplete(e.id) }) }.filter { it.episodes.isNotEmpty() }
    /** The shows, and whether they came from the saved copy (offline); [saved]: the saved copy at once, without the PC. */
    suspend fun shows(saved: Boolean = false): Pair<List<Show>, Boolean> {
        showMeta // starts the YouTube pictures fill once
        val api = api
        val mine = withContext(Dispatchers.IO) { local.shows() }
        val t0 = android.os.SystemClock.uptimeMillis()
        val (pc, offline) = runCatching {
            check(!saved)
            check(settings.server.isNotEmpty()) { "no server" } // without a PC: the device's own shows only
            val json = api.showsJson()
            pending.flush(api, force = true)
            withContext(Dispatchers.IO) { showsCache.writeText(json) }
            api.parseShows(json) to false
        }.getOrElse { e ->
            val cached = withContext(Dispatchers.IO) { if (showsCache.exists()) showsCache.readText() else null }
            if (cached == null && mine.isEmpty() && settings.server.isNotEmpty()) throw e
            (cached?.let { api.parseShows(it) } ?: emptyList()) to true
        }
        if (!saved) android.util.Log.i("kumapie", "shows: PC ${android.os.SystemClock.uptimeMillis() - t0} ms${if (offline) " (no answer)" else ""}")
        // The PC lists episodes made on devices too (for the TV); here they come through Sync, so only once.
        val pcOwn = pc.map { s -> s.copy(episodes = s.episodes.filterNot { LocalEpisodes.isLocal(it.id) }) }.filter { it.episodes.isNotEmpty() }
        // A device-made show without a match of its own: the PC's poster (the PC looks every show up).
        val pcPoster = pc.flatMap { s -> s.episodes.map { it.id to s.poster } }.toMap()
        // Easy scenes and lines: the PC's count (the device's list has 0 easy scenes; its own lines over that showed "316% easy").
        val pcCount = pc.flatMap { s -> s.episodes.map { it.id to it } }.toMap()
        val mineShown = mine.map { s -> s.copy(episodes = s.episodes.map { e -> pcCount[e.id]?.let { p -> e.copy(easy = p.easy, scenes = p.scenes, seen = p.seen, watchedFlag = p.watchedFlag) } ?: e }) }
            .map { s -> if (s.poster.isNotEmpty()) s else s.copy(poster = s.episodes.firstNotNullOfOrNull { pcPoster[it.id]?.ifEmpty { null } } ?: "") }
        val m = progress.merged
        return withContext(Dispatchers.IO) { playableHere(mineShown + pcOwn) }.also { lastShows = it }.map { s ->
            // A "not watched" mark: the PC's own count is from before it, only the merged scenes count.
            s.copy(episodes = s.episodes.map { e -> e.copy(resume = m.pos[e.id] ?: e.resume, resumedAt = maxOf(e.resumedAt, m.posAt[e.id] ?: 0),
                // Lines heard: the PC's count; after a "not watched" here it may still be the old one until the PC knows.
                seen = if (e.id in m.unwatched && m.ranges[e.id].isNullOrEmpty()) 0 else e.seen,
                watchedFlag = (e.watchedFlag && e.id !in m.unwatched) || e.id in m.done) })
        } to offline
    }

    /**
     * An episode to play: its download at once (the PC's fresher copy is saved for the next time), else fresh from the
     * PC. A downloaded video plays from the device.
     */
    suspend fun episode(id: String): EpisodeDetail {
        val api = api
        // Parsed off the main thread: a long episode's JSON and its word colours held the first frames.
        suspend fun parse(json: String, work: (EpisodeDetail) -> String = { it.work }) = withContext(Dispatchers.Default) {
            withSeen(api.parseEpisode(json).let { it.copy(work = work(it)) }.let { languages.of(it.lang).known.run { refreshDue(); apply(it) } })
        }
        if (LocalEpisodes.isLocal(id)) {
            val json = withContext(Dispatchers.IO) { local.json(id).readText() }
            // The show's Japanese title from its metadata (AniList via Jimaku or the Add sheet).
            val detail = parse(json) { d -> local.meta(local.showId(d.show))?.optJSONObject("title")?.optString("ja").orEmpty() }
            // Its copy deleted here: streamed from the PC (sync keeps every device episode there).
            val video = local.video(id).takeIf { it.exists() }?.let { Uri.fromFile(it).toString() }
                ?: settings.server.takeIf { it.isNotBlank() }?.let { "$it/api/sync/${settings.owner}/$id/video.mp4" }.orEmpty()
            return detail.copy(video = video, resume = progress.merged.pos[id])
        }
        val downloaded = downloads.isComplete(id)
        // When the PC answers, send what waited, then fetch again so the episode has that progress in it.
        suspend fun fetch() = runCatching {
            val first = api.episodeJson(id)
            if (pending.count() > 0 && pending.flush(api, force = true)) api.episodeJson(id) else first
        }.getOrNull()
        // A download doesn't wait for the PC (8 s when it can't be reached); the fresher colours come next time.
        if (downloaded) background.launch {
            fetch()?.let { json -> downloads.episodeJson(id).let { f -> File(f.path + ".new").apply { writeText(json) }.renameTo(f) } }
        }
        val json = (if (downloaded) withContext(Dispatchers.IO) { downloads.episodeJson(id).readText() } else fetch())
            ?: throw IOException(tr("The PC doesn't answer and this episode isn't downloaded."))
        val detail = parse(json)
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
        val unwatched = d.id in m.unwatched // the PC's own count is from before the mark
        val ranges = io.github.pedrubik2000.kumapie.data.Ranges.union((if (unwatched) emptyList() else d.ranges) + m.ranges[d.id].orEmpty())
        return d.copy(ranges = ranges, scenes = d.scenes.map { s ->
            s.copy(seen = (s.seen && !unwatched) || ranges.any { s.middle in it.start..it.end })
        })
    }

    /** Stats from [Progress] ([Progress.stats], shared with the TV without a PC), synced first. */
    suspend fun stats(): io.github.pedrubik2000.kumapie.data.Stats {
        syncProgress()
        return progress.stats(runCatching { shows().first }.getOrDefault(emptyList()))
    }

    suspend fun langProgress(lang: String, shows: List<Show>) = withContext(Dispatchers.IO) {
        Progress.homeTotal(lang, progress.merged, shows, languages.of(lang).known.knownCount())
    }

    /** Every episode of [shows] with its scenes and word colours (fetched once per app run; unreachable ones skipped). */
    suspend fun allEpisodes(shows: List<Show>, progress: (String) -> Unit = {}): List<Pair<Show, EpisodeDetail>> = kotlinx.coroutines.coroutineScope {
        val list = shows.flatMap { s -> s.episodes.map { s to it } }
        // The due cards once here, not from each episode's parse at the same time.
        shows.map { it.lang }.toSet().forEach { languages.of(it).known.refreshDue() }
        // Four at a time: each is a PC round trip (~800 KB) and a parse; the feed read 151 one by one in 15 s on the tablet.
        val sem = kotlinx.coroutines.sync.Semaphore(4)
        val n = java.util.concurrent.atomic.AtomicInteger()
        list.map { (show, ep) ->
            async {
                sem.withPermit {
                    progress(tr("Reading episodes %1\$d of %2\$d…", n.incrementAndGet(), list.size))
                    val detail = episodeCache[ep.id]?.let(::withSeen) ?: runCatching { episode(ep.id) }.getOrNull()?.also { episodeCache[ep.id] = it }
                    detail?.let { show to it }
                }
            }
        }.awaitAll().filterNotNull()
    }

    /** Counts an episode as watched (seen before kumapie, or elsewhere), on every device. */
    suspend fun markWatched(id: String) {
        val detail = episodeCache[id] ?: episode(id)
        val end = maxOf(detail.duration, detail.scenes.maxOfOrNull { it.end } ?: 0.0)
        progress.record(id, null, 0.0,
            ranges = listOf(io.github.pedrubik2000.kumapie.data.Span(0, (end * 1000).toLong())), done = true)
        syncLater()
    }

    /** Undoes [markWatched] or a real watch: no scene of it counts as seen any more, and it starts from the beginning. */
    suspend fun markUnwatched(id: String) {
        progress.unwatch(id)
        if (settings.server.isNotEmpty()) pending.add(Pending.progress(id, 0.0, 0.0)) // the TV starts it from the beginning too
        syncLater()
    }

    private val episodeCache = java.util.concurrent.ConcurrentHashMap<String, EpisodeDetail>()

    /** Pictures of downloaded episodes and their shows, so lists offline have them too. */
    fun thumb(id: String, url: String): Any = downloads.thumb(id).takeIf { it.exists() } ?: url
    fun poster(showId: String, url: String): Any = downloads.poster(showId).takeIf { it.exists() } ?: url

    /** The player's backend; [lang] is the episode's language, so its marks go to that language's list. */
    fun backend(lang: String = "de"): Backend {
        val here = NoPcBackend(progress, lang, { languages.ofWord(it, lang) }, { surface, l -> wordAudio(surface, l) }, { languages.of(lang).voice.speak(it) }, ::syncLater)
        return if (settings.server.isEmpty()) here else OfflineBackend(api, downloads, pending, here) { languages.ofWord(it, lang).lang.code == "de" }
    }

    /**
     * A person's recording of the word in the episode's language first (saved, or from Wikimedia Commons, then saved);
     * a computer voice only without one: the PC's that a download brought, else the device's. Offline, a recording not
     * saved yet fails to play and the player says the word with the device voice.
     */
    private fun wordAudio(surface: String, lang: String): String {
        dictionary.recording(surface, lang)?.let { recording ->
            if (recording.startsWith("http")) background.launch { dictionary.keepRecording(surface, recording, lang) }
            return recording
        }
        downloads.wordFile(surface).takeIf { it.exists() }?.let { return it.path }
        return "tts:$surface"
    }
}

/**
 * With a PC: [here] (the no-PC backend: Progress and the colour lists, at once) and, on top, downloaded audio and
 * reports queued while the PC can't be reached.
 */
private class OfflineBackend(
    private val api: Api,
    private val downloads: Downloads,
    private val pending: Pending,
    private val here: NoPcBackend,
    /** The PC only knows German marks. */
    private val german: (String) -> Boolean,
) : Backend by here {

    /** The PC gets only the position, so the TV resumes there (its history is part of Progress as the "pc" note). */
    override suspend fun progress(episode: String, pos: Double, watched: Double, ranges: List<io.github.pedrubik2000.kumapie.data.Span>, done: Boolean) {
        here.progress(episode, pos, watched, ranges, done)
        pending.add(Pending.progress(episode, pos, 0.0))
        pending.flush(api)
    }

    override suspend fun markKnown(word: String, known: Boolean, scene: String): String {
        val mine = here.markKnown(word, known, scene)
        if (!german(word)) return mine
        if (pending.flush(api) && runCatching { api.markKnown(word, known) }.isSuccess) return mine
        pending.add(Pending.known(word, known))
        return mine
    }

    override fun definitionAudio(scene: String, line: Int, word: String): String =
        downloads.definitionFile(scene, line, word).takeIf { it.exists() }?.path
            ?: api.definitionAudio(scene, line, word)
}
