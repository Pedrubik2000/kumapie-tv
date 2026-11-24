package io.github.pedrubik2000.kumapie.data

import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class AniListSync(
    private val prefs: SharedPreferences,
    private val api: Api = live(prefs),
    private val scope: CoroutineScope = shared,
    private val today: () -> String = { java.time.LocalDate.now().toString() },
    private val now: () -> Long = System::currentTimeMillis,
    private val later: (Long, () -> Unit) -> Unit = { ms, f -> scope.launch { delay(ms); f() } },
) {
    class Api(
        val lookup: suspend (String) -> Int?,
        val entry: suspend (String, Int) -> AniList.Entry,
        val save: suspend (String, Int, AniList.Entry, AniList.Entry) -> AniList.Entry,
        val offset: () -> Long? = { AniList.serverOffset },
        val format: suspend (String) -> String = { AniList.viewer(it).second },
    )

    class Seen(val entry: AniList.Entry, val format: String?)

    enum class SyncState { NONE, SAVED, WAITING }

    private val fired = HashSet<String>()

    val changes: StateFlow<Int> get() = version

    fun stateOf(id: String): SyncState {
        val title = titleOf(id) ?: return SyncState.NONE
        return synchronized(store) {
            when {
                pending().has(title) -> if (title in stuck) SyncState.WAITING else SyncState.NONE
                title in sent -> SyncState.SAVED
                else -> SyncState.NONE
            }
        }
    }

    private fun mark(title: String, saved: Boolean, waiting: Boolean?) {
        synchronized(store) {
            if (saved) sent.add(title) else sent.remove(title)
            when (waiting) {
                true -> stuck.add(title)
                false -> stuck.remove(title)
                null -> Unit
            }
        }
        version.update { it + 1 }
    }

    fun added(id: String) = queue(id) { it.put(ADDED, true) }

    fun watching(id: String, episode: Int) = queue(id) { it.remove(STATUS); it.put(WATCHING, episode) }

    fun finished(id: String, episode: Int) = queue(id) { it.remove(STATUS); it.put(FINISHED, maxOf(it.optInt(FINISHED), episode)) }

    fun status(id: String, status: String) = queue(id) { it.remove(ADDED); it.remove(WATCHING); it.put(STATUS, status) }

    fun score(id: String, score: Double) {
        if (score.isFinite() && score >= 0) queue(id) { it.put(SCORE, score) }
    }

    fun episodeDone(id: String, type: String) {
        episodeOf(id, type == "movie")?.let { finished(id, it) }
    }

    fun watchingAt(episodeId: String, type: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0 || positionMs <= durationMs / 8 || !fired.add(episodeId)) return
        episodeOf(episodeId, type == "movie")?.let { watching(episodeId, it) }
    }

    suspend fun read(token: String, id: String): Seen {
        val title = titleOf(id) ?: throw java.io.IOException("not a kitsu title")
        mark(title, saved = false, waiting = null)
        val media = api.lookup(title) ?: throw java.io.IOException("no AniList mapping")
        val entry = lock.withLock { apply(api.entry(token, media), pending().optJSONObject(title) ?: JSONObject(), today()) }
        val format = try { api.format(token) } catch (e: AniList.LoggedOut) { throw e } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        if (synchronized(store) { pending().length() > 0 }) retry()
        return Seen(entry, format)
    }

    suspend fun flushNow(): Int {
        lock.withLock { flush() }
        return waiting()
    }

    fun waiting(): Int = synchronized(store) { pending().length() }

    fun retry() { if (token().isNotEmpty()) scope.launch { lock.withLock { flush() } } }

    private fun token() = prefs.getString("anilist_token", "").orEmpty()

    private fun offset(): Long? = api.offset() ?: prefs.takeIf { it.contains(OFFSET) }?.getLong(OFFSET, 0)

    private fun clock() = now() + (offset() ?: 0)

    private fun queue(id: String, change: (JSONObject) -> Unit) {
        val title = titleOf(id) ?: return
        if (token().isEmpty()) return
        synchronized(store) {
            val all = pending()
            val item = all.optJSONObject(title) ?: JSONObject().also { all.put(title, it) }
            change(item)
            item.put(AT, clock()).put(SRV, offset() != null)
            prefs.edit().putString(QUEUE, all.toString()).apply()
        }
        mark(title, saved = false, waiting = false)
        scope.launch { lock.withLock { flush() } }
    }

    private fun pending() = runCatching { JSONObject(prefs.getString(QUEUE, "{}").orEmpty()) }.getOrDefault(JSONObject())

    private enum class Outcome { DONE, DROPPED, KEEP, STOP }

    private suspend fun flush() {
        val token = token().ifEmpty { return }
        if (now() < prefs.getLong(PAUSE, 0)) return
        for (title in pending().keys().asSequence().toList()) {
            if (token() != token) return
            val item = pending().optJSONObject(title) ?: continue
            val outcome = try { send(token, title, item) } catch (e: CancellationException) { throw e } catch (e: Exception) { failed(title, e) }
            when (outcome) {
                Outcome.STOP -> return
                Outcome.KEEP -> continue
                Outcome.DONE -> settle(title, token, item.toString(), true)
                Outcome.DROPPED -> settle(title, token, item.toString(), false)
            }
        }
    }

    private fun settle(title: String, token: String, sentItem: String, saved: Boolean) {
        val removed = synchronized(store) {
            if (token() != token || !prefs.contains(QUEUE)) return@synchronized false
            val all = pending()
            (all.optJSONObject(title)?.toString() == sentItem).also { if (it) prefs.edit().putString(QUEUE, all.also { a -> a.remove(title) }.toString()).apply() }
        }
        if (removed) mark(title, saved = saved, waiting = false)
    }

    private fun failed(title: String, e: Exception): Outcome {
        if (e !is AniList.LoggedOut && !(e is AniList.Failed && e.permanent)) mark(title, saved = false, waiting = true)
        when {
            e is AniList.LoggedOut -> {
                forget(prefs)
                prefs.edit().putString("anilist_token", "").putBoolean("anilist_logged_out", true).apply()
            }
            e is AniList.Failed && e.code == 429 -> {
                val wait = e.retryAfter * 1000
                prefs.edit().putLong(PAUSE, now() + wait).apply()
                later(wait + 1000) { retry() }
            }
            e is AniList.Failed && e.permanent -> return dropped(title, "AniList refused it: ${e.message}")
            e is java.io.IOException -> android.util.Log.w("kumapie", "AniList sync $title kept, no network: $e")
            else -> { android.util.Log.w("kumapie", "AniList sync $title kept for later: $e"); return Outcome.KEEP }
        }
        return Outcome.STOP
    }

    private fun dropped(title: String, why: String): Outcome {
        android.util.Log.w("kumapie", "AniList sync $title dropped: $why")
        return Outcome.DROPPED
    }

    private suspend fun send(token: String, title: String, want: JSONObject): Outcome {
        val server = want.optBoolean(SRV)
        if ((if (server) clock() else now()) - want.optLong(AT) > DAY) return dropped(title, "older than 24 hours")
        val media = try { api.lookup(title) } catch (e: java.io.IOException) { if (gone(e)) null else throw e }
            ?: return dropped(title, "no AniList mapping")
        val before = api.entry(token, media)
        offset()?.let { prefs.edit().putLong(OFFSET, it).apply() }
        val u = before.updatedAt
        if (server && offset() != null && u * 1000 > want.optLong(AT) && u > own(title)) return dropped(title, "the entry was edited after it")
        if (token() != token) return Outcome.DONE
        val after = apply(before, want, today())
        if (after != before && after.status != null) {
            val done = api.save(token, media, before, after)
            if (token() == token && done.updatedAt > 0) remember(title, done.updatedAt)
        }
        return Outcome.DONE
    }

    private fun gone(e: java.io.IOException) = e is java.io.FileNotFoundException || e.message.orEmpty().contains("response code: 410")

    private fun own(title: String): Long = runCatching { JSONObject(prefs.getString(OWN, "{}").orEmpty()).optLong(title) }.getOrDefault(0)

    private fun remember(title: String, updatedAt: Long) {
        val all = runCatching { JSONObject(prefs.getString(OWN, "{}").orEmpty()) }.getOrDefault(JSONObject())
        prefs.edit().putString(OWN, all.put(title, updatedAt).toString()).apply()
    }

    companion object {
        private val store = Any()
        private val sent = HashSet<String>()
        private val stuck = HashSet<String>()
        private val version = MutableStateFlow(0)

        fun resetState() {
            synchronized(store) {
                sent.clear()
                stuck.clear()
            }
            version.update { it + 1 }
        }

        private const val QUEUE = "anilist_queue"
        private const val PAUSE = "anilist_pause"
        private const val AT = "at"
        private const val SRV = "srv"
        private const val OWN = "anilist_own"
        private const val OFFSET = "anilist_offset"
        private const val DAY = 24 * 3600 * 1000L
        private const val ADDED = "added"
        private const val WATCHING = "watching"
        private const val FINISHED = "finished"
        private const val STATUS = "status"
        private const val SCORE = "score"
        private val shared = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            android.util.Log.w("kumapie", "AniList sync failed: $e")
        })
        private val lock = Mutex()
        private val STARTABLE = setOf(null, "PLANNING", "PAUSED", "DROPPED")

        fun titleOf(id: String): String? = Regex("""^kitsu:\d+""").find(id)?.value

        fun episodeOf(id: String, movie: Boolean): Int? {
            val p = id.split(':')
            if (p.size < 2 || p.size > 3 || p[0] != "kitsu" || p[1].isEmpty() || !p[1].all(Char::isDigit)) return null
            if (p.size == 3) return p[2].toIntOrNull()?.takeIf { it > 0 }
            return if (movie) 1 else 0
        }

        fun forget(prefs: SharedPreferences) {
            resetState()
            val e = prefs.edit().remove(QUEUE).remove(PAUSE).remove(OWN).remove("anilist_viewer")
            prefs.all.keys.filter { it.startsWith(AniListLists.CACHE) }.forEach { e.remove(it) }
            e.apply()
        }

        private fun number(n: Int, e: AniList.Entry): Int? =
            (if (n > 0) n else 1.takeIf { e.episodes == 1 })?.takeIf { e.episodes == null || it <= e.episodes }

        fun apply(e: AniList.Entry, want: JSONObject, today: String): AniList.Entry {
            var n = e
            if (want.optBoolean(ADDED) && n.status == null) n = n.copy(status = "PLANNING")
            if (want.has(WATCHING) && number(want.getInt(WATCHING), e) != null) {
                if (n.status in STARTABLE) n = n.copy(status = "CURRENT")
                if (n.started.isEmpty() && n.status != "COMPLETED") n = n.copy(started = today)
            }
            if (want.has(FINISHED)) number(want.getInt(FINISHED), e)?.let { n = AniList.watched(n, it, today) }
            want.optString(STATUS).takeIf { it.isNotEmpty() }?.let { s ->
                n = n.copy(status = s)
                if (s == "COMPLETED" && n.completed.isEmpty()) n = n.copy(completed = today)
                if (s == "CURRENT" && n.started.isEmpty()) n = n.copy(started = today)
            }
            if (want.has(SCORE) && n.status != null) n = n.copy(score = want.getDouble(SCORE))
            return n
        }

        fun live(prefs: SharedPreferences) = Api(
            lookup = { AniList.idOfKitsu(it, prefs) }, entry = AniList::entry,
            save = { t, m, before, after -> AniList.save(t, m, after, before) },
        )
    }
}
