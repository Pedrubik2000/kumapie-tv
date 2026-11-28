package io.github.pedrubik2000.kumapie.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object SeenMarks {
    const val KEY = "alone_marks"

    class Mark(val on: Boolean, val at: Long)

    fun read(text: String?): Map<String, Mark> = runCatching {
        JSONObject(text.orEmpty().ifEmpty { "{}" }).let { o ->
            o.keys().asSequence().mapNotNull { id -> o.optJSONArray(id)?.let { id to Mark(it.optInt(0) == 1, it.optLong(1)) } }.toMap()
        }
    }.getOrDefault(emptyMap())

    fun write(marks: Map<String, Mark>): String =
        JSONObject().apply { marks.toSortedMap().forEach { (id, m) -> put(id, JSONArray().put(if (m.on) 1 else 0).put(m.at)) } }.toString()

    private fun newer(a: Mark, b: Mark) = a.at > b.at || (a.at == b.at && !a.on && b.on)

    fun merge(mine: Map<String, Mark>, theirs: Map<String, Mark>): Map<String, Mark> =
        (mine.keys + theirs.keys).associateWith { id ->
            val (a, b) = mine[id] to theirs[id]
            if (a == null) requireNotNull(b) else if (b == null || !newer(b, a)) a else b
        }

    fun apply(seen: Set<String>, marks: Map<String, Mark>): Set<String> =
        seen + marks.filterValues { it.on }.keys - marks.filterValues { !it.on }.keys

    fun same(a: Map<String, Mark>, b: Map<String, Mark>) = write(a) == write(b)

    fun set(prefs: SharedPreferences, id: String, on: Boolean, now: Long = System.currentTimeMillis()) {
        prefs.edit().putString(KEY, write(read(prefs.getString(KEY, null)) + (id to Mark(on, now)))).apply()
    }
}
