package io.github.pedrubik2000.kumapie.lang

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Japanese words to import as known from jiten.moe's public API (kumapie_plan.md item 6): the most frequent words of
 * its global frequency list (CC BY-SA 4.0) and its word sets. Names and fillers need no list: Sudachi knows them
 * ([Japanese.autoKnown]).
 */
object Jiten {
    private const val API = "https://api.jiten.moe/api"

    /** Word sets worth importing: slug -> name (jiten.moe/guides/word-sets). The name sets are left out (Sudachi). */
    val SETS = linkedMapOf(
        "particle-grammar" to "Particles & common grammar",
        "counters" to "Counters",
        "onomatopoeia" to "Onomatopoeia",
        "kaishi-15" to "Kaishi 1.5k",
    )

    /** The words ranked 1..[n] in the global frequency list (CSV "Word,Form,Rank", ordered by rank). */
    fun top(n: Int): Set<String> {
        val out = HashSet<String>()
        open("$API/frequency-list/download?downloadType=csv").inputStream.bufferedReader().useLines { lines ->
            for (line in lines.drop(1)) {
                val cols = line.split(',')
                val rank = cols.lastOrNull()?.toIntOrNull() ?: continue
                if (rank > n) break
                cols[0].takeIf { it.isNotBlank() }?.let(out::add)
            }
        }
        return out
    }

    /** Every written form in a word set ("束[たば]" -> 束, and its kana form たば). */
    fun set(slug: String): Set<String> {
        val out = HashSet<String>()
        var offset = 0
        do {
            val page = JSONObject(open("$API/word-sets/$slug/vocabulary?offset=$offset&limit=100").inputStream.bufferedReader().readText())
            val data = page.getJSONArray("data")
            for (i in 0 until data.length()) {
                val w = data.getJSONObject(i)
                out += plain(w.getJSONObject("mainReading").getString("text"))
                val alt = w.optJSONArray("alternativeReadings")
                for (j in 0 until (alt?.length() ?: 0)) out += plain(alt!!.getJSONObject(j).getString("text"))
            }
            offset += data.length()
            Thread.sleep(200) // gently: one person's import, about 40 pages
        } while (data.length() > 0 && offset < page.optInt("totalItems"))
        return out - ""
    }

    /** Jiten's furigana as plain text: "番[ばん]目[め]" -> 番目. */
    fun plain(text: String) = text.replace(Regex("""\[[^\]]*]"""), "").trim()

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 60_000
        if (responseCode != 200) error("jiten.moe: HTTP $responseCode for $url")
    }
}
