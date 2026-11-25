package io.github.pedrubik2000.kumapie.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.sun.net.httpserver.HttpServer
import io.github.pedrubik2000.kumapie.data.AniListLists
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.lang.JitenApi
import io.github.pedrubik2000.kumapie.lang.JitenHttp
import io.github.pedrubik2000.kumapie.lang.JitenQueue
import io.github.pedrubik2000.kumapie.lang.JitenStore
import io.github.pedrubik2000.kumapie.lang.TitleDifficulty
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television-xhdpi")
class TvHomeFocusTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var server: HttpServer

    @Before fun startServer() {
        takeRailLast()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { x ->
            val body = if (x.requestURI.path == "/api/tv/shows") showsBody.toByteArray() else ByteArray(0)
            x.sendResponseHeaders(if (body.isEmpty()) 404 else 200, if (body.isEmpty()) -1 else body.size.toLong())
            x.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @After fun stopServer() = server.stop(0)

    private var showsBody = SHOWS
    private var discoverCalls = 0
    private var home by mutableStateOf(true)
    private var screen by mutableStateOf("home")
    private var tick by mutableStateOf(0)

    private fun openHome(queue: JitenQueue? = null) {
        rule.setContent {
            val settings = Settings(LocalContext.current)
            if (screen == "library") LibraryScreen(Api("http://127.0.0.1:${server.address.port}"), settings, onHome = { screen = "home" }, onDiscover = {}, onReview = {}, onSettings = {}, onOpen = {})
            else if (home) HomeScreen(Api("http://127.0.0.1:${server.address.port}"), settings, "p", "Pedro",
                onShow = {}, onPlay = { _, _ -> }, onSettings = {}, onStats = { home = false }, onProfiles = {},
                onLibrary = { screen = "library" }, onDiscover = { discoverCalls++ }, jiten = queue, lists = { null })
        }
        rule.waitUntil(5_000) { focused() == RESUME }
    }

    private fun focused(): String? = rule.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()?.config?.let { c ->
        c.getOrNull(SemanticsProperties.Text)?.joinToString(" ")?.takeIf { it.isNotBlank() }
            ?: c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
    }

    private fun press(vararg keys: Long) = keys.map(::Key).forEach { k -> rule.onRoot().performKeyInput { pressKey(k) }; rule.waitForIdle() }

    private fun logIn(vararg mine: JSONObject) {
        val settings = Settings(RuntimeEnvironment.getApplication())
        settings.prefs.edit().putString("anilist_token", "t").putString("anilist_user", "pedro")
            .putString(AniListLists.CACHE + "_pedro", JSONArray(mine.toList()).toString()).commit()
        val covers = RuntimeEnvironment.getApplication().getSharedPreferences("backdrops_kitsu", 0).edit()
        mine.forEach { covers.putString("kitsu:" + it.getString("kitsu"), "/b/${it.getString("kitsu")}.jpg") }
        covers.commit()
    }

    private fun queueWith(vararg values: Pair<String, TitleDifficulty>): JitenQueue {
        val store = JitenStore(File(RuntimeEnvironment.getApplication().cacheDir, "jiten-test-${System.nanoTime()}.json"))
        values.forEachIndexed { i, (kitsu, d) -> store.linkKitsu(kitsu, 100 + i); store.put(100 + i, d) }
        return JitenQueue(store, JitenApi(JitenHttp), resolve = { null }, idle = { false })
    }

    private fun waitForCard(title: String) = rule.waitUntil(5_000) { rule.onAllNodesWithContentDescription(title).fetchSemanticsNodes().isNotEmpty() }

    private val memory = HomeMemory()

    private fun openReturning() {
        rule.setContent {
            val settings = Settings(LocalContext.current)
            val t = tick
            if (home) HomeScreen(Api("http://127.0.0.1:${server.address.port}"), settings, "p", "Pedro", onShow = {}, onPlay = { _, _ -> home = false },
                onSettings = {}, onStats = {}, onProfiles = {}, onLibrary = {}, onDiscover = {}, onDiscovered = { home = false },
                lists = { if (t < 0) emptyList() else null }, memory = memory)
        }
        rule.waitUntil(5_000) { focused() == RESUME }
    }

    private fun openAndComeBack(vararg to: Long, card: String) {
        press(*to)
        assertEquals(card, focused())
        press(OK)
        assertEquals(false, home)
        home = true
        rule.waitUntil(5_000) { focused() == card }
    }

    @Test fun backFromAContinueWatchingCardsTitlePageFocusesThatCard() {
        openReturning()
        openAndComeBack(DOWN, RIGHT, card = "Beta")
        rule.onNodeWithText("CONTINUE WATCHING · 2 OF 2").assertExists()
    }

    @Test fun theCardStaysFocusedWhenHomeRefreshesWithEqualContentAfterTheReturn() {
        openReturning()
        openAndComeBack(DOWN, RIGHT, card = "Beta")
        tick++
        rule.waitForIdle()
        Thread.sleep(500)
        rule.waitForIdle()
        assertEquals("Beta", focused())
    }

    @Test fun backFromAListCardsTitlePageFocusesThatCard() {
        logIn(mine("1", "Frieren", "CURRENT", progress = 11, episodes = 28), mine("2", "Dandadan", "PLANNING", progress = 0, episodes = 12))
        openReturning()
        waitForCard("Frieren")
        openAndComeBack(DOWN, DOWN, card = "Frieren")
    }

    @Test fun backFromTheHeroResumeButtonStillFocusesResume() {
        openReturning()
        press(OK)
        assertEquals(false, home)
        home = true
        rule.waitUntil(5_000) { focused() == RESUME }
    }

    @Test fun resumeIsFocusedWhenHomeOpens() = openHome()

    @Test fun downFromResumeGoesToTheRowUnderIt() {
        openHome()
        press(DOWN)
        assertEquals("Alpha", focused())
    }

    @Test fun downFromResumeShowsThatTitleInTheHeroWithoutButtons() {
        openHome()
        press(DOWN)
        rule.onNodeWithText("▶  Resume").assertDoesNotExist()
        rule.onNodeWithText("Episodes").assertDoesNotExist()
        rule.onNodeWithText("CONTINUE WATCHING · 1 OF 2").assertExists()
        press(RIGHT)
        assertEquals("Beta", focused())
        rule.onNodeWithText("CONTINUE WATCHING · 2 OF 2").assertExists()
    }

    @Test fun upFromTheFirstRowReturnsToResumeAndTheButtonsComeBack() {
        openHome()
        press(DOWN, RIGHT, UP)
        assertEquals(RESUME, focused())
        rule.onNodeWithText("Episodes").assertExists()
        rule.onNodeWithText("CONTINUE WATCHING").assertExists()
    }

    @Test fun downAgainLandsInWatchingOnAniListWithTheFactChips() {
        logIn(mine("1", "Frieren", "CURRENT", progress = 11, episodes = 28, average = 90), mine("2", "Dandadan", "PLANNING", progress = 0, episodes = 12))
        openHome()
        waitForCard("Frieren")
        press(DOWN, DOWN)
        assertEquals("Frieren", focused())
        rule.onNodeWithText("WATCHING ON ANILIST").assertExists()
        rule.onNodeWithText("On AniList: Watching").assertExists()
        assert(rule.onAllNodesWithText("Ep 11 of 28").fetchSemanticsNodes().size >= 2)
        rule.onNodeWithText("AniList 90").assertExists()
        rule.onNodeWithText("▶  Resume").assertDoesNotExist()
        press(DOWN)
        assertEquals("Dandadan", focused())
        rule.onNodeWithText("PLANNING ON ANILIST · NOT STARTED").assertExists()
    }

    @Test fun aCardWithADifficultyShowsItsPercentAndOneWithoutShowsNone() {
        logIn(mine("1", "Frieren", "CURRENT", progress = 11, episodes = 28, average = 90), mine("2", "Dandadan", "CURRENT", progress = 3, episodes = 12))
        openHome(queueWith("1" to TitleDifficulty(2.7, 9, null, null)))
        waitForCard("Frieren")
        press(DOWN, DOWN)
        rule.onAllNodesWithText("54%").fetchSemanticsNodes().let { assertEquals(1, it.size) }
        val tags = rule.onAllNodes(SemanticsMatcher("a percent tag") { n ->
            n.config.getOrNull(SemanticsProperties.Text)?.any { Regex("""\d+%""").matches(it.text) } == true
        }).fetchSemanticsNodes()
        assertEquals(1, tags.size)
    }

    @Test fun theFocusedRowsHeadingSitsBelowTheHero() {
        logIn(mine("1", "Frieren", "CURRENT", progress = 11, episodes = 28), mine("2", "Dandadan", "PLANNING", progress = 0, episodes = 12))
        openHome()
        waitForCard("Frieren")
        press(DOWN, DOWN)
        rule.waitForIdle()
        val heroBottom = 250 * 2f
        rule.onNodeWithText("Watching on AniList").fetchSemanticsNode().boundsInRoot.let { assert(it.top >= heroBottom) { "heading at ${it.top}" } }
        press(DOWN)
        rule.waitForIdle()
        rule.onNodeWithText("Planning on AniList").fetchSemanticsNode().boundsInRoot.let { assert(it.top >= heroBottom) { "heading at ${it.top}" } }
    }

    @Test fun withNothingInProgressTheFirstFocusIsFindSomethingToWatchAndOkOpensDiscover() {
        showsBody = """{"shows":[${show("c", "Gamma", "shows", episode("c1", null, 0))}]}"""
        rule.setContent {
            HomeScreen(Api("http://127.0.0.1:${server.address.port}"), Settings(LocalContext.current), "p", "Pedro", onShow = {}, onPlay = { _, _ -> },
                onSettings = {}, onStats = {}, onProfiles = {}, onDiscover = { discoverCalls++ }, lists = { null })
        }
        rule.waitUntil(5_000) { focused() == "Find something to watch" }
        press(OK)
        assertEquals(1, discoverCalls)
    }

    @Test fun downOnTheLastContentRowKeepsTheFocusWhereItIsAndNeverEntersTheRail() {
        openHome()
        repeat(8) { press(Key.DirectionDown.keyCode) }
        val at = focused()
        press(Key.DirectionDown.keyCode)
        assertEquals(at, focused())
        assert(at !in setOf("Home", "Library", "Review", "Stats", "Pedro", "Settings")) { "$at" }
    }

    @Test fun upOnTheHeroKeepsTheFocusOutOfTheRail() {
        openHome()
        press(Key.DirectionUp.keyCode)
        assertEquals(RESUME, focused())
    }

    private fun loginTop() = rule.onNodeWithText("Log in with AniList to see your lists here").fetchSemanticsNode().boundsInRoot.top

    @Test fun theLoginRowAndTheFirstLibraryPosterEachComeBackFromTheRailAndTheLayoutStays() {
        openHome()
        press(DOWN, DOWN)
        assertEquals("Log in", focused())
        val top = loginTop()
        press(Key.DirectionLeft.keyCode)
        press(RIGHT)
        assertEquals("Log in", focused())
        assertEquals(top, loginTop(), 0.5f)
        press(DOWN)
        var first = focused()
        assert(first != "Log in" && first !in RAIL_LABELS) { "$first" }
        repeat(6) {
            press(Key.DirectionLeft.keyCode)
            val now = focused()
            if (now in RAIL_LABELS) return@repeat
            first = now
        }
        assert(focused() in RAIL_LABELS) { "${focused()}" }
        press(RIGHT)
        assertEquals(first, focused())
    }

    private fun openSwappableHome(until: String = RESUME) {
        rule.setContent {
            val settings = Settings(LocalContext.current)
            val t = tick
            HomeScreen(Api("http://127.0.0.1:${server.address.port}"), settings, "p", "Pedro", onShow = {}, onPlay = { _, _ -> }, onSettings = {},
                onStats = {}, onProfiles = {}, onLibrary = {}, onDiscover = {}, lists = { if (t < 0) emptyList() else null })
        }
        rule.waitUntil(5_000) { focused() == until }
    }

    private fun swapTo(user: String, vararg mine: JSONObject) {
        Settings(RuntimeEnvironment.getApplication()).prefs.edit().putString("anilist_user", user)
            .putString(AniListLists.CACHE + "_" + user, JSONArray(mine.toList()).toString()).commit()
        tick++
    }

    private fun hasCard(title: String) = rule.onAllNodesWithContentDescription(title).fetchSemanticsNodes().isNotEmpty()

    @Test fun aRememberedRowThatDisappearsLeavesRightOnTheHeroButton() {
        logIn(mine("1", "Frieren", "CURRENT", progress = 11, episodes = 28), mine("2", "Dandadan", "PLANNING", progress = 0, episodes = 12))
        openSwappableHome()
        waitForCard("Frieren")
        press(DOWN, DOWN)
        assertEquals("Frieren", focused())
        press(Key.DirectionLeft.keyCode)
        assert(focused() in RAIL_LABELS) { "${focused()}" }
        swapTo("other", mine("2", "Dandadan", "PLANNING", progress = 0, episodes = 12))
        rule.waitUntil(5_000) { !hasCard("Frieren") }
        press(RIGHT)
        rule.waitUntil(5_000) { focused() == RESUME }
        rule.waitUntil(5_000) { hasCard("Alpha") }
        assert(rule.onNodeWithText("Continue watching").fetchSemanticsNode().boundsInRoot.top >= 500f)
    }

    @Test fun withNoContinueRowAnEmptiedHomeLeavesRightOnFindSomethingToWatch() {
        showsBody = """{"shows":[${show("c", "Gamma", "shows", episode("c1", null, 0))}]}"""
        logIn(mine("1", "Frieren", "CURRENT", progress = 11, episodes = 28))
        openSwappableHome("Frieren")
        press(Key.DirectionLeft.keyCode)
        assert(focused() in RAIL_LABELS) { "${focused()}" }
        swapTo("other")
        rule.waitUntil(5_000) { !hasCard("Frieren") }
        press(RIGHT)
        rule.waitUntil(5_000) { focused() == "Find something to watch" }
    }

    @Test fun rightOnTheHeaderLanguageButtonDoesNotJumpToTheRememberedRow() {
        showsBody = """{"shows":[${listOf(
            show("a", "Alpha", "anime", episode("a1", 300.0, 200)),
            show("e", "Epsilon", "shows", episode("e1", null, 0)).replace("\"lang\":\"ja\"", "\"lang\":\"en\""),
        ).joinToString(",")}]}"""
        openHome()
        press(DOWN)
        assertEquals("Alpha", focused())
        rule.onNode(androidx.compose.ui.test.hasText("▾", substring = true)).requestFocus()
        rule.waitForIdle()
        val at = focused()
        assert(at != null && at.contains("▾")) { "$at" }
        press(RIGHT)
        assertEquals(at, focused())
    }

    @Test fun aRowAboveTheRememberedOneDisappearingStillReturnsToTheSameRow() {
        logIn(mine("1", "Frieren", "CURRENT", progress = 11, episodes = 28), mine("2", "Dandadan", "PLANNING", progress = 0, episodes = 12),
            mine("3", "Kaiju", "PLANNING", progress = 0, episodes = 12))
        openSwappableHome()
        waitForCard("Frieren")
        press(DOWN, DOWN, DOWN)
        val planned = focused()
        assert(planned == "Dandadan" || planned == "Kaiju") { "$planned" }
        press(Key.DirectionLeft.keyCode)
        assert(focused() in RAIL_LABELS) { "${focused()}" }
        swapTo("other", mine("2", "Dandadan", "PLANNING", progress = 0, episodes = 12), mine("3", "Kaiju", "PLANNING", progress = 0, episodes = 12))
        rule.waitUntil(5_000) { !hasCard("Frieren") }
        press(RIGHT)
        assertEquals(planned, focused())
    }

    @Test fun leftFromTheSecondRowThenRightLandsOnThatRowsCard() {
        openHome()
        press(DOWN)
        assertEquals("Alpha", focused())
        press(Key.DirectionLeft.keyCode)
        assertEquals("Home", focused())
        press(RIGHT)
        assertEquals("Alpha", focused())
    }

    @Test fun aFocusedRailItemPutsItsNameInTheHintLineAndTheContentGivesItsHintBack() {
        openHome()
        rule.onNodeWithContentDescription("Stats").requestFocus()
        rule.waitForIdle()
        assertEquals("OK Stats", Hints.line())
        rule.onNodeWithContentDescription("Home").requestFocus()
        rule.waitForIdle()
        assertEquals("Home", Hints.line())
        press(RIGHT)
        assertEquals("OK Choose · ▼ Rows", Hints.line())
    }

    @Test fun theHintLineFollowsTheFocusFromTheHeroToACover() {
        openHome()
        assertEquals("OK Choose · ▼ Rows", Hints.line())
        press(DOWN)
        assertEquals("OK Open", Hints.line())
    }

    private fun openEmptyHome() {
        showsBody = """{"shows":[${show("c", "Gamma", "shows", episode("c1", null, 0))}]}"""
        rule.setContent {
            HomeScreen(Api("http://127.0.0.1:${server.address.port}"), Settings(LocalContext.current), "p", "Pedro", onShow = {}, onPlay = { _, _ -> },
                onSettings = {}, onStats = {}, onProfiles = {}, onDiscover = { discoverCalls++ }, lists = { null })
        }
        rule.waitUntil(5_000) { focused() == "Find something to watch" }
    }

    @Test fun downFromFindSomethingToWatchReachesTheLoginButtonWhenLoggedOut() {
        openEmptyHome()
        press(DOWN)
        rule.waitUntil(5_000) { focused() == "Log in" }
    }

    @Test fun downFromFindSomethingToWatchReachesTheRowBelowWhenLoggedIn() {
        Settings(RuntimeEnvironment.getApplication()).prefs.edit().putString("anilist_token", "t").putString("anilist_user", "pedro").commit()
        openEmptyHome()
        press(DOWN)
        rule.waitUntil(5_000) { focused().let { it != null && it != "Find something to watch" } }
    }

    @Test fun theLoginLineSitsBelowTheHeroWhenFocused() {
        openHome()
        press(DOWN, DOWN)
        rule.waitForIdle()
        assertEquals("Log in", focused())
        rule.onNodeWithText("Log in with AniList to see your lists here").fetchSemanticsNode().boundsInRoot.let { assert(it.top >= 250 * 2f) { "line at ${it.top}" } }
    }

    @Test fun withoutATokenTheLoginLineTakesTheAniListRows() {
        openHome()
        rule.onNodeWithText("Log in with AniList to see your lists here").assertExists()
        rule.onNodeWithText("Log in").assertExists()
        rule.onAllNodesWithText("Watching on AniList").fetchSemanticsNodes().let { assertEquals(0, it.size) }
        rule.onAllNodesWithText("Planning on AniList").fetchSemanticsNodes().let { assertEquals(0, it.size) }
    }

    @Test fun theLibraryRoundButtonOpensItsScreen() {
        openHome()
        rule.onNodeWithContentDescription("Library").requestFocus()
        press(OK)
        rule.waitUntil(5_000) { screen == "library" }
        rule.onNodeWithText("Log in with AniList to see your lists here").assertExists()
        rule.waitUntil(5_000) { focused() == "Log in" }
    }

    @Test fun backInTheRowsGoesUpToResume() {
        openHome()
        press(DOWN, DOWN, RIGHT)
        assert(focused() != RESUME)
        press(BACK)
        rule.waitUntil(2_000) { focused() == RESUME }
    }

    @Test fun rightFromARoundButtonGoesToThePage() {
        openHome()
        rule.onNodeWithContentDescription("Stats").requestFocus()
        rule.waitForIdle()
        assertEquals("Stats", focused())
        press(RIGHT)
        assertEquals(RESUME, focused())
    }

    @Test fun leftFromTheContentLandsOnTheHomeItemOfTheRail() {
        openHome()
        press(Key.DirectionLeft.keyCode)
        assertEquals("Home", focused())
    }

    @Test fun backFromStatsFocusesItsRoundButton() {
        openHome()
        rule.onNodeWithContentDescription("Stats").requestFocus()
        press(OK)
        assertEquals(false, home)
        home = true
        runCatching { rule.waitUntil(5_000) { focused() == "Stats" } }
        assertEquals("Stats", focused())
    }

    private companion object {
        const val RESUME = "▶  Resume"
        val RAIL_LABELS = setOf("Home", "Library", "Review", "Stats", "Pedro", "Settings")
        val DOWN = Key.DirectionDown.keyCode; val UP = Key.DirectionUp.keyCode; val RIGHT = Key.DirectionRight.keyCode
        val BACK = Key.Back.keyCode; val OK = Key.DirectionCenter.keyCode

        fun mine(id: String, name: String, status: String, progress: Int, episodes: Int, average: Int = 0) = JSONObject()
            .put("id", id.toInt()).put("kitsu", id).put("native", name).put("romaji", name).put("cover", "/c/$id.jpg").put("banner", "")
            .put("format", "TV").put("episodes", episodes).put("status", status).put("progress", progress).put("score", 0.0)
            .put("average", average).put("genres", JSONArray(listOf("Adventure"))).put("year", 2023)

        fun episode(id: String, resume: Double?, at: Long) =
            """{"id":"$id","title":"Episode 1","season":1,"episode":1,"duration":1400,"thumb":"/t/$id.jpg","scenes":10,
               "seen":2,"easy":80,"resume":${resume ?: "null"},"resumed_at":$at,"watched":false}"""

        fun show(id: String, title: String, kind: String, vararg episodes: String) =
            """{"id":"$id","title":"$title","kind":"$kind","poster":"/p/$id.jpg","lang":"ja","episodes":[${episodes.joinToString(",")}]}"""

        val SHOWS = """{"shows":[${listOf(
            show("a", "Alpha", "anime", episode("a1", 300.0, 200)),
            show("b", "Beta", "anime", episode("b1", 500.0, 100)),
            show("c", "Gamma", "shows", episode("c1", null, 0)),
        ).joinToString(",")}]}"""
    }
}
