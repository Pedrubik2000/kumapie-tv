package io.github.pedrubik2000.kumapie.data

import io.github.pedrubik2000.kumapie.i18n.tr

data class SimilarPick(
    val id: Int,
    val native: String,
    val romaji: String,
    val cover: String,
    val average: Int,
    val year: Int,
    val format: String,
    val episodes: Int?,
    val genres: List<String>,
    val reasons: List<String>,
    val listed: String,
)

object SimilarPicks {
    const val COUNT = 5
    private const val MAX_REASONS = 3
    private const val MAX_GENRES_SHOWN = 3
    private const val HIGH_SCORE = 75
    private const val QUERY_GENRES = 2
    private val SEEN = setOf("COMPLETED", "CURRENT", "REPEATING", "PAUSED", "DROPPED")

    fun query(me: Mine): AniListBrowse.Query = AniListBrowse.Query(sort = listOf("SCORE_DESC"), genreIn = me.genres.take(QUERY_GENRES).toSet())

    private fun shared(me: Mine, c: Mine): Int = c.genres.count { it in me.genres } + c.tags.count { it in me.tags }

    fun pick(me: Mine, candidates: List<Mine>, mine: Map<Int, String>, max: Int = COUNT): List<SimilarPick> = candidates
        .filter { it.anilistId != me.anilistId && mine[it.anilistId] !in SEEN && shared(me, it) > 0 }
        .distinctBy { it.anilistId }
        .sortedWith(compareBy({ -shared(me, it) }, { -it.average }))
        .take(max)
        .map { c ->
            SimilarPick(c.anilistId, c.native, c.romaji, c.cover, c.average, c.year, c.format, c.episodes, c.genres.take(MAX_GENRES_SHOWN),
                reasons(me, c), mine[c.anilistId].orEmpty())
        }

    fun reasons(me: Mine, c: Mine): List<String> {
        val tags = c.tags.filter { it in me.tags }.take(2).map { tr("Also tagged %s", it) }
        val genres = c.genres.filter { it in me.genres }.take(2).takeIf { it.isNotEmpty() }?.let { listOf(tr("Shares %s", it.joinToString(", "))) }.orEmpty()
        val year = if (me.year > 0 && c.year == me.year) listOf(tr("Also from %d", c.year)) else emptyList()
        val format = if (me.format.isNotEmpty() && c.format == me.format) listOf(tr("Same format: %s", formatLabel(c.format))) else emptyList()
        val score = if (c.average >= HIGH_SCORE) listOf(tr("Rated %d%% on AniList", c.average)) else emptyList()
        return (tags + genres + year + format + score).take(MAX_REASONS)
    }

    fun formatLabel(format: String): String = when (format) {
        "TV" -> "TV"
        "MOVIE" -> tr("Movie")
        "" -> ""
        else -> format.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
    }

    fun stats(p: SimilarPick): String = listOfNotNull(
        p.average.takeIf { it > 0 }?.let { "★ $it%" }, p.year.takeIf { it > 0 }?.toString(), formatLabel(p.format).takeIf { it.isNotEmpty() },
        p.episodes?.let { tr("%d eps", it) },
    ).joinToString(" · ")

    suspend fun addToPlanning(id: Int, save: suspend (Int, AniList.Entry) -> Unit): String {
        save(id, AniList.Entry(status = "PLANNING"))
        return "PLANNING"
    }
}
