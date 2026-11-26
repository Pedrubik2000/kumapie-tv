package io.github.pedrubik2000.kumapie.ui

import io.github.pedrubik2000.kumapie.data.AniListLists
import io.github.pedrubik2000.kumapie.data.ContinueHidden
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.Show
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContinueHiddenPrefsTest {
    private val prefs get() = Settings(RuntimeEnvironment.getApplication()).prefs
    private val e = Episode("a1", "Episode 1", 1, 1, 1400.0, "", 0, 0, 0, null, resumedAt = 50)
    private val show = Show("kitsu:7", "A", "anime", "", listOf(e), "ja")

    @Test fun hideThenUndoIsKeptOnTheDevice() {
        assertEquals(mapOf("kitsu:7" to 50L), ContinueHidden.hide(prefs, "p", show, e))
        assertEquals(mapOf("kitsu:7" to 50L), ContinueHidden.read(prefs, "p"))
        assertEquals(emptyMap<String, Long>(), ContinueHidden.unhide(prefs, "p", "kitsu:7"))
        assertEquals(emptyMap<String, Long>(), ContinueHidden.read(prefs, "p"))
    }

    @Test fun playingTheShowAgainBringsItBack() {
        ContinueHidden.hide(prefs, "p", show, e)
        val played = e.copy(resumedAt = 60)
        assertEquals(listOf(played), ContinueHidden.shown(listOf(show to played), ContinueHidden.read(prefs, "p")).map { it.second })
        assertEquals(emptyList<Episode>(), ContinueHidden.shown(listOf(show to e), ContinueHidden.read(prefs, "p")).map { it.second })
    }

    @Test fun theNextAiringEpisodeSurvivesTheListsCache() {
        val entry = JSONObject().put("status", "CURRENT").put("progress", 3).put("score", 0).put("media", JSONObject().put("id", 9).put("format", "TV")
            .put("nextAiringEpisode", JSONObject().put("episode", 4).put("airingAt", 1_800_000_000L)))
        val parsed = AniListLists.parse(listOf(entry), mapOf(9 to "99"))
        assertEquals(4 to 1_800_000_000L, parsed.single().let { it.nextEp to it.nextAt })
        prefs.edit().putString(AniListLists.CACHE + "_u", JSONArray(listOf(JSONObject(
            """{"id":9,"kitsu":"99","native":"","romaji":"","cover":"","banner":"","format":"TV","episodes":0,"status":"CURRENT","progress":3,"score":0.0,"average":0,"genres":[],"year":0,"next":4,"nextAt":1800000000}"""))).toString()).commit()
        assertEquals(4 to 1_800_000_000L, AniListLists.cached(prefs, "u").single().let { it.nextEp to it.nextAt })
    }
}
