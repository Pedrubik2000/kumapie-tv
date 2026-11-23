package io.github.pedrubik2000.kumapie.data

import android.content.SharedPreferences
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
) {
    class Api(
        val lookup: suspend (String) -> Int?,
        val entry: suspend (String, Int) -> AniList.Entry,
        val save: suspend (String, Int, AniList.Entry) -> AniList.Entry,
    )

    private val fired = HashSet<String>()

    fun added(id: String) = queue(id) { it.remove(STATUS); it.put(ADDED, true) }

    fun watching(id: String, episode: Int) = queue(id) { it.remove(STATUS); it.put(WATCHING, episode) }

    fun finished(id: String, episode: Int) = queue(id) { it.remove(STATUS); it.put(FINISHED, maxOf(it.optInt(FINISHED), episode)) }

    fun status(id: String, status: String) = queue(id) { it.remove(ADDED); it.remove(WATCHING); it.put(STATUS, status) }

    fun score(id: String, score: Double) = queue(id) { it.put(SCORE, score) }

    fun watchingAt(episodeId: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0 || positionMs <= durationMs / 8 || !fired.add(episodeId)) return
        episodeId.substringAfterLast(':').toIntOrNull()?.let { watching(episodeId, it) }
    }

    fun retry() { if (token().isNotEmpty()) scope.launch { lock.withLock { flush() } } }

    private fun token() = prefs.getString("anilist_token", "").orEmpty()

    private fun queue(id: String, change: (JSONObject) -> Unit) {
        val title = titleOf(id) ?: return
        if (token().isEmpty()) return
        scope.launch {
            lock.withLock {
                val all = pending()
                change(all.optJSONObject(title) ?: JSONObject().also { all.put(title, it) })
                prefs.edit().putString(QUEUE, all.toString()).apply()
                flush()
            }
        }
    }

    private fun pending() = runCatching { JSONObject(prefs.getString(QUEUE, "{}").orEmpty()) }.getOrDefault(JSONObject())

    private suspend fun flush() {
        val token = token().ifEmpty { return }
        val all = pending()
        for (title in all.keys().asSequence().toList()) {
            val done = try { send(token, title, all.getJSONObject(title)); true } catch (e: AniList.LoggedOut) {
                prefs.edit().putString("anilist_token", "").putBoolean("anilist_logged_out", true).apply()
                return
            } catch (e: Exception) {
                android.util.Log.w("kumapie", "AniList sync kept for later: $e")
                false
            }
            if (done) { all.remove(title); prefs.edit().putString(QUEUE, all.toString()).apply() }
        }
    }

    private suspend fun send(token: String, title: String, want: JSONObject) {
        val media = api.lookup(title) ?: return
        val before = api.entry(token, media)
        val after = apply(before, want, today())
        if (after != before) api.save(token, media, after)
    }

    companion object {
        private const val QUEUE = "anilist_queue"
        private const val ADDED = "added"
        private const val WATCHING = "watching"
        private const val FINISHED = "finished"
        private const val STATUS = "status"
        private const val SCORE = "score"
        private val shared = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val lock = Mutex()
        private val STARTABLE = setOf(null, "PLANNING", "PAUSED", "DROPPED")

        fun titleOf(id: String): String? = Regex("""^kitsu:\d+""").find(id)?.value

        fun apply(e: AniList.Entry, want: JSONObject, today: String): AniList.Entry {
            var n = e
            if (want.optBoolean(ADDED) && n.status == null) n = n.copy(status = "PLANNING")
            if (want.has(WATCHING)) {
                if (n.status in STARTABLE) n = n.copy(status = "CURRENT")
                if (n.started.isEmpty() && n.status != "COMPLETED") n = n.copy(started = today)
            }
            if (want.has(FINISHED)) n = AniList.watched(n, want.getInt(FINISHED), today)
            want.optString(STATUS).takeIf { it.isNotEmpty() }?.let { s ->
                n = n.copy(status = s)
                if (s == "COMPLETED" && n.completed.isEmpty()) n = n.copy(completed = today)
                if (s == "CURRENT" && n.started.isEmpty()) n = n.copy(started = today)
            }
            if (want.has(SCORE)) n = n.copy(score = want.getDouble(SCORE))
            return n
        }

        fun live(prefs: SharedPreferences) = Api({ AniList.idOfKitsu(it, prefs) }, AniList::entry, AniList::save)
    }
}
