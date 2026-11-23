package io.github.pedrubik2000.kumapie

import io.github.pedrubik2000.kumapie.data.AniList.Entry
import io.github.pedrubik2000.kumapie.data.AniListLists
import io.github.pedrubik2000.kumapie.data.AniListSync
import io.github.pedrubik2000.kumapie.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
    private var stored = Entry(episodes = 12)
    private val saved = mutableListOf<Entry>()
    private var offline = false
    private var asked = 0

    private val api = AniListSync.Api({ if (it == "kitsu:99") null else 7 }, { _, _ -> asked++; if (offline) error("offline"); stored },
        { _, _, e -> saved += e; stored = e; e })

    private fun sync() = AniListSync(prefs, api, CoroutineScope(Dispatchers.Unconfined)) { "2026-11-23" }

    @Before fun login() { prefs.edit().clear().putString("anilist_token", "t").commit() }

    @Test fun addedPlansOnlyWhenOnNoList() {
        sync().added("kitsu:1")
        assertEquals(listOf(Entry("PLANNING", episodes = 12)), saved)
        sync().added("kitsu:1")
        stored = Entry("DROPPED", episodes = 12)
        sync().added("kitsu:1")
        assertEquals(1, saved.size)
    }

    @Test fun watchingStartsIt() {
        stored = Entry("PAUSED", episodes = 12)
        sync().watching("kitsu:1:1", 1)
        assertEquals(Entry("CURRENT", started = "2026-11-23", episodes = 12), saved.single())
    }

    @Test fun rewatchStaysCompleted() {
        stored = Entry("COMPLETED", progress = 12, started = "2026-01-01", completed = "2026-02-01", episodes = 12)
        sync().watching("kitsu:1:1", 1)
        assertTrue(saved.isEmpty())
    }

    @Test fun thresholdFiresOnce() {
        val s = sync()
        s.watchingAt("kitsu:1:3", 100, 1000)
        s.watchingAt("kitsu:1:3", 125, 1000)
        assertEquals(0, asked)
        s.watchingAt("kitsu:1:3", 126, 1000)
        s.watchingAt("kitsu:1:3", 500, 1000)
        assertEquals(1, asked)
        assertEquals("CURRENT", saved.single().status)
    }

    @Test fun finishedCountsAndCompletes() {
        stored = Entry("CURRENT", progress = 11, started = "2026-10-01", episodes = 12)
        sync().finished("kitsu:1:5", 5)
        assertTrue(saved.isEmpty())
        sync().finished("kitsu:1:12", 12)
        assertEquals(Entry("COMPLETED", progress = 12, started = "2026-10-01", completed = "2026-11-23", episodes = 12), saved.single())
    }

    @Test fun statusAndScore() {
        sync().status("kitsu:1", "COMPLETED")
        assertEquals(Entry("COMPLETED", completed = "2026-11-23", episodes = 12), saved.last())
        sync().score("kitsu:1", 8.0)
        assertEquals(8.0, saved.last().score, 0.0)
        assertEquals("COMPLETED", saved.last().status)
    }

    @Test fun failedWriteIsKeptAndReplayed() {
        offline = true
        sync().finished("kitsu:1:1", 1)
        assertTrue(saved.isEmpty())
        offline = false
        sync().retry()
        assertEquals(1, saved.single().progress)
        sync().retry()
        assertEquals(1, saved.size)
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

    @Test fun listsRoundTrip() {
        val e = JSONObject("""{"status":"CURRENT","progress":3,"score":8.5,"media":{"id":5,"format":"TV","episodes":12,"averageScore":79,
            "genres":["Action"],"title":{"romaji":"Dandadan","native":"ダンダダン"},"coverImage":{"extraLarge":"c"},"bannerImage":null,"startDate":{"year":2024}}}""")
        val mine = AniListLists.parse(listOf(e), mapOf(5 to "48")).single()
        assertEquals("48", mine.kitsuId)
        assertEquals("", mine.banner)
        assertEquals(listOf("Action"), mine.genres)
        assertTrue(AniListLists.parse(listOf(e), emptyMap()).isEmpty())
    }
}
