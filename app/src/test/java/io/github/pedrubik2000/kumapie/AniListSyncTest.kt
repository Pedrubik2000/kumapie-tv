package io.github.pedrubik2000.kumapie

import io.github.pedrubik2000.kumapie.data.Addons
import io.github.pedrubik2000.kumapie.data.AniList
import io.github.pedrubik2000.kumapie.data.AniList.Entry
import io.github.pedrubik2000.kumapie.data.AniListLists
import io.github.pedrubik2000.kumapie.data.AniListSync
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Found
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.Show
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AniListSyncTest {
    private val prefs = Settings(RuntimeEnvironment.getApplication()).prefs
    private val entries = mutableMapOf(7 to Entry(episodes = 12), 8 to Entry(episodes = 1), 9 to Entry())
    private val saved = mutableListOf<Triple<Int, Entry, Entry>>()
    private var failure: Exception? = null
    private var asked = 0
    private var clock = 1_800_000_000_000L

    private val api = AniListSync.Api({ mapOf("kitsu:1" to 7, "kitsu:2" to 8, "kitsu:3" to 9)[it] }, { _, media ->
        asked++
        failure?.let { throw it }
        entries[media] ?: error("wrong media $media")
    }, { _, media, before, after -> saved += Triple(media, before, after); entries[media] = after; after })

    private fun sync() = AniListSync(prefs, api, CoroutineScope(Dispatchers.Unconfined), { "2026-11-23" }, { clock })

    @Before fun login() { prefs.edit().clear().putString("anilist_token", "t").commit() }

    @Test fun addedPlansOnlyWhenOnNoList() {
        sync().added("kitsu:1")
        assertEquals(listOf(Triple(7, Entry(episodes = 12), Entry("PLANNING", episodes = 12))), saved)
        entries[7] = Entry("DROPPED", episodes = 12)
        sync().added("kitsu:1")
        assertEquals(1, saved.size)
    }

    @Test fun watchingStartsIt() {
        entries[7] = Entry("PAUSED", episodes = 12)
        sync().watching("kitsu:1:1", 1)
        assertEquals(Entry("CURRENT", started = "2026-11-23", episodes = 12), saved.single().third)
    }

    @Test fun rewatchStaysCompleted() {
        entries[7] = Entry("COMPLETED", progress = 12, started = "2026-01-01", completed = "2026-02-01", episodes = 12)
        sync().watching("kitsu:1:1", 1)
        assertTrue(saved.isEmpty())
    }

    @Test fun thresholdFiresOnce() {
        val s = sync()
        s.watchingAt("kitsu:1:3", "series", 100, 1000)
        s.watchingAt("kitsu:1:3", "series", 125, 1000)
        assertEquals(0, asked)
        s.watchingAt("kitsu:1:3", "series", 126, 1000)
        s.watchingAt("kitsu:1:3", "series", 500, 1000)
        assertEquals(1, asked)
        assertEquals("CURRENT", saved.single().third.status)
    }

    @Test fun finishedCountsAndCompletes() {
        entries[7] = Entry("CURRENT", progress = 11, started = "2026-10-01", episodes = 12)
        sync().finished("kitsu:1:5", 5)
        assertTrue(saved.isEmpty())
        sync().finished("kitsu:1:12", 12)
        assertEquals(Entry("COMPLETED", progress = 12, started = "2026-10-01", completed = "2026-11-23", episodes = 12), saved.single().third)
    }

    @Test fun statusAndScore() {
        sync().status("kitsu:1", "COMPLETED")
        assertEquals(Entry("COMPLETED", completed = "2026-11-23", episodes = 12), saved.last().third)
        sync().score("kitsu:1", 8.0)
        assertEquals(8.0, saved.last().third.score, 0.0)
        assertEquals("COMPLETED", saved.last().third.status)
    }

    @Test fun scoreOnUnlistedTitleSendsNothing() {
        sync().score("kitsu:1", 8.0)
        assertTrue(saved.isEmpty())
        sync().score("kitsu:1", Double.NaN)
        sync().score("kitsu:1", -3.0)
        assertEquals(1, asked)
    }

    @Test fun movieAndWholeTitleIds() {
        assertEquals(12, AniListSync.episodeOf("kitsu:5:12", false))
        assertEquals(0, AniListSync.episodeOf("kitsu:12345", false))
        assertEquals(1, AniListSync.episodeOf("kitsu:12345", true))
        assertNull(AniListSync.episodeOf("tt0903747", true))
        sync().episodeDone("kitsu:1", "series")
        assertTrue(saved.isEmpty())
        sync().episodeDone("kitsu:2", "series")
        assertEquals(Entry("COMPLETED", progress = 1, started = "2026-11-23", completed = "2026-11-23", episodes = 1), saved.single().third)
        sync().episodeDone("kitsu:12345", "movie")
        assertEquals(1, saved.size)
    }

    @Test fun progressAboveEpisodeCountIsSkipped() {
        sync().finished("kitsu:1:13", 13)
        sync().watching("kitsu:1:13", 13)
        assertTrue(saved.isEmpty())
    }

    @Test fun unknownEpisodeCountNeverCompletes() {
        sync().finished("kitsu:3:50", 50)
        assertEquals(Entry("CURRENT", progress = 50, started = "2026-11-23"), saved.single().third)
    }

    @Test fun repeatingKeepsFirstFinishDate() {
        val e = Entry("REPEATING", progress = 11, repeat = 1, started = "2026-01-01", completed = "2026-02-01", episodes = 12)
        assertEquals(Entry("COMPLETED", progress = 12, repeat = 2, started = "2026-01-01", completed = "2026-02-01", episodes = 12),
            AniList.watched(e, 12, "2026-11-23"))
    }

    @Test fun partialDatesRoundTrip() {
        val read = AniList.entryOf(JSONObject("{\"status\":\"PLANNING\",\"startedAt\":{\"year\":2019,\"month\":null,\"day\":null},\"completedAt\":{\"year\":2020,\"month\":5,\"day\":null}}"))
        assertEquals("2019", read.started)
        assertEquals("2020-05", read.completed)
        val vars = AniList.saveVars(7, read.copy(status = "CURRENT"), read)
        assertFalse(vars.has("a"))
        assertFalse(vars.has("c"))
        assertTrue(AniList.saveVars(7, read.copy(completed = "2021-01-02"), read).has("c"))
        entries[7] = read.copy(episodes = 12)
        sync().watching("kitsu:1:1", 1)
        assertEquals("2019", saved.single().third.started)
    }

    @Test fun addedKeepsAQueuedStatus() {
        failure = java.io.IOException("offline")
        sync().status("kitsu:1", "DROPPED")
        sync().added("kitsu:1")
        failure = null
        sync().retry()
        assertEquals("DROPPED", saved.single().third.status)
    }

    @Test fun failedWriteIsKeptAndReplayed() {
        failure = java.io.IOException("offline")
        sync().finished("kitsu:1:1", 1)
        assertTrue(saved.isEmpty())
        failure = null
        sync().retry()
        assertEquals(1, saved.single().third.progress)
        sync().retry()
        assertEquals(1, saved.size)
    }

    @Test fun permanentErrorDropsAndTransientKeeps() {
        failure = AniList.Failed(400, "AniList: validation")
        sync().finished("kitsu:1:1", 1)
        failure = null
        sync().retry()
        assertTrue(saved.isEmpty())
        failure = AniList.Failed(503, "AniList: HTTP 503")
        sync().finished("kitsu:1:2", 2)
        failure = null
        sync().retry()
        assertEquals(2, saved.single().third.progress)
    }

    @Test fun rateLimitPausesTheFlush() {
        failure = AniList.Failed(429, "AniList: too many requests", 30)
        sync().finished("kitsu:1:1", 1)
        sync().finished("kitsu:2:1", 1)
        failure = null
        assertEquals(1, asked)
        sync().retry()
        assertEquals(1, asked)
        clock += 31_000
        sync().retry()
        assertEquals(1, saved.filter { it.first == 7 }.size)
    }

    @Test fun staleReplayAfterNewerWebsiteEditIsDropped() {
        failure = java.io.IOException("offline")
        sync().finished("kitsu:1:5", 5)
        failure = null
        entries[7] = Entry("DROPPED", episodes = 12, updatedAt = clock / 1000 + 60)
        clock += 120_000
        sync().retry()
        assertTrue(saved.isEmpty())
    }

    @Test fun itemOlderThanADayIsDropped() {
        failure = java.io.IOException("offline")
        sync().finished("kitsu:1:5", 5)
        failure = null
        clock += 25 * 3600 * 1000L
        sync().retry()
        assertTrue(saved.isEmpty())
        assertEquals(1, asked)
    }

    @Test fun loggedOutClearsTokenAndQueue() {
        failure = java.io.IOException("offline")
        sync().finished("kitsu:1:5", 5)
        assertTrue(prefs.getString("anilist_queue", "")!!.contains("kitsu:1"))
        failure = AniList.LoggedOut()
        sync().retry()
        assertEquals("", prefs.getString("anilist_token", null))
        assertNull(prefs.getString("anilist_queue", null))
    }

    @Test fun logoutClearsQueueAndListCache() {
        failure = java.io.IOException("offline")
        sync().finished("kitsu:1:5", 5)
        prefs.edit().putString("anilist_mine_pedro", "[]").commit()
        Settings(RuntimeEnvironment.getApplication()).logOutAniList()
        assertNull(prefs.getString("anilist_queue", null))
        assertNull(prefs.getString("anilist_mine_pedro", null))
        assertEquals("", prefs.getString("anilist_token", null))
        assertTrue(AniListLists.cached(prefs, "Pedro").isEmpty())
    }

    @Test fun removingFromHomeSendsNothing() = runBlocking {
        val addons = Addons(emptyList(), prefs, sync())
        val title = Found("kitsu:1", "series", "Dandadan", "", "")
        addons.save(title, true)
        saved.clear()
        val before = asked
        addons.save(title, false)
        assertEquals(before, asked)
        assertTrue(saved.isEmpty())
    }

    @Test fun keepFromPcSendsNothing() = runBlocking {
        prefs.edit().putString("alone_ja_kitsu:1", "").putString("alone_ja_kitsu:2", "").commit()
        val e = Episode("kitsu:2:3", "Episode 3", 1, 3, 1400.0, "", 0, 0, 0, 10.0)
        Addons(emptyList(), prefs, sync()).keepFromPc(listOf(Found("kitsu:1", "series", "Kept", "", "")),
            listOf(Show("kitsu:2", "Two", "stream", "", listOf(e), "ja") to e))
        assertEquals(0, asked)
        assertNull(prefs.getString("anilist_queue", null))
    }

    @Test fun noTokenNoCall() {
        prefs.edit().putString("anilist_token", "").commit()
        sync().added("kitsu:1")
        sync().retry()
        assertEquals(0, asked)
    }

    @Test fun skipsUnmappedAndOtherIds() {
        sync().added("kitsu:99")
        sync().added("tt0903747")
        assertEquals(0, asked)
    }

    @Test fun listsParse() {
        val e = JSONObject("{\"status\":\"REPEATING\",\"progress\":3,\"score\":8.5,\"media\":{\"id\":5,\"format\":\"TV\",\"episodes\":12,\"averageScore\":79," +
            "\"genres\":[\"Action\"],\"title\":{\"romaji\":\"Dandadan\",\"native\":\"ダンダダン\"},\"coverImage\":{\"extraLarge\":\"c\"},\"bannerImage\":null,\"startDate\":{\"year\":2024}}}")
        val mine = AniListLists.parse(listOf(e), mapOf(5 to "48")).single()
        assertEquals("48", mine.kitsuId)
        assertEquals("", mine.banner)
        assertEquals(listOf("Action"), mine.genres)
        assertTrue(mine.watching)
        assertTrue(AniListLists.parse(listOf(e), emptyMap()).isEmpty())
    }
}
