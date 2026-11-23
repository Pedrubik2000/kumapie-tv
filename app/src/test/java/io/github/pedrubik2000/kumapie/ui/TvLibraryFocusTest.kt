package io.github.pedrubik2000.kumapie.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import io.github.pedrubik2000.kumapie.data.AniListLists
import io.github.pedrubik2000.kumapie.data.Discovery
import io.github.pedrubik2000.kumapie.data.Found
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.lang.JitenApi
import io.github.pedrubik2000.kumapie.lang.JitenHttp
import io.github.pedrubik2000.kumapie.lang.JitenQueue
import io.github.pedrubik2000.kumapie.lang.JitenStore
import io.github.pedrubik2000.kumapie.lang.TitleDifficulty
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
class TvLibraryFocusTest {
    @get:Rule val rule = createComposeRule()

    private var showing by mutableStateOf(true)
    private val opened = ArrayList<Found>()

    @Before fun forget() {
        LibraryMemory.list = null
        LibraryMemory.opened = null
    }

    private fun discovery(kept: List<Found> = emptyList()) = object : Discovery {
        override suspend fun board() = emptyList<Pair<String, List<Found>>>()
        override suspend fun discover(q: String) = emptyList<Found>()
        override suspend fun discoverMeta(type: String, id: String) = JSONObject()
        override suspend fun discoverStreams(type: String, id: String) = JSONArray()
        override suspend fun saved() = kept
        override suspend fun save(title: Found, on: Boolean) = kept
        override suspend fun seen(id: String, on: Boolean) = Unit
    }

    private fun mine(id: Int, name: String, status: String, progress: Int = 0, episodes: Int = 12) = JSONObject()
        .put("id", id).put("kitsu", "$id").put("native", name).put("romaji", name).put("cover", "/c/$id.jpg").put("banner", "")
        .put("format", "TV").put("episodes", episodes).put("status", status).put("progress", progress).put("score", 0.0)
        .put("average", 80).put("genres", JSONArray(listOf("Adventure"))).put("year", 2023)

    private val lists = listOf(
        mine(1, "Frieren", "CURRENT", 11, 28), mine(2, "Bocchi", "CURRENT", 3), mine(3, "Dandadan", "PLANNING"),
        mine(4, "Kaiju", "PLANNING", 2), mine(5, "Lupin", "PLANNING"),
    )

    private fun logIn(mine: List<JSONObject> = lists) {
        Settings(RuntimeEnvironment.getApplication()).prefs.edit().putString("anilist_token", "t").putString("anilist_user", "pedro")
            .putString(AniListLists.CACHE + "_pedro", JSONArray(mine).toString()).commit()
        val covers = RuntimeEnvironment.getApplication().getSharedPreferences("backdrops_kitsu", 0).edit()
        mine.forEach { covers.putString("kitsu:" + it.getString("kitsu"), "/b/${it.getString("kitsu")}.jpg") }
        covers.commit()
    }

    private fun queue(vararg values: Pair<String, TitleDifficulty>): JitenQueue {
        val store = JitenStore(File(RuntimeEnvironment.getApplication().cacheDir, "jiten-lib-${System.nanoTime()}.json"))
        values.forEachIndexed { i, (kitsu, d) -> store.linkKitsu(kitsu, 100 + i); store.put(100 + i, d) }
        return JitenQueue(store, JitenApi(JitenHttp), resolve = { null }, idle = { false })
    }

    private fun open(kept: List<Found> = emptyList(), jiten: JitenQueue = queue()) {
        rule.setContent {
            if (showing) LibraryScreen(discovery(kept), Settings(RuntimeEnvironment.getApplication()), onHome = {}, onDiscover = {}, onReview = {}, onSettings = {},
                onOpen = { opened += it; showing = false }, jiten = jiten, read = { prefs, user, _, _, _ -> AniListLists.cached(prefs, user) })
        }
    }

    private fun focused(): String? = rule.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()?.config?.let { c ->
        c.getOrNull(SemanticsProperties.Text)?.joinToString(" ")?.takeIf { it.isNotBlank() }
            ?: c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
    }

    private fun press(vararg keys: Long) = keys.map(::Key).forEach { k -> rule.onRoot().performKeyInput { pressKey(k) }; rule.waitForIdle() }

    private fun waitFocus(want: (String?) -> Boolean) = rule.waitUntil(5_000) { want(focused()) }

    private fun hasCard(title: String) = rule.onAllNodesWithContentDescription(title).fetchSemanticsNodes().isNotEmpty()

    private fun hasText(t: String) = rule.onAllNodesWithText(t).fetchSemanticsNodes().isNotEmpty()

    private fun selectPlanning() {
        waitFocus { it?.startsWith("Watching") == true }
        press(DOWN)
        rule.waitUntil(5_000) { hasCard("Dandadan") }
    }

    @Test fun opensOnWatchingWithItsTitles() {
        logIn()
        open()
        waitFocus { it?.startsWith("Watching") == true }
        rule.waitUntil(5_000) { hasCard("Frieren") }
        assertTrue(hasCard("Bocchi"))
        assertTrue(!hasCard("Dandadan"))
        assertTrue(hasText("Watching · 2 titles"))
    }

    @Test fun opensOnPlanningWhenWatchingIsEmpty() {
        logIn(lists.drop(2))
        open()
        waitFocus { it?.startsWith("Planning") == true }
        rule.waitUntil(5_000) { hasCard("Dandadan") }
    }

