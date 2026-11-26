package io.github.pedrubik2000.kumapie.data

import io.github.pedrubik2000.kumapie.i18n.tr
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * A Discover stream through the PC (kumapie://watch, kumapie-server discover.py), the TV's and the phone's: asks every
 * 3 s; [step] gets what the PC is doing, [url] the video link as soon as it exists (it plays at once), [next] the next
 * episode's id, [file] the file the PC took. Answers the PC's reply once the words are ready (its "id" and "url");
 * throws when no file is ready or the PC failed.
 */
suspend fun Api.watchReady(query: Map<String, String>, step: (String) -> Unit, url: (String) -> Unit, next: (String) -> Unit,
                           file: (String) -> Unit = {}): JSONObject {
    while (true) {
        val r = watch(query)
        next(r.optString("next"))
        r.optString("file").takeIf { it.isNotEmpty() }?.let(file)
        if (r.has("url")) url(r.getString("url"))
        when (r.optString("state")) {
            "ready" -> return r
            "none" -> error(tr("No file is ready for this episode yet: pick one in its files"))
            "failed" -> error(tr("The PC couldn't get it: %1\$s", r.optString("error")))
            else -> step(r.optString("step"))
        }
        delay(3_000)
    }
}

/** Where a stream starts once its words are ready: where the plain video got to ([at] s) when that is further in. */
fun resumeAt(at: Double, saved: Double?): Double? = if (at > 1 && at >= (saved ?: 0.0)) at else saved

/**
 * Starting a Discover episode, the TV's and the phone's: an addon's link ("url") plays on this device from its saved
 * place (over 20 s in, [RESUME_AFTER_S]), anything else goes to the PC ([watchReady]). [ready] is the PC's ready file, [picked] a file from
 * the list: an addon's link also puts the title on Home (no PC keeps "Continue watching"), answering the new list.
 */
sealed interface StreamStart {
    data class Alone(val url: String, val resumeAt: Double?) : StreamStart
    data class Pc(val query: Map<String, String>) : StreamStart

    companion object {
        fun of(query: Map<String, String>, saved: Double?): StreamStart =
            query["url"]?.takeIf { it.isNotEmpty() }?.let { Alone(it, saved?.takeIf { s -> s > RESUME_AFTER_S }) } ?: Pc(query)

        fun of(query: Map<String, String>, prefs: android.content.SharedPreferences): StreamStart =
            of(query, prefs.getFloat("alone_pos_${query["id"]}", 0f).toDouble())

        fun ready(title: Found, id: String, name: String) = mapOf("lang" to "ja", "type" to title.type, "id" to id, "title" to name, "show" to title.name)

        suspend fun picked(d: Discovery, title: Found, id: String, name: String, f: JSONObject, kept: Boolean?): Pair<Map<String, String>, List<Found>?> {
            if (!f.has("url")) return mapOf("lang" to "ja", "type" to title.type, "id" to id, "hash" to f.optString("hash"), "file" to f.optString("file"),
                "idx" to f.optInt("idx", -1).toString(), "title" to name, "show" to title.name, "label" to f.optString("short")) to null
            val list = if (kept == true) null else runCatching { d.save(title, true) }.getOrNull()
            return mapOf("lang" to "ja", "type" to title.type, "id" to id, "url" to f.getString("url"), "file" to f.optString("file"),
                "title" to name, "show" to title.name) to list
        }
    }
}

/**
 * A no-PC stream played to its end, the TV's and the phone's: its episode marked watched, then the next one's query:
 * a file of the same release group first, else the first that plays here, else the first ([nextQuery] over [files]).
 * Null: no next to look for (a movie, a whole title); throws when it has no files or the addons fail.
 */
suspend fun Addons.next(query: Map<String, String>, looking: (Int) -> Unit = {}): Map<String, String>? {
    val type = query["type"] ?: "series"
    val id = query["id"].orEmpty()
    done(id, type)
    val next = Addons.nextId(type, id) ?: return null
    val n = next.substringAfterLast(':').toInt()
    looking(n)
    val files = discoverStreams(type, next).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
    return nextQuery(query, files) ?: error(tr("No files found for episode %1\$d", n))
}

fun nextQuery(query: Map<String, String>, next: String): Map<String, String>? {
    if (next.isEmpty()) return null
    val show = query["show"].orEmpty()
    return mapOf("lang" to (query["lang"] ?: "ja"), "type" to (query["type"] ?: "series"), "id" to next, "show" to show,
        "title" to "$show ${next.substringAfterLast(':')}")
}

fun nextQuery(query: Map<String, String>, files: List<JSONObject>, has: Set<String> = Playable.device, narrow: Boolean = Playable.narrow): Map<String, String>? {
    val next = Addons.nextId(query["type"] ?: "series", query["id"].orEmpty()) ?: return null
    val plays = files.filter { Playable.missing(it.optString("label") + " " + it.optString("about") + " " + it.optString("file"), has).isEmpty() }
    val group = Regex("""^\[[^\]]+\]""").find(query["file"].orEmpty())?.value
    fun weight(f: JSONObject) = Playable.weight(f.optString("label") + " " + f.optString("about") + " " + f.optString("file"), narrow)
    val light = plays.sortedBy(::weight)
    val chosen = Playable.weight(query["file"].orEmpty(), narrow)
    val f = light.firstOrNull { group != null && it.optString("file").startsWith(group, ignoreCase = true) && weight(it) <= chosen } ?: light.firstOrNull() ?: files.firstOrNull() ?: return null
    return nextQuery(query, next)?.plus(listOf("url" to f.optString("url"), "file" to f.optString("file")))
}
