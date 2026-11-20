package io.github.pedrubik2000.kumapie

import io.github.pedrubik2000.kumapie.data.AniList
import io.github.pedrubik2000.kumapie.data.AniList.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Item 4w: what an episode watched to the end does to the AniList entry, and typed scores. */
class AniListEntryTest {
    private val today = "2026-11-20"

    @Test fun newShowStartsToday() =
        assertEquals(Entry("CURRENT", progress = 1, started = today, episodes = 12), AniList.watched(Entry(episodes = 12), 1, today))

    @Test fun partlyWatchedKeepsNoStart() =
        assertEquals(Entry("CURRENT", progress = 5, episodes = 12), AniList.watched(Entry("PAUSED", progress = 4, episodes = 12), 5, today))

    @Test fun neverLower() {
        val e = Entry("CURRENT", progress = 6, episodes = 12)
        assertEquals(e, AniList.watched(e, 3, today))
    }

    @Test fun lastEpisodeCompletes() = assertEquals(Entry("COMPLETED", progress = 12, started = "2026-10-01", completed = today, episodes = 12),
        AniList.watched(Entry("CURRENT", progress = 11, started = "2026-10-01", episodes = 12), 12, today))

    @Test fun rewatchCountsOneMore() = assertEquals(2, AniList.watched(Entry("REPEATING", progress = 11, repeat = 1, episodes = 12), 12, today).repeat)

    @Test fun scores() {
        assertEquals(8.0, AniList.parseScore("8", "POINT_10")!!, 0.0)
        assertNull(AniList.parseScore("8.5", "POINT_10"))
        assertEquals(8.5, AniList.parseScore("8,5", "POINT_10_DECIMAL")!!, 0.0)
        assertNull(AniList.parseScore("11", "POINT_10"))
        assertEquals(85.0, AniList.parseScore("85", "POINT_100")!!, 0.0)
        assertEquals(0.0, AniList.parseScore("", "POINT_5")!!, 0.0)
    }

    @Test fun tokenFromRedirect() =
        assertEquals("abc.def", AniList.tokenOf("kumapie://anilist-auth#access_token=abc.def&token_type=Bearer&expires_in=31536000"))
}
