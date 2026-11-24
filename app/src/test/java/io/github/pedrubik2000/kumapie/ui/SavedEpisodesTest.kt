package io.github.pedrubik2000.kumapie.ui

import io.github.pedrubik2000.kumapie.data.Episode
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
}
