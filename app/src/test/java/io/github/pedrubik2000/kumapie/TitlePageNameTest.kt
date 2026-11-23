package io.github.pedrubik2000.kumapie

import io.github.pedrubik2000.kumapie.data.Discovery
import io.github.pedrubik2000.kumapie.data.Found
import io.github.pedrubik2000.kumapie.data.TitlePage
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TitlePageNameTest {
    private val meta = JSONObject().put("id", "kitsu:1").put("name", "Sousou no Frieren").put("episodes", JSONArray())

    private val discovery = object : Discovery {
        override suspend fun board() = emptyList<Pair<String, List<Found>>>()
        override suspend fun discover(q: String) = emptyList<Found>()
        override suspend fun discoverMeta(type: String, id: String) = meta
        override suspend fun discoverStreams(type: String, id: String) = JSONArray()
        override suspend fun saved() = emptyList<Found>()
        override suspend fun save(title: Found, on: Boolean) = emptyList<Found>()
        override suspend fun seen(id: String, on: Boolean) = Unit
    }

    @Test fun theJapaneseNameSurvivesTheMetaArriving() = runBlocking {
        val page = TitlePage(discovery, Found("kitsu:1", "series", "Sousou no Frieren", "", "2023", nameJa = "葬送のフリーレン"))
        assertEquals("葬送のフリーレン", page.shown.shownName)
        page.load()
        assertEquals("葬送のフリーレン", page.shown.shownName)
    }

    @Test fun aMarkedEpisodeShowsWatchedBeforeTheReloadAnswers() = runBlocking {
        var shownWhileSaving: Boolean? = null
        lateinit var page: TitlePage
        val episode = JSONObject().put("id", "kitsu:1:1").put("number", 1)
        val withEpisode = JSONObject().put("id", "kitsu:1").put("name", "Sousou no Frieren").put("episodes", JSONArray().put(episode))
        val slow = object : Discovery by discovery {
            override suspend fun discoverMeta(type: String, id: String) = withEpisode
            override suspend fun seen(id: String, on: Boolean) { shownWhileSaving = page.episodes.single().optBoolean("watched") }
        }
        page = TitlePage(slow, Found("kitsu:1", "series", "Sousou no Frieren", "", "2023"))
        page.load()
        page.mark(page.episodes.single())
        assertEquals(true, shownWhileSaving)
    }

    @Test fun aTitleWithoutOneStaysInEnglish() = runBlocking {
        val page = TitlePage(discovery, Found("kitsu:1", "series", "Sousou no Frieren", "", "2023"))
        page.load()
        assertEquals("Sousou no Frieren", page.shown.shownName)
    }
}
