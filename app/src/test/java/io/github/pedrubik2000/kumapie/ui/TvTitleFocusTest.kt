package io.github.pedrubik2000.kumapie.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import io.github.pedrubik2000.kumapie.data.AniList
import io.github.pedrubik2000.kumapie.data.AniListLists
import io.github.pedrubik2000.kumapie.data.AniListSync
import io.github.pedrubik2000.kumapie.data.Discovery
import io.github.pedrubik2000.kumapie.data.Found
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.lang.JitenApi
import io.github.pedrubik2000.kumapie.lang.JitenHttp
import io.github.pedrubik2000.kumapie.lang.JitenQueue
import io.github.pedrubik2000.kumapie.lang.JitenStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television-xhdpi")
class TvTitleFocusTest {
    @get:Rule val rule = createComposeRule()

    private val title = Found("kitsu:5", "series", "Alpha", "/p/alpha.jpg", "2020")

    private fun discovery(watched: List<Boolean>) = object : Discovery {
        override suspend fun board() = emptyList<Pair<String, List<Found>>>()
        override suspend fun discover(q: String) = emptyList<Found>()
        override suspend fun discoverMeta(type: String, id: String) = JSONObject().put("episodes", JSONArray(watched.mapIndexed { i, w ->
            JSONObject().put("id", "kitsu:5:${i + 1}").put("number", i + 1).put("watched", w)
        }))
        override suspend fun discoverStreams(type: String, id: String) = JSONArray()
        override suspend fun saved() = emptyList<Found>()
        override suspend fun save(title: Found, on: Boolean) = emptyList<Found>()
        override suspend fun seen(id: String, on: Boolean) = Unit
    }

    private fun logIn(status: String, progress: Int, score: Double = 0.0) {
        val settings = Settings(RuntimeEnvironment.getApplication())
        val mine = JSONObject().put("id", 5).put("kitsu", "5").put("native", "Alpha").put("romaji", "Alpha").put("cover", "").put("banner", "")
            .put("format", "TV").put("episodes", 3).put("status", status).put("progress", progress).put("score", score).put("average", 80)
            .put("genres", JSONArray()).put("year", 2020)
        settings.prefs.edit().putString("anilist_token", "t").putString("anilist_user", "pedro")
            .putString(AniListLists.CACHE + "_pedro", JSONArray(listOf(mine)).toString()).commit()
    }

    private fun open(watched: List<Boolean>) {
        val app = RuntimeEnvironment.getApplication()
        val queue = JitenQueue(JitenStore(File(app.cacheDir, "jiten-title-${System.nanoTime()}.json")), JitenApi(JitenHttp), resolve = { null }, idle = { false })
        val offline = AniListSync.Api(lookup = { null }, entry = { _, _ -> AniList.Entry(null, 0.0, 0, 0, "", "", null) }, save = { _, _, _, after -> after })
        val sync = AniListSync(Settings(app).prefs, api = offline)
        rule.setContent { DiscoverScreen(discovery(watched), start = title, jiten = queue, lists = { null }, sync = sync) }
    }

    private fun focused(): String? = rule.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()?.config?.let { c ->
        c.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text ?: c.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
    }

    private fun press(k: Key) { rule.onRoot().performKeyInput { pressKey(k) }; rule.waitForIdle() }

    private fun waitFocus(want: (String?) -> Boolean) = rule.waitUntil(5_000) { want(focused()) }

    @Test fun downFromPlayLandsOnTheNextUpEpisodeAndUpComesBack() {
        open(listOf(true, false, false))
        waitFocus { it?.startsWith("▶") == true }
        press(Key.DirectionDown)
        waitFocus { it == "Episode 2" }
        press(Key.DirectionUp)
        waitFocus { it?.startsWith("▶") == true }
    }

    @Test fun rightFromTheButtonsReachesTheStatusRow() {
        logIn("CURRENT", 1)
        open(listOf(true, false, false))
        waitFocus { it?.startsWith("▶") == true }
        press(Key.DirectionRight)
        assertEquals("+ Home", focused())
        press(Key.DirectionRight)
        waitFocus { it == "Status" }
        press(Key.DirectionDown)
        assertEquals("Your score", focused())
        press(Key.DirectionDown)
        assertEquals("Episode 2", focused())
    }

    @Test fun withoutALoginTheCardHasNothingToFocus() {
        open(listOf(true, false, false))
        waitFocus { it?.startsWith("▶") == true }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Log in with AniList to see your list here").fetchSemanticsNodes().isNotEmpty() }
        repeat(3) { press(Key.DirectionRight) }
        assertEquals("+ Home", focused())
        assertTrue(rule.onAllNodesWithText("Status").fetchSemanticsNodes().isEmpty())
    }

    @Test fun aFinishedTitleOpensOnYourScore() {
        logIn("COMPLETED", 3)
        open(listOf(true, true, true))
        waitFocus { it == "Your score" }
        rule.onAllNodesWithText("You finished it. Rate it?").fetchSemanticsNodes().let { assertEquals(1, it.size) }
    }
}
