package io.github.pedrubik2000.kumapie.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.sun.net.httpserver.HttpServer
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Discovery
import io.github.pedrubik2000.kumapie.data.Found
import io.github.pedrubik2000.kumapie.data.Settings
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress

/** The focused element's first text (a card's title), else its icon's name. */
private fun ComposeContentTestRule.focusedTitle(): String? = onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()?.config?.let { c ->
    c.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text ?: c.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeContentTestRule.press(k: Key) { onRoot().performKeyInput { pressKey(k) }; waitForIdle() }

/** The Show page with the remote: it opens on the episode played last (f66496b, 61e8713), even far down the list. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television-xhdpi") // the Chromecast
class TvShowFocusTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var server: HttpServer
    private var shows = ""

    @Before fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { x ->
            val body = if (x.requestURI.path == "/api/tv/shows") shows.toByteArray() else ByteArray(0)
            x.sendResponseHeaders(if (body.isEmpty()) 404 else 200, if (body.isEmpty()) -1 else body.size.toLong())
            x.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @After fun stopServer() = server.stop(0)

    /** A show of 15 episodes, [resumed] = (episode number, when it was left). */
    private fun open(vararg resumed: Pair<Int, Long>) {
        val left = resumed.toMap()
        shows = """{"shows":[{"id":"s","title":"Show","kind":"anime","poster":"/p/s.jpg","lang":"ja","episodes":[${(1..15).joinToString(",") { n ->
            """{"id":"e$n","title":"Episode $n","season":1,"episode":$n,"duration":1400,"thumb":"/t/$n.jpg","scenes":10,"seen":0,
               "easy":0,"resume":${if (n in left) "300.0" else "null"},"resumed_at":${left[n] ?: 0},"watched":false}"""
        }}]}]}"""
        val api = Api("http://127.0.0.1:${server.address.port}")
        val show = runBlocking { api.shows() }.single()
        rule.setContent { ShowScreen(api, Settings(LocalContext.current), show) { _, _ -> } }
    }

    @Test fun opensOnTheFirstEpisodeWhenNoneWasPlayed() {
        open()
        rule.waitUntil(5_000) { rule.focusedTitle() == "Episode 1" }
    }

    @Test fun opensOnTheEpisodePlayedLastFarDownTheList() {
        open(3 to 100L, 12 to 200L) // 12 is off screen: it must be scrolled to before it can take the focus
        rule.waitUntil(5_000) { rule.focusedTitle() == "Episode 12" }
        rule.press(Key.DirectionDown)
        assertEquals("Episode 13", rule.focusedTitle())
    }
}

/** Discover with the remote: each test is a focus bug once found by hand (9126c08, 67671ec). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television-xhdpi")
class TvDiscoverFocusTest {
    @get:Rule val rule = createComposeRule()

    private fun title(name: String) = Found(name.lowercase(), "series", name, "/p/$name.jpg", "2024")

    private val discovery = object : Discovery {
        override suspend fun board() = listOf("Popular" to listOf(title("Alpha"), title("Beta")), "New" to listOf(title("Delta"), title("Echo")))
        override suspend fun discover(q: String) = listOf(title("Zeta"), title("Eta"))
        override suspend fun discoverMeta(type: String, id: String) = JSONObject()
        override suspend fun discoverStreams(type: String, id: String) = JSONArray()
        override suspend fun saved() = emptyList<Found>()
        override suspend fun save(title: Found, on: Boolean) = emptyList<Found>()
        override suspend fun seen(id: String, on: Boolean) = Unit
    }

    private fun open() {
        rule.setContent { DiscoverScreen(discovery) }
        rule.waitUntil(5_000) { rule.focusedTitle() == "Alpha" }
    }

    @Test fun downGoesToTheNextRow() {
        open()
        rule.press(Key.DirectionRight)
        assertEquals("Beta", rule.focusedTitle())
        rule.press(Key.DirectionDown)
        rule.waitUntil(2_000) { rule.focusedTitle()?.let { it == "Delta" || it == "Echo" } == true }
    }

    @Test fun downFromATitlesPlayGoesToItsNextEpisode() {
        val meta = JSONObject("""{"episodes":[{"id":"alpha:1","number":1,"watched":true},{"id":"alpha:2","number":2}]}""")
        val withEpisodes = object : Discovery by discovery { override suspend fun discoverMeta(type: String, id: String) = meta }
        rule.setContent { DiscoverScreen(withEpisodes, start = title("Alpha")) }
        rule.waitUntil(5_000) { rule.focusedTitle()?.startsWith("▶") == true }
        rule.press(Key.DirectionDown)
        rule.waitUntil(2_000) { rule.focusedTitle() == "Episode 2" } // the round buttons took it (Chromecast, 2026-11-18)
    }

    @Test fun backClearsASearchAndStaysInDiscover() {
        open()
        rule.onNode(hasImeAction(androidx.compose.ui.text.input.ImeAction.Done)).requestFocus() // read-only until OK: no SetText yet
        rule.press(Key.DirectionCenter) // OK starts editing (read-only until then)
        rule.onNode(hasSetTextAction()).performTextInput("zeta")
        rule.onNode(hasSetTextAction()).performImeAction()
        rule.waitUntil(5_000) { rule.focusedTitle() == "Zeta" }
        rule.press(Key.Back) // the first Back left Discover (Chromecast, 2026-11-17)
        rule.waitUntil(5_000) { rule.focusedTitle() == "Alpha" }
    }
}

/** The TV's first run without a PC (bug hunt 2026-11-17: Back quit the app; a failed dictionary download said "Starting…" forever). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television-xhdpi")
class TvOnboardingTest {
    @get:Rule val rule = createComposeRule()

    @Test fun backGoesOneStepBack() {
        lateinit var activity: androidx.activity.ComponentActivity
        rule.setContent {
            activity = LocalContext.current as androidx.activity.ComponentActivity
            TvOnboarding(Settings(LocalContext.current), onDone = {})
        }
        rule.onNodeWithText("Next").requestFocus()
        rule.press(Key.DirectionCenter)
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Step 2 of 4").fetchSemanticsNodes().isNotEmpty() }
        rule.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Step 1 of 4").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(false, activity.isFinishing)
    }

    @Test fun noJimakuKeyNoNextPastThePhoneStep() {
        rule.setContent { TvOnboarding(Settings(LocalContext.current).apply { jimakuKey = "" }, onDone = {}) }
        repeat(3) {
            rule.onNodeWithText("Next").requestFocus()
            rule.press(Key.DirectionCenter)
            rule.waitForIdle()
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Step 3 of 4").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Add your Jimaku key from your phone first: without it, streams get no Japanese lines.").assertExists()
    }

    @Test fun aFailedDictionaryDownloadSaysSo() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        TvMining.fetching = null
        TvMining.failed = "no internet" to System.currentTimeMillis()
        val said = dictionariesState(context, Settings(context))
        TvMining.failed = null
        assertEquals("Couldn't download them (no internet). Trying again…", said)
    }
}
