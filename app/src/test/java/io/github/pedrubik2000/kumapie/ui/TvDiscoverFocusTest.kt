package io.github.pedrubik2000.kumapie.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import io.github.pedrubik2000.kumapie.data.AniListLists
import io.github.pedrubik2000.kumapie.data.Discovery
import io.github.pedrubik2000.kumapie.data.Found
import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.lang.JitenApi
import io.github.pedrubik2000.kumapie.lang.JitenHttp
import io.github.pedrubik2000.kumapie.lang.JitenQueue
import io.github.pedrubik2000.kumapie.lang.JitenStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
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
class TvDiscoverFocusTest {
    @get:Rule val rule = createComposeRule()

    private val saves = ArrayList<Found>()
    private var showing by mutableStateOf(true)
    private var home = 0
    private var lineOpened = 0
    private var boards = 0
    private var searches = 0
    private var searchDelay = 0L
    private var savedDelay = 0L
    private var saveDelay = 0L
    private var onHomeList = emptyList<Found>()
    private var fresh: ((List<Mine>) -> Unit)? = null

    @Before fun forget() {
        LibraryMemory.forget()
    }

    private fun title(id: Int, name: String) = Found("kitsu:$id", "series", name, "/p/$id.jpg", "2024")

    private val discovery = object : Discovery {
        override suspend fun board(): List<Pair<String, List<Found>>> { boards++; return listOf("Popular" to listOf(title(11, "Alpha"), title(12, "Beta")), "New" to listOf(title(13, "Delta"), title(14, "Echo"))) }
        override suspend fun discover(q: String): List<Found> {
            searches++
            kotlinx.coroutines.delay(searchDelay)
            if (q == "boom") error("search failed")
            return listOf(title(15, "Zeta"), title(16, "Eta"))
        }
        override suspend fun discoverMeta(type: String, id: String) = JSONObject()
        override suspend fun discoverStreams(type: String, id: String) = JSONArray()
        override suspend fun saved(): List<Found> { kotlinx.coroutines.delay(savedDelay); return onHomeList }
        override suspend fun save(title: Found, on: Boolean): List<Found> { kotlinx.coroutines.delay(saveDelay); saves += title; return emptyList() }
        override suspend fun seen(id: String, on: Boolean) = Unit
    }

    private fun mine(id: Int, name: String, status: String, average: Int, episodes: Int = 12) = JSONObject()
        .put("id", id).put("kitsu", "$id").put("native", name).put("romaji", name).put("cover", "/c/$id.jpg").put("banner", "")
        .put("format", "TV").put("episodes", episodes).put("status", status).put("progress", 0).put("score", 0.0)
        .put("average", average).put("genres", JSONArray(listOf("Adventure"))).put("year", 2023)

    private val planning = listOf(
        mine(1, "Frieren", "CURRENT", 95), mine(3, "Dandadan", "PLANNING", 90), mine(4, "Kaiju", "PLANNING", 85), mine(5, "Lupin", "PLANNING", 80),
        mine(6, "Bocchi", "PLANNING", 78), mine(7, "Kuma", "PLANNING", 70), mine(8, "Nana", "PLANNING", 60), mine(9, "Oshi", "PLANNING", 55),
        mine(10, "Mob", "PLANNING", 50),
    )

    private fun logIn(mine: List<JSONObject> = planning) {
        Settings(RuntimeEnvironment.getApplication()).prefs.edit().putString("anilist_token", "t").putString("anilist_user", "pedro")
            .putString(AniListLists.CACHE + "_pedro", JSONArray(mine).toString()).commit()
    }

    private fun seedPictures() {
        val covers = RuntimeEnvironment.getApplication().getSharedPreferences("backdrops_kitsu", 0).edit()
        (1..30).forEach { covers.putString("kitsu:$it", "/b/$it.jpg") }
        covers.commit()
    }

