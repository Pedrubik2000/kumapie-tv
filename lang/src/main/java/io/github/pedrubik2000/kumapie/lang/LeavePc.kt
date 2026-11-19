package io.github.pedrubik2000.kumapie.lang

import android.content.Context
import io.github.pedrubik2000.kumapie.data.Addons
import io.github.pedrubik2000.kumapie.data.Api
import org.json.JSONObject

/**
 * Leaving the PC (Settings > Use without the PC), one implementation for the TV and the phone/tablet: once more from
 * the PC, the first error stopping it so the caller switches nothing: the cards and their reviews and the imported
 * word lists ([Srs] takeFromPc), the words marked known, the personal dictionary, and the anime streamed through it
 * (kept titles, episodes watched, where each was left). The caller then sets no server.
 */
object LeavePc {
    /** Cards per language and how many words are marked known, for the log. */
    class Result(val cards: Map<String, Int>, val marks: Int) {
        override fun toString() = "$cards cards, $marks marks"
    }

    /**
     * [Progress] synced once more (every device's part and the PC's history: watch time, positions, lookups, the
     * words marked known, each language keeping its own), so Stats and Home's numbers go on without the PC.
     */
    suspend fun run(context: Context, pc: Api, server: String, person: String, languages: List<Language>, addons: Addons): Result {
        val cards = Cards.get(context).srs.takeFromPc(server, person, languages)
        val progress = Progress.of(context)
        progress.sync(pc)
        val marked = progress.merged.marked
        languages.forEach { l -> l.known.useMarked(marked.mapNotNull(l::wordOfMark).toSet()) }
        PersonalDictionary.get(context).sync(pc::personalDictionary)
        addons.keepFromPc(pc.saved(), pc.going())
        return Result(cards, marked.size).also { android.util.Log.i("kumapie", "left the PC: $it") }
    }

    /** Each part's `marked` ({word: [known, at seconds]}): the newest mark of a word wins; the words left known. */
    fun newestMarks(parts: List<JSONObject>): Set<String> {
        val latest = HashMap<String, Pair<Boolean, Long>>()
        for (p in parts) p.optJSONObject("marked")?.let { m ->
            for (k in m.keys()) {
                val a = m.optJSONArray(k) ?: continue
                if ((latest[k]?.second ?: -1) <= a.optLong(1)) latest[k] = a.getBoolean(0) to a.optLong(1)
            }
        }
        return latest.filterValues { it.first }.keys
    }
}
