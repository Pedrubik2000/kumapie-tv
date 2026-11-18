package io.github.pedrubik2000.kumapie.lang

import java.net.HttpURLConnection
import java.net.URL

/**
 * Japanese words to import as known (kumapie_plan.md item 6): the most frequent words of jiten.moe's global frequency
 * list (CC BY-SA 4.0) and kumapie's own word lists (wordlists/ja.tsv in this repo, from JMdict's tags by
 * kumapie-server tools/kumapie/wordlists.py; jiten.moe's word sets need a sign-in since 2026-11). Names and fillers
 * need no list: Sudachi knows them ([Japanese.autoKnown]).
 */
object Jiten {
    private const val API = "https://api.jiten.moe/api"
    private const val LISTS = "https://raw.githubusercontent.com/Pedrubik2000/kumapie-tv/main/wordlists/ja.tsv"

    /** The word lists: slug (the file's first column) -> name. */
    val SETS = linkedMapOf(
        "particles" to "Particles",
        "counters" to "Counters",
        "onomatopoeia" to "Onomatopoeia",
        "grammar" to "Grammar words",
        "honorific" to "Honorific & humble",
        "interjections" to "Interjections",
    )

    /** What the import panels (Settings, onboarding, TV) import: the top [n] words and the [sets]. Network: off the main thread. */
    fun words(n: Int, sets: Collection<String>): Set<String> =
        top(n) + if (sets.isEmpty()) emptySet() else open(LISTS).inputStream.bufferedReader().useLines { lists(it, sets.toSet()) }

    /** The words of [sets] in the lists file ("slug<TAB>form" lines, "#" comments). */
    fun lists(lines: Sequence<String>, sets: Set<String>): Set<String> =
        lines.filter { !it.startsWith("#") }.map { it.split('\t') }
            .filter { it.size == 2 && it[0] in sets && it[1].isNotBlank() }.map { it[1] }.toSet()

    /** The words ranked 1..[n] in the global frequency list (CSV "Word,Form,Rank", ordered by rank). */
    fun top(n: Int): Set<String> {
        val out = HashSet<String>()
        if (n <= 0) return out
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

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 60_000
        if (responseCode != 200) error("${URL(url).host}: HTTP $responseCode for $url")
    }
}
