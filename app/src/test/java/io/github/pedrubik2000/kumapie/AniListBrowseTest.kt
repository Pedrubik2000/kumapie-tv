package io.github.pedrubik2000.kumapie

import io.github.pedrubik2000.kumapie.data.AniList
import io.github.pedrubik2000.kumapie.data.AniListBrowse
import io.github.pedrubik2000.kumapie.data.Settings
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AniListBrowseTest {
    private val prefs = Settings(RuntimeEnvironment.getApplication()).prefs

    private fun media(id: Int) = JSONObject("{\"id\":$id,\"format\":\"TV\",\"episodes\":12,\"status\":\"FINISHED\",\"season\":\"FALL\",\"averageScore\":80,\"genres\":[\"Mystery\"]," +
        "\"tags\":[{\"name\":\"Detective\",\"rank\":70,\"isMediaSpoiler\":false}],\"title\":{\"romaji\":\"r$id\",\"native\":\"n$id\"},\"coverImage\":{\"extraLarge\":\"c\"},\"startDate\":{\"year\":2012}}")

    private fun answer(total: Int, next: Boolean, vararg ids: Int) = JSONObject().put("Page", JSONObject()
        .put("pageInfo", JSONObject().put("total", total).put("hasNextPage", next)).put("media", JSONArray(ids.map(::media))))

    @Test fun onlyWhatWasSetGoesInTheVariablesAndMyListOnlyWhenLoggedIn() {
        val q = AniListBrowse.Query(search = " psycho ", sort = listOf("SEARCH_MATCH", "SCORE_DESC"), genreIn = setOf("Mystery"), genreNot = setOf("Ecchi"),
            tagNot = setOf("Gore"), formats = setOf("TV"), status = "FINISHED", episodesGreater = 13, startLesser = 20200101, onList = false)
        val v = AniListBrowse.variables(q, 2, loggedIn = true)
        assertEquals(2, v.getInt("p"))
        assertEquals("psycho", v.getString("search"))
        assertEquals("[\"SEARCH_MATCH\",\"SCORE_DESC\"]", v.getJSONArray("sort").toString())
        assertEquals("[\"Mystery\"]", v.getJSONArray("gi").toString())
        assertEquals("[\"Ecchi\"]", v.getJSONArray("gn").toString())
        assertEquals("[\"Gore\"]", v.getJSONArray("tn").toString())
        assertEquals("FINISHED", v.getString("st"))
        assertEquals(13, v.getInt("eg"))
        assertEquals(20200101, v.getInt("sl"))
        assertEquals(false, v.getBoolean("ol"))
        assertFalse(v.has("ti") || v.has("el") || v.has("sg") || v.has("ids"))
        assertFalse(AniListBrowse.variables(q, 1, loggedIn = false).has("ol"))
        assertFalse(AniListBrowse.variables(AniListBrowse.Query(), 1, loggedIn = false).has("search"))
    }

    @Test fun aPageKeepsTheTitlesKitsuKnowsAndItsTotalAndNext() = runBlocking {
        val page = AniListBrowse.page(AniListBrowse.Query(), 1, "t", prefs, { _, _ -> answer(312, true, 1, 2, 3) }, { ids, _ -> ids.filter { it != 2 }.associateWith { "k$it" } })
        assertEquals(listOf(1, 3), page.items.map { it.anilistId })
        assertEquals(listOf("k1", "k3"), page.items.map { it.kitsuId })
        assertEquals(listOf("Detective"), page.items.first().tags)
        assertEquals(312, page.total)
        assertTrue(page.hasNext)
    }

    @Test fun aStaleTokenIsRetriedWithoutOne() = runBlocking {
        val seen = ArrayList<String>()
        val page = AniListBrowse.page(AniListBrowse.Query(onList = false), 1, "old", prefs, { t, v ->
            seen += t + ":" + v.has("ol")
            if (t.isNotEmpty()) throw AniList.LoggedOut() else answer(1, false, 7)
        }, { ids, _ -> ids.associateWith { "k$it" } })
        assertEquals(listOf("old:true", ":false"), seen)
        assertTrue(page.expired)
        assertEquals(1, page.items.size)
        assertFalse(page.hasNext)
    }
}