    @Test fun downSelectsPlanningAndTheGridChanges() {
        logIn()
        open()
        selectPlanning()
        assertTrue(focused()?.startsWith("Planning") == true)
        assertTrue(!hasCard("Frieren"))
        assertTrue(hasText("Planning · 3 titles"))
    }

    @Test fun passingThroughAListRowDoesNotSelectItUntilFocusRests() {
        logIn()
        open()
        waitFocus { it?.startsWith("Watching") == true }
        rule.waitUntil(5_000) { hasCard("Frieren") }
        rule.mainClock.autoAdvance = false
        press(DOWN)
        assertTrue(hasCard("Frieren"))
        assertTrue(!hasCard("Dandadan"))
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertTrue(hasCard("Dandadan"))
    }

    @Test fun rightEntersTheGridAndLeftReturnsToTheSelectedList() {
        logIn()
        open()
        selectPlanning()
        press(RIGHT)
        assertEquals("Dandadan", focused())
        press(RIGHT)
        assertEquals("Kaiju", focused())
        press(LEFT)
        assertEquals("Dandadan", focused())
        press(LEFT)
        assertTrue(focused()?.startsWith("Planning") == true)
        press(RIGHT)
        assertEquals("Dandadan", focused())
    }

    @Test fun leftFromTheColumnGoesToTheRailAndRightComesBackToTheSelectedList() {
        logIn()
        open()
        selectPlanning()
        press(LEFT)
        assertEquals("Library", focused())
        press(RIGHT)
        assertTrue(focused()?.startsWith("Planning") == true)
    }

    @Test fun upFromTheTopOfTheGridGoesToSortAndOkCyclesIt() {
        logIn()
        open()
        selectPlanning()
        press(RIGHT, UP)
        assertEquals("Sort: My order", focused())
        press(OK)
        assertTrue(hasText("Sort: Easiest first"))
        press(DOWN)
        assertEquals("Dandadan", focused())
    }

    @Test fun backFromTheGridGoesToTheColumn() {
        logIn()
        open()
        selectPlanning()
        press(RIGHT)
        press(BACK)
        assertTrue(focused()?.startsWith("Planning") == true)
    }

    @Test fun okOnACheckboxFiltersTheList() {
        logIn()
        open()
        selectPlanning()
        rule.onNodeWithText("In progress").requestFocus()
        press(OK)
        rule.waitUntil(5_000) { hasText("Planning · 1 of 3 shown") }
        assertTrue(hasCard("Kaiju"))
        assertTrue(!hasCard("Dandadan"))
        press(OK)
        rule.waitUntil(5_000) { hasText("Planning · 3 titles") }
    }

    @Test fun onHomeKeepsTheSavedTitles() {
        logIn()
        open(kept = listOf(Found("kitsu:5", "series", "Lupin", "", "2023")))
        selectPlanning()
        rule.onNodeWithText("On Home").requestFocus()
        press(OK)
        rule.waitUntil(5_000) { hasText("Planning · 1 of 3 shown") }
        assertTrue(hasCard("Lupin"))
    }

    @Test fun leftAndRightOnTheDifficultyRowChangeTheLimitAndDoNotLeaveIt() {
        logIn()
        open(jiten = queue("1" to TitleDifficulty(2.7, 9, null, null)))
        waitFocus { it?.startsWith("Watching") == true }
        rule.onNodeWithText("Up to").requestFocus()
        assertTrue(hasText("‹  Any  ›"))
        press(RIGHT)
        assertTrue(hasText("‹  Beginner  ›"))
        rule.waitUntil(5_000) { hasText("Watching · 0 of 2 shown · 1 not rated yet") }
        press(RIGHT, RIGHT)
        assertTrue(hasText("‹  Average  ›"))
        rule.waitUntil(5_000) { hasText("Watching · 1 of 2 shown · 1 not rated yet") }
        assertTrue(hasCard("Frieren"))
        assertTrue(!hasCard("Bocchi"))
        press(LEFT)
        assertTrue(hasText("‹  Easy  ›"))
        assertTrue(focused()?.startsWith("Up to") == true)
        press(UP)
        assertTrue(focused()?.startsWith("On Home") == true)
    }

    @Test fun loggedOutShowsTheLoginLineWithFocusOnLogIn() {
        open()
        waitFocus { it == "Log in" }
        assertTrue(hasText("Log in with AniList to see your lists here"))
    }

    @Test fun anEmptyListSaysSoAndFiltersThatHideEverythingSayThat() {
        logIn()
        open()
        waitFocus { it?.startsWith("Watching") == true }
        press(DOWN, DOWN)
        rule.waitUntil(5_000) { hasText("Nothing in this list yet") }
        press(UP)
        rule.waitUntil(5_000) { hasCard("Dandadan") }
        rule.onNodeWithText("On Home").requestFocus()
        press(OK)
        rule.waitUntil(5_000) { hasText("Nothing here with these filters") }
    }

    @Test fun okOnACoverOpensItAndComingBackFocusesItAgain() {
        logIn()
        open()
        selectPlanning()
        press(RIGHT, RIGHT, OK)
        rule.waitUntil(5_000) { opened.isNotEmpty() }
        assertEquals("kitsu:4", opened.single().id)
        showing = true
        waitFocus { it == "Kaiju" }
        assertTrue(hasText("Planning · 3 titles"))
    }

    private companion object {
        val DOWN = Key.DirectionDown.keyCode; val UP = Key.DirectionUp.keyCode; val LEFT = Key.DirectionLeft.keyCode
        val RIGHT = Key.DirectionRight.keyCode; val OK = Key.DirectionCenter.keyCode; val BACK = Key.Back.keyCode
    }
}