    private fun queue(): JitenQueue {
        val store = JitenStore(File(RuntimeEnvironment.getApplication().cacheDir, "jiten-discover-${System.nanoTime()}.json"))
        return JitenQueue(store, JitenApi(JitenHttp), resolve = { null }, idle = { false })
    }

    private fun open(discoverApi: Discovery = discovery, start: Found? = null) {
        seedPictures()
        val settings = Settings(RuntimeEnvironment.getApplication())
        val jiten = queue()
        rule.setContent {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.onBackKey { home++ }) {
                if (showing) DiscoverScreen(discoverApi, settings, start = start, jiten = jiten, read = { prefs, user, _, _, f -> fresh = f; AniListLists.cached(prefs, user) })
            }
        }
    }

    private fun focused(): String? = rule.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()?.config?.let { c ->
        c.getOrNull(SemanticsProperties.Text)?.joinToString(" ")?.takeIf { it.isNotBlank() }
            ?: c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
    }

    private fun press(vararg keys: Long) = keys.map(::Key).forEach { k -> rule.onRoot().performKeyInput { pressKey(k) }; rule.waitForIdle() }

    private fun waitFocus(want: (String?) -> Boolean) = rule.waitUntil(5_000) { want(focused()) }

    private fun hasText(t: String) = rule.onAllNodesWithText(t).fetchSemanticsNodes().isNotEmpty()

    private fun hasCard(t: String) = rule.onAllNodesWithContentDescription(t).fetchSemanticsNodes().isNotEmpty()

    private fun hasRound(n: Int) = hasText("Round $n of 3 · last one standing goes on Home")

    private fun prefs() = Settings(RuntimeEnvironment.getApplication()).prefs

    private fun openPick() {
        logIn()
        open()
        waitFocus { it?.startsWith("Pick for me") == true }
        rule.waitUntil(5_000) { hasText("Dandadan") }
    }

    private fun rows(key: Long, n: Int) {
        rule.mainClock.autoAdvance = false
        repeat(n) { press(key) }
        rule.mainClock.advanceTimeBy(1_000)
        rule.mainClock.autoAdvance = true
    }

    private fun selectDuel() {
        press(DOWN, OK)
        rule.waitUntil(5_000) { hasText("Keep one") }
    }

    private fun selectBrowse() {
        rows(DOWN, 2)
        press(OK)
        rule.waitUntil(5_000) { hasCard("Alpha") }
    }

    @Test fun movingOverTheModeRowsChangesNothingOkSelectsAndRightEntersTheSelectedMode() {
        openPick()
        press(DOWN, DOWN)
        rule.mainClock.advanceTimeBy(1_000)
        assertTrue(hasText("Dandadan") && !hasCard("Alpha"))
        assertTrue(focused()?.startsWith("Browse") == true)
        press(UP, RIGHT)
        assertEquals("+ Home", focused())
        press(LEFT)
        assertTrue(focused()?.startsWith("Pick for me") == true)
        press(DOWN, OK)
        rule.waitUntil(5_000) { hasText("Keep one") }
    }

    @Test fun opensOnPickForMeWhenLoggedInWithAPlanningList() {
        openPick()
        assertTrue(hasText("AniList 90"))
        assertTrue(hasText("Short: 12 episodes"))
        assertTrue(hasText("TV · 12 episodes · 2023 · Adventure · AniList 90"))
        assertTrue(hasText("Kaiju"))
    }

    @Test fun opensOnBrowseWhenLoggedOutAndShowsTheCatalogsAsOneGrid() {
        open()
        waitFocus { it?.startsWith("Browse") == true }
        rule.waitUntil(5_000) { hasCard("Alpha") }
        assertTrue(hasCard("Echo"))
        assertTrue(hasText("4 titles"))
    }

    @Test fun loggedOutPickAndThisOrThatAskToLogInAndTheButtonIsReachable() {
        open()
        waitFocus { it?.startsWith("Browse") == true }
        rows(UP, 2)
        press(OK)
        rule.waitUntil(5_000) { hasText("Log in with AniList to see your lists here") }
        press(RIGHT)
        assertEquals("Log in", focused())
        press(LEFT)
        assertTrue(focused()?.startsWith("Pick for me") == true)
        press(DOWN)
        waitFocus { it?.startsWith("This or that") == true }
        assertTrue(hasText("Log in with AniList to see your lists here"))
    }

    @Test fun eachButtonShowsTheNextPickAndKeepsItsPlace() {
        openPick()
        press(RIGHT)
        assertEquals("+ Home", focused())
        press(OK)
        rule.waitUntil(5_000) { saves.size == 1 && !hasText("Dandadan") }
        assertEquals("Dandadan", saves.single().name)
        assertEquals("+ Home", focused())
        assertTrue(hasText("Nana"))
        press(RIGHT, OK)
        rule.waitUntil(5_000) { !hasText("Kaiju") }
        assertEquals("✕ Not for me", focused())
        assertEquals("4", prefs().getString("discover_never", ""))
        press(RIGHT, OK)
        rule.waitUntil(5_000) { !hasText("Lupin") }
        assertEquals("» Skip", focused())
        assertTrue(prefs().getString("discover_skipped", "").orEmpty().startsWith("5:"))
        assertEquals(1, saves.size)
    }

    @Test fun downFromThePickButtonsGoesNowhereAndNextPicksAreReachedOnlyFromTheColumn() {
        openPick()
        press(RIGHT, DOWN)
        assertEquals("+ Home", focused())
        press(RIGHT, DOWN)
        assertEquals("✕ Not for me", focused())
        press(RIGHT, DOWN)
        assertEquals("» Skip", focused())
    }

    @Test fun okOnANextPickMakesItTheCurrentOneAndKeepsTheRow() {
        openPick()
        rows(DOWN, 3)
        assertEquals("Kaiju", focused())
        press(OK)
        rule.waitUntil(5_000) { hasText("TV · 12 episodes · 2023 · Adventure · AniList 85") }
        assertEquals("Dandadan", focused())
        assertEquals(0, saves.size)
    }

    @Test fun okOnTheTitleOrTheCoverOpensTheTitlePage() {
        openPick()
        (0..1).forEach { which ->
            rule.onAllNodes(androidx.compose.ui.test.hasText("Dandadan") and androidx.compose.ui.test.hasClickAction())[which].requestFocus()
            press(OK)
            rule.waitUntil(5_000) { focused()?.startsWith("▶") == true }
            press(Key.Back.keyCode)
            waitFocus { it?.contains("Dandadan") == true }
            assertTrue(focused()?.startsWith("Pick for me") != true)
        }
        assertEquals(0, home)
    }

    @Test fun nothingLeftToPickShowsOneQuietLine() {
        logIn(planning.take(1))
        open()
        waitFocus { it?.startsWith("Pick for me") == true }
        rule.waitUntil(5_000) { hasText("Nothing left to pick right now") }
        assertTrue(focused()?.startsWith("Pick for me") == true)
    }

    private fun playToTheWinner() {
        openPick()
        selectDuel()
        assertTrue(hasRound(1))
        assertTrue(hasText("8 titles · 8 left"))
        press(RIGHT)
        assertTrue(focused()?.contains("Dandadan") == true)
        press(RIGHT)
        assertTrue(focused()?.contains("Kaiju") == true)
        press(LEFT)
        press(OK)
        assertTrue(focused()?.contains("Lupin") == true)
        press(RIGHT, OK)
        assertTrue(focused()?.contains("Nana") == true)
        press(OK)
        assertTrue(focused()?.contains("Mob") == true)
        press(OK)
        assertTrue(hasRound(2))
        assertTrue(hasText("8 titles · 4 left"))
        assertTrue(focused()?.contains("Bocchi") == true)
        press(LEFT, OK)
        assertTrue(focused()?.contains("Nana") == true)
        press(RIGHT, OK)
        assertTrue(hasRound(3))
        assertTrue(focused()?.contains("Mob") == true)
        press(LEFT, OK)
        rule.waitUntil(5_000) { hasText("Last one standing") }
        waitFocus { it == "+ Home" }
    }

    @Test fun theKnockoutRunsToAWinnerWithLeftRightAndOk() {
        playToTheWinner()
        press(OK)
        rule.waitUntil(5_000) { saves.size == 1 }
        assertEquals("Dandadan", saves.single().name)
        waitFocus { it == "✓ On Home" }
        press(RIGHT, RIGHT, OK)
        rule.waitUntil(5_000) { hasRound(1) }
        waitFocus { it?.contains("Kaiju") == true }
    }

    @Test fun withFewerThanTwoCandidatesThereIsOneQuietLine() {
        logIn(planning.take(2))
        open()
        waitFocus { it?.startsWith("Pick for me") == true }
        selectQuietDuel()
    }

    private fun selectQuietDuel() {
        press(DOWN, OK)
        rule.waitUntil(5_000) { hasText("Not enough titles on your Planning list to choose between") }
    }

    @Test fun browseCatalogRowStepsAndTheGridChangesThenRightEntersAndLeftReturns() {
        open()
        waitFocus { it?.startsWith("Browse") == true }
        rule.waitUntil(5_000) { hasCard("Alpha") }
        press(DOWN)
        assertTrue(focused()?.startsWith("Catalog") == true)
        press(RIGHT)
        rule.waitUntil(5_000) { !hasCard("Delta") && hasCard("Beta") }
        assertTrue(hasText("2 titles"))
        press(UP, RIGHT)
        assertEquals("Alpha", focused())
        press(RIGHT)
        assertEquals("Beta", focused())
        press(LEFT, LEFT)
        assertTrue(focused()?.startsWith("Browse") == true)
        press(RIGHT)
        assertEquals("Alpha", focused())
    }

    @Test fun backFromTheGridGoesToTheColumnAndBackAgainLeaves() {
        open()
        waitFocus { it?.startsWith("Browse") == true }
        rule.waitUntil(5_000) { hasCard("Alpha") }
        press(RIGHT, Key.Back.keyCode)
        assertTrue(focused()?.startsWith("Browse") == true)
        assertEquals(0, home)
        press(Key.Back.keyCode)
        assertEquals(1, home)
    }

    @Test fun okOnACoverOpensTheTitlePageAndBackRestoresTheCover() {
        open()
        waitFocus { it?.startsWith("Browse") == true }
        rule.waitUntil(5_000) { hasCard("Echo") }
        press(RIGHT, RIGHT, RIGHT, RIGHT)
        assertEquals("Echo", focused())
        press(OK)
        rule.waitUntil(5_000) { focused()?.startsWith("▶") == true }
        press(Key.Back.keyCode)
        rule.waitUntil(5_000) { focused() == "Echo" }
        assertEquals(0, home)
    }

    private fun search(q: String) {
        rule.onNode(hasImeAction(androidx.compose.ui.text.input.ImeAction.Done)).requestFocus()
        press(OK)
        rule.onNode(hasSetTextAction()).performTextInput(q)
        rule.onNode(hasSetTextAction()).performImeAction()
    }

    @Test fun aSearchReplacesTheGridAndBackClearsItStayingInDiscover() {
        open()
        waitFocus { it?.startsWith("Browse") == true }
        rule.waitUntil(5_000) { hasCard("Alpha") }
        search("zeta")
        rule.waitUntil(5_000) { hasCard("Zeta") && !hasCard("Alpha") }
        press(Key.Back.keyCode)
        rule.waitUntil(5_000) { hasCard("Alpha") && !hasCard("Zeta") }
        assertEquals(0, home)
    }

    @Test fun backFromASearchedTitleGoesBackToTheResults() {
        open()
        waitFocus { it?.startsWith("Browse") == true }
        rule.waitUntil(5_000) { hasCard("Alpha") }
        search("zeta")
        rule.waitUntil(5_000) { hasCard("Zeta") }
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Zeta")).requestFocus()
        press(RIGHT)
        assertEquals("Eta", focused())
        press(OK)
        rule.waitUntil(5_000) { focused()?.startsWith("▶") == true }
        press(Key.Back.keyCode)
        rule.waitUntil(5_000) { focused() == "Eta" }
        assertTrue(hasText("zeta"))
        assertEquals(0, home)
    }

    @Test fun myListNotOnItHidesTitlesOnHisLists() {
        logIn(planning)
        val board = object : Discovery by discovery {
            override suspend fun board() = listOf("Popular" to listOf(title(3, "Alpha"), title(20, "Beta")))
        }
        open(board)
        waitFocus { it?.startsWith("Pick for me") == true }
        rows(DOWN, 2)
        press(OK)
        rule.waitUntil(5_000) { hasCard("Alpha") }
        press(DOWN, DOWN, DOWN)
        assertTrue(focused()?.startsWith("My list") == true)
        press(RIGHT)
        rule.waitUntil(5_000) { !hasCard("Alpha") && hasCard("Beta") }
        assertTrue(hasText("1 of 2 shown"))
    }

    @Test fun aDeepLinkOpensTheTitlePageAtOnce() {
        val meta = JSONObject("""{"episodes":[{"id":"alpha:1","number":1,"watched":true},{"id":"alpha:2","number":2}]}""")
        val withEpisodes = object : Discovery by discovery { override suspend fun discoverMeta(type: String, id: String) = meta }
        open(withEpisodes, start = Found("alpha", "series", "Alpha", "/p/alpha.jpg", "2024"))
        rule.waitUntil(5_000) { focused()?.startsWith("▶") == true }
        press(DOWN)
        rule.waitUntil(2_000) { focused()?.startsWith("Episode 2") == true }
    }

    @Test fun theBoardIsAskedOnceAcrossATitlePageAndBack() {
        open()
        waitFocus { it?.startsWith("Browse") == true }
        rule.waitUntil(5_000) { hasCard("Alpha") }
        press(RIGHT, OK)
        rule.waitUntil(5_000) { focused()?.startsWith("▶") == true }
        press(Key.Back.keyCode)
        rule.waitUntil(5_000) { focused() == "Alpha" }
        assertEquals(1, boards)
    }

    @Test fun aSlowSearchIsNotRunAgainOnReturn() {
        searchDelay = 1_500
        open()
        waitFocus { it?.startsWith("Browse") == true }
        rule.waitUntil(5_000) { hasCard("Alpha") }
        search("zeta")
        rule.waitUntil(5_000) { hasCard("Zeta") }
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Zeta")).requestFocus()
        press(OK)
        rule.waitUntil(5_000) { focused()?.startsWith("▶") == true }
        press(Key.Back.keyCode)
        rule.waitUntil(5_000) { focused() == "Zeta" }
        assertEquals(1, searches)
        assertEquals(1, boards)
    }

    @Test fun aFailedSearchLeavesNoErrorOnceItIsClearedAndTheBoardIsNotAskedAgain() {
        open()
        waitFocus { it?.startsWith("Browse") == true }
        rule.waitUntil(5_000) { hasCard("Alpha") }
        search("boom")
        rule.waitUntil(5_000) { hasText("search failed") }
        press(Key.Back.keyCode)
        rule.waitUntil(5_000) { hasCard("Alpha") && !hasText("search failed") }
        assertEquals(1, boards)
    }

    @Test fun theWinnerOpenedAndBackIsStillTheWinnerWithOpenFocused() {
        playToTheWinner()
        press(RIGHT, OK)
        rule.waitUntil(5_000) { focused()?.startsWith("▶") == true }
        press(Key.Back.keyCode)
        waitFocus { it == "Open" }
        assertTrue(hasText("Last one standing"))
    }

    @Test fun aChosenNextPickOpenedAndBackIsStillThePickWithItsCardFocused() {
        openPick()
        rows(DOWN, 3)
        press(OK)
        rule.waitUntil(5_000) { hasText("TV · 12 episodes · 2023 · Adventure · AniList 85") }
        press(RIGHT, UP)
        assertEquals("Kaiju", focused())
        press(OK)
        rule.waitUntil(5_000) { focused()?.startsWith("▶") == true }
        press(Key.Back.keyCode)
        waitFocus { it == "Kaiju" }
        assertTrue(hasText("TV · 12 episodes · 2023 · Adventure · AniList 85"))
    }

    @Test fun picksWaitAtMostTwoSecondsForTheTitlesOnHomeAndALateAnswerIsAppliedAfterHeLeavesTheContent() {
        logIn()
        savedDelay = 10_000
        onHomeList = listOf(title(3, "Dandadan"))
        open()
        rule.mainClock.autoAdvance = false
        Thread.sleep(500)
        rule.mainClock.advanceTimeBy(1_500)
        assertTrue(hasText("Loading…") && !hasText("Dandadan"))
        rule.mainClock.advanceTimeBy(700)
        assertTrue(hasText("Dandadan"))
        press(RIGHT)
        rule.mainClock.advanceTimeBy(10_000)
        assertTrue(hasText("Dandadan"))
        rule.mainClock.autoAdvance = true
        press(LEFT)
        rule.waitUntil(5_000) { !hasText("Dandadan") && hasText("Kaiju") }
    }

    @Test fun plusHomeLearnsTheOnHomeSetFirstAndDoesNotSaveATitleAlreadyThere() {
        logIn()
        savedDelay = 10_000
        onHomeList = listOf(title(3, "Dandadan"))
        open()
        waitFocus { it?.startsWith("Pick for me") == true }
        rule.waitUntil(5_000) { hasText("Dandadan") }
        press(RIGHT, OK)
        rule.mainClock.advanceTimeBy(11_000)
        rule.waitUntil(5_000) { !hasText("Dandadan") }
        assertEquals(0, saves.size)
    }

    @Test fun aSaveInProgressIsNotLostWhenHeOpensATitleRightAfter() {
        openPick()
        saveDelay = 3_000
        press(RIGHT, OK, RIGHT, RIGHT, RIGHT, OK)
        rule.waitUntil(5_000) { focused()?.startsWith("▶") == true }
        press(Key.Back.keyCode)
        waitFocus { it?.contains("Kaiju") == true }
        assertTrue(!hasText("Dandadan"))
        assertEquals(1, saves.size)
    }

    @Test fun aListThatArrivesWhileTheTitlePageIsOpenDoesNotChangeThePickOnBack() {
        openPick()
        press(RIGHT, UP, OK)
        rule.waitUntil(5_000) { focused()?.startsWith("▶") == true }
        refreshWithZed()
        press(Key.Back.keyCode)
        waitFocus { it == "Dandadan" }
        assertTrue(!hasText("Zed"))
    }

    @Test fun searchingAgainWhileTheFirstSearchLoadsLeavesNoCancelledError() {
        searchDelay = 1_500
        open()
        waitFocus { it?.startsWith("Browse") == true }
        rule.waitUntil(5_000) { hasCard("Alpha") }
        search("zeta")
        rule.onNode(hasImeAction(androidx.compose.ui.text.input.ImeAction.Done)).requestFocus()
        press(OK)
        rule.onNode(hasSetTextAction()).performTextReplacement("eta")
        rule.onNode(hasSetTextAction()).performImeAction()
        rule.waitUntil(5_000) { hasCard("Zeta") }
        assertTrue(!hasText("Try again"))
    }

    @Test fun downFromTheLineWhenItsReturnTargetIsGoneStillLeavesTheLine() {
        logIn()
        openWithTheLine()
        waitFocus { it?.startsWith("Pick for me") == true }
        rule.waitUntil(5_000) { hasText("Dandadan") }
        press(RIGHT, UP, UP)
        assertEquals(line, focused())
        logIn(planning.take(1))
        rule.waitUntil(5_000) { fresh != null }
        rule.runOnIdle { fresh?.invoke(AniListLists.cached(prefs(), "pedro")) }
        rule.waitUntil(5_000) { hasText("Nothing left to pick right now") }
        press(DOWN)
        assertTrue(focused()?.startsWith("Pick for me") == true)
    }

    private fun refreshWithZed() {
        logIn(planning + mine(11, "Zed", "PLANNING", 99))
        rule.waitUntil(5_000) { fresh != null }
        rule.runOnIdle { fresh?.invoke(AniListLists.cached(prefs(), "pedro")) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
    }

    @Test fun aFreshListDoesNotChangeThePickWhileHeIsOnItsButtonsOnlyAfterHeLeaves() {
        openPick()
        press(RIGHT)
        assertEquals("+ Home", focused())
        refreshWithZed()
        assertTrue(hasText("Dandadan") && !hasText("Zed"))
        press(LEFT)
        rule.waitUntil(5_000) { hasText("Zed") }
    }

    @Test fun aFreshListDoesNotResetTheBracketWhileHeIsInItOnlyAfterHeLeaves() {
        openPick()
        selectDuel()
        press(RIGHT)
        refreshWithZed()
        assertTrue(hasCard("Dandadan") && !hasCard("Zed"))
        press(LEFT)
        rule.waitUntil(5_000) { hasCard("Zed") }
    }

    private fun openWithTheLine() {
        seedPictures()
        val jiten = queue()
        rule.setContent {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.onBackKey { home++ }) {
                DiscoverScreen(discovery, Settings(RuntimeEnvironment.getApplication()), jiten = jiten, read = { prefs, user, _, _, f -> fresh = f; AniListLists.cached(prefs, user) })
                RdMissingLine(Settings(RuntimeEnvironment.getApplication()).apply { rdToken = ""; addons = emptyList() }) { lineOpened++ }
            }
        }
    }

    private val line = "Real-Debrid key missing: no video will play"

    @Test fun upFromTheTopOfTheContentReachesTheLineStaysThereOkOpensSettingsAndBackLeaves() {
        logIn()
        openWithTheLine()
        waitFocus { it?.startsWith("Pick for me") == true }
        rule.waitUntil(5_000) { hasText("Dandadan") }
        press(RIGHT, UP)
        assertEquals("Dandadan", focused())
        press(UP)
        assertEquals(line, focused())
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(line, focused())
        press(DOWN)
        assertEquals("Dandadan", focused())
        press(UP, OK)
        assertEquals(1, lineOpened)
        press(Key.Back.keyCode)
        assertEquals(1, home)
    }

    @Test fun upFromTheFirstColumnRowReachesTheLineAndDownComesBack() {
        openWithTheLine()
        waitFocus { it?.startsWith("Browse") == true }
        rule.waitUntil(5_000) { hasCard("Alpha") }
        rule.mainClock.autoAdvance = false
        press(UP, UP, UP)
        rule.mainClock.advanceTimeBy(1_000)
        rule.mainClock.autoAdvance = true
        assertEquals(line, focused())
        press(DOWN)
        assertTrue(focused()?.startsWith("Pick for me") == true)
    }

    private companion object {
        val DOWN = Key.DirectionDown.keyCode; val UP = Key.DirectionUp.keyCode; val LEFT = Key.DirectionLeft.keyCode
        val RIGHT = Key.DirectionRight.keyCode; val OK = Key.DirectionCenter.keyCode
    }
}
