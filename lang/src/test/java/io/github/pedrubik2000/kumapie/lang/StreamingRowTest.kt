package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Addons
import io.github.pedrubik2000.kumapie.data.Found
import io.github.pedrubik2000.kumapie.data.streamingRow
import org.junit.Assert.assertEquals
import org.junit.Test

/** Home's Streaming row: the title played last first, never played ones after in kept order (core plan 7b). */
class StreamingRowTest {
    private val kept = listOf("kitsu:1", "kitsu:2", "kitsu:3", "kitsu:4").map { Found(it, "series", it, "", "") }

    private fun row(played: Map<String, Long>) = streamingRow(kept, played).map { it.id }

    @Test fun playedLastFirstNeverPlayedAfterInKeptOrder() =
        assertEquals(listOf("kitsu:3", "kitsu:2", "kitsu:1", "kitsu:4"), row(mapOf("kitsu:2" to 100L, "kitsu:3" to 200L)))

    @Test fun tiesKeepKeptOrder() {
        assertEquals(listOf("kitsu:2", "kitsu:4", "kitsu:1", "kitsu:3"), row(mapOf("kitsu:2" to 100L, "kitsu:4" to 100L)))
        assertEquals(kept.map { it.id }, row(emptyMap()))
    }

    // Addons' going() drops a title whose last episode is finished; its last play is still in alone_last_<title>.
    @Test fun aFinishedTitleStillCountsAsPlayed() {
        val prefs = mapOf<String, Any?>(
            "alone_last_kitsu:4" to """{"id":"kitsu:4:12","at":300}""", "alone_seen" to setOf("kitsu:4:12"),
            "alone_last_kitsu:2" to """{"id":"kitsu:2:3","at":100}""", "alone_pos_kitsu:2:3" to 30f, "alone_last_bad" to "{")
        assertEquals(mapOf("kitsu:4" to 300L, "kitsu:2" to 100L), Addons.lastPlays(prefs))
        assertEquals(listOf("kitsu:4", "kitsu:2", "kitsu:1", "kitsu:3"), row(Addons.lastPlays(prefs)))
    }
}
