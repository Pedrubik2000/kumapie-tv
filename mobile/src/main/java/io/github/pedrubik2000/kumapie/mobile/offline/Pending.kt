package io.github.pedrubik2000.kumapie.mobile.offline

import android.content.Context
import io.github.pedrubik2000.kumapie.data.Api
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Reports made while offline (progress, lookups, words marked known), one JSON object per line, sent to the
 * PC in order as soon as it answers again. Watched seconds add up on the server, so nothing is merged.
 */
class Pending(context: Context) {
    private val file = File(context.filesDir, "pending.jsonl")
    private val lock = Mutex()
    /** When the PC last failed to answer: no new attempt for [BACKOFF_MS] (each try waits for a timeout). */
    @Volatile private var failedAt = 0L

    suspend fun add(item: JSONObject) = lock.withLock {
        withContext(Dispatchers.IO) { file.appendText(item.toString() + "\n") }
    }

    suspend fun count(): Int = lock.withLock { lines().size }

    /**
     * Sends what is waiting, oldest first; stops at the first failure and keeps the rest. True if all went.
     * Shortly after a failure it doesn't try again unless [force]d.
     */
    suspend fun flush(api: Api, force: Boolean = false): Boolean = lock.withLock {
        val items = lines()
        if (items.isEmpty()) return@withLock true
        if (!force && System.currentTimeMillis() - failedAt < BACKOFF_MS) return@withLock false
        var sent = 0
        for (line in items) {
            val o = JSONObject(line)
            val ok = runCatching {
                when (o.getString("t")) {
                    "progress" -> api.progress(o.getString("episode"), o.getDouble("pos"),
                        o.getJSONArray("seen").let { a -> (0 until a.length()).map { a.getString(it) } },
                        o.getDouble("watched"))
                    "lookup" -> api.lookup(o.getString("word"), o.getString("scene"))
                    "known" -> api.markKnown(o.getString("word"), o.getBoolean("known"))
                }
            }.isSuccess
            if (!ok) break
            sent++
        }
        val rest = items.drop(sent)
        failedAt = if (rest.isEmpty()) 0L else System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            if (rest.isEmpty()) file.delete() else file.writeText(rest.joinToString("") { it + "\n" })
        }
        rest.isEmpty()
    }

    /** The newest position still waiting for [episode], so an offline episode resumes where it was left. */
    suspend fun lastPosition(episode: String): Double? = lock.withLock {
        lines().map(::JSONObject)
            .lastOrNull { it.optString("t") == "progress" && it.optString("episode") == episode }
            ?.getDouble("pos")
    }

    private suspend fun lines(): List<String> = withContext(Dispatchers.IO) {
        if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()
    }

    companion object {
        private const val BACKOFF_MS = 30_000L

        fun progress(episode: String, pos: Double, seen: Collection<String>, watched: Double): JSONObject =
            JSONObject().put("t", "progress").put("episode", episode).put("pos", pos)
                .put("seen", JSONArray(seen)).put("watched", watched)

        fun lookup(word: String, scene: String): JSONObject =
            JSONObject().put("t", "lookup").put("word", word).put("scene", scene)

        fun known(word: String, known: Boolean): JSONObject =
            JSONObject().put("t", "known").put("word", word).put("known", known)
    }
}
