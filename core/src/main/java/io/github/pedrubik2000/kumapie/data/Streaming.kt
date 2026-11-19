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

/** A no-PC stream played to its end ([Addons.ended]): episode [n] is next; [next] its id and file, null: no files for it. */
class Ended(val n: Int, val next: Pair<String, JSONObject>?)

/**
 * A no-PC stream played to its end, the TV's and the phone's: [id] marked watched, then the next episode's file
 * ([Addons.next]). Null when there is no next to look for (a movie, a whole title: kitsu:12346 is another show);
 * [looking] gets the next episode's number before the addons are asked. Throws when the addons fail.
 */
suspend fun Addons.ended(type: String, id: String, file: String, looking: (Int) -> Unit = {}): Ended? {
    runCatching { seen(id, true) }
    val nextId = Addons.nextId(type, id) ?: return null
    val n = nextId.substringAfterLast(':').toInt()
    looking(n)
    return Ended(n, next(type, id, file))
}
