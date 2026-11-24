package io.github.pedrubik2000.kumapie.lang

import org.json.JSONArray
import org.json.JSONObject

/**
 * One language's imported known words and their sync time. The list describes the person, not a device: the newest
 * list wins, whole, everywhere. A local import or removal ([ImportedSources.JITEN]) is "my whole list is now this" and
 * empties the other sources; a newer list from another device or the PC ([use]) is stored as [ImportedSources.PEERS] and
 * empties the rest too, keeping the sender's time (an equal time is never applied again). The wire is unchanged: [body] sends
 * `{time, words}` (the union, the words only until the PC has them).
 */
class ImportedLists(private val sources: ImportedSources, private val stamps: Stamps, private val code: String,
                    private val now: () -> Long = System::currentTimeMillis) {
    interface Stamps {
        fun get(key: String, default: Long): Long
        fun put(key: String, value: Long)
    }

    @Volatile private var bySource = sources.read()
    @Volatile var words: Set<String> = ImportedSources.union(bySource)
        private set

    fun reload() {
        bySource = sources.read()
        words = ImportedSources.union(bySource)
    }

    fun set(source: String, new: Set<String>, time: Long = now()): Boolean {
        if (!sources.write(source, new)) return false
        listOf(ImportedSources.JITEN, ImportedSources.LEGACY, ImportedSources.PEERS).filter { it != source }.forEach { sources.write(it, emptySet()) }
        reload()
        stamps.put("imported_time_$code", time)
        stamps.put("imported_sent_$code", -1)
        return true
    }

    fun time() = stamps.get("imported_time_$code", if (words.isEmpty()) 0 else 1)

    fun body(): JSONObject {
        val time = time()
        val imp = JSONObject().put("time", time)
        if (stamps.get("imported_sent_$code", -1) != time) imp.put("words", JSONArray(words.sorted()))
        return JSONObject().put("imported", imp)
    }

    fun use(o: JSONObject): Boolean {
        val imp = o.optJSONObject("imported") ?: return false
        val time = imp.getLong("time")
        val list = imp.optJSONArray("words")
        val took = list != null && time > time() && set(ImportedSources.PEERS, (0 until list.length()).map(list::getString).toSet(), time)
        if (time == time()) stamps.put("imported_sent_$code", time)
        return took
    }
}
