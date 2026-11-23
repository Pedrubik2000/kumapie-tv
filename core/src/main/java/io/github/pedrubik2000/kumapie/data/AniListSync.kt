package io.github.pedrubik2000.kumapie.data

import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
) {
    class Api(
        val lookup: suspend (String) -> Int?,
        val entry: suspend (String, Int) -> AniList.Entry,
        val save: suspend (String, Int, AniList.Entry, AniList.Entry) -> AniList.Entry,
    )

    private val fired = HashSet<String>()

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

    fun retry() { if (token().isNotEmpty()) scope.launch { lock.withLock { flush() } } }

    private fun token() = prefs.getString("anilist_token", "").orEmpty()

    private fun queue(id: String, change: (JSONObject) -> Unit) {
        val title = titleOf(id) ?: return
        if (token().isEmpty()) return
        scope.launch {
            lock.withLock {
                val all = pending()
                val item = all.optJSONObject(title) ?: JSONObject().put(AT, now()).also { all.put(title, it) }
                change(item)
                prefs.edit().putString(QUEUE, all.toString()).apply()
                flush()
            }
        }
    }

    private fun pending() = runCatching { JSONObject(prefs.getString(QUEUE, "{}").orEmpty()) }.getOrDefault(JSONObject())

    private suspend fun flush() {
        val token = token().ifEmpty { return }
        if (now() < prefs.getLong(PAUSE, 0)) return
        val all = pending()
        for (title in all.keys().asSequence().toList()) {
            try {
                send(token, title, all.getJSONObject(title))
            } catch (e: CancellationException) {
                throw e
            } catch (e: AniList.LoggedOut) {
                forget(prefs)
                prefs.edit().putString("anilist_token", "").putBoolean("anilist_logged_out", true).apply()
                return
            } catch (e: AniList.Failed) {
                if (e.code == 429) { prefs.edit().putLong(PAUSE, now() + e.retryAfter * 1000).apply(); return }
                if (!e.permanent) return
                android.util.Log.w("kumapie", "AniList sync dropped: $e")
            } catch (e: Exception) {
                android.util.Log.w("kumapie", "AniList sync kept for later: $e")
                return
            }
            all.remove(title)
            prefs.edit().putString(QUEUE, all.toString()).apply()
        }
    }

    private suspend fun send(token: String, title: String, want: JSONObject) {
        if (now() - want.optLong(AT) > DAY) return
        val media = api.lookup(title) ?: return
        val before = api.entry(token, media)
        if (before.updatedAt * 1000 > want.optLong(AT)) return
        val after = apply(before, want, today())
        if (after != before && after.status != null) api.save(token, media, before, after)
    }

    companion object {
        private const val QUEUE = "anilist_queue"
        private const val PAUSE = "anilist_pause"
        private const val AT = "at"
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
            val e = prefs.edit().remove(QUEUE).remove(PAUSE)
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

        fun live(prefs: SharedPreferences) = Api({ AniList.idOfKitsu(it, prefs) }, AniList::entry) { t, m, before, after -> AniList.save(t, m, after, before) }
    }
}
