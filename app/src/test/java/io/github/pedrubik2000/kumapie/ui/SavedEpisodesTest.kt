package io.github.pedrubik2000.kumapie.ui

import io.github.pedrubik2000.kumapie.data.AniListLists
import io.github.pedrubik2000.kumapie.data.Discovery
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Found
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.goingThatExist
import io.github.pedrubik2000.kumapie.data.savedEnded
import kotlinx.coroutines.runBlocking
import io.github.pedrubik2000.kumapie.data.SavedMeta
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.data.resumeLine
import io.github.pedrubik2000.kumapie.data.savedEpisodeIds
import io.github.pedrubik2000.kumapie.data.streamsThatExist
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The phone's Home (mobile HomeScreen) reads the title page's cached meta the same way: the cards and caption it would print. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SavedEpisodesTest {
    private fun card(n: Int): Pair<Show, Episode> {
        val e = Episode("kitsu:1:$n", "Episode $n", 1, n, 1400.0, "", 0, 0, 0, null)
        return Show("kitsu:1", "Dandadan", "stream", "", listOf(e), "ja") to e
    }

    private fun cache(last: Int) = SavedMeta.put(RuntimeEnvironment.getApplication(), "kitsu:1",
        JSONObject().put("episodes", JSONArray((1..last).map { JSONObject().put("id", "kitsu:1:$it") })))

    @Test fun theCachedMetaOfTheTitlePageDecidesWhichNextEpisodeExists() {
        val app = RuntimeEnvironment.getApplication()
        val going = listOf(card(13))
        assertEquals(listOf("Episode 13"), streamsThatExist(going, emptyList(), savedEpisodeIds(app, going), emptyMap()).map { it.second.title }) // no meta, no count: kept
        cache(24)
        val shown = streamsThatExist(going, emptyList(), savedEpisodeIds(app, going), emptyMap())
        assertEquals(listOf("Episode 13 · Next episode"), shown.map { (_, e) -> e.title + " · " + resumeLine(e) })
        assertEquals(setOf("kitsu:1:13"), savedEpisodeIds(app, going)["kitsu:1"]!!.filter { it.endsWith(":13") }.toSet())
    }

    private fun cacheEnded(last: Int, status: String, year: String = "") = SavedMeta.put(RuntimeEnvironment.getApplication(), "kitsu:1",
        JSONObject().put("status", status).put("year", year).put("episodes", JSONArray((1..last).map { JSONObject().put("id", "kitsu:1:$it") })))

    private fun phoneCards(): List<String> {
        val app = RuntimeEnvironment.getApplication()
        val going = listOf(card(13))
        return streamsThatExist(going, emptyList(), savedEpisodeIds(app, going), emptyMap(), savedEnded(app, going)).map { it.second.id }
    }

    @Test fun anEndedMetaWithoutTheEpisodeDropsTheCardOnThePhone() {
        cacheEnded(12, "Ended")
        assertEquals(emptyList<String>(), phoneCards())
    }

    @Test fun aClosedYearRangeCountsAsEnded() {
        cacheEnded(12, "", "2024-2024")
        assertEquals(emptyList<String>(), phoneCards())
    }

    @Test fun anAiringOrUnknownMetaKeepsTheCard() {
        cacheEnded(12, "", "2024-")
        assertEquals(listOf("kitsu:1:13"), phoneCards())
        cacheEnded(12, "Ongoing")
        assertEquals(listOf("kitsu:1:13"), phoneCards())
    }

    private class Going(val cards: List<Pair<Show, Episode>>) : Discovery {
        override suspend fun board() = emptyList<Pair<String, List<Found>>>()
        override suspend fun discover(q: String) = emptyList<Found>()
        override suspend fun discoverMeta(type: String, id: String) = JSONObject()
        override suspend fun discoverStreams(type: String, id: String) = JSONArray()
        override suspend fun saved() = emptyList<Found>()
        override suspend fun save(title: Found, on: Boolean) = emptyList<Found>()
        override suspend fun seen(id: String, on: Boolean) {}
        override suspend fun going() = cards
    }

    @Test fun theCachedAniListCountDropsTheCardOnThePhone() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val prefs = Settings(app).prefs
        val media = JSONObject().put("id", 5).put("format", "TV").put("episodes", 12).put("averageScore", 70)
            .put("title", JSONObject().put("romaji", "Dandadan").put("native", "ダンダダン")).put("startDate", JSONObject().put("year", 2024))
        val entry = JSONObject().put("status", "CURRENT").put("progress", 12).put("score", 0.0).put("media", media)
        AniListLists.refresh(prefs, "u", "t", ask = {
            JSONObject().put("MediaListCollection", JSONObject().put("hasNextChunk", false).put("lists", JSONArray().put(JSONObject().put("entries", JSONArray().put(entry)))))
        }, kitsu = { _, _ -> mapOf(5 to "1") })
        val shown = mutableListOf<List<String>>()
        goingThatExist(Going(listOf(card(13), card(12))), app, prefs, "u", "") { shown += it.map { c -> c.second.id } }
        assertEquals(listOf(listOf("kitsu:1:12")), shown)
        shown.clear()
        goingThatExist(Going(listOf(card(13))), app, prefs, "", "") { shown += it.map { c -> c.second.id } }
        assertEquals(listOf(listOf("kitsu:1:13")), shown)
    }
}
