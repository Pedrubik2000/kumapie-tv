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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import io.github.pedrubik2000.kumapie.data.AniList
import io.github.pedrubik2000.kumapie.data.AniListSync
import io.github.pedrubik2000.kumapie.data.Discovery
import io.github.pedrubik2000.kumapie.data.Found
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.lang.JitenApi
import io.github.pedrubik2000.kumapie.lang.JitenHttp
import io.github.pedrubik2000.kumapie.lang.JitenQueue
import io.github.pedrubik2000.kumapie.lang.JitenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
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
import java.io.IOException

class FakeAniList(var entry: suspend () -> AniList.Entry, var format: suspend () -> String = { "POINT_10" }) {
    var online = true
    var reads = 0
    val saved = ArrayList<AniList.Entry>()

    val api = AniListSync.Api(
        lookup = { if (online) 5 else throw IOException("offline") },
        entry = { _, _ -> reads++; entry() },
        save = { _, _, _, after -> saved += after; after },
        format = { format() },
    )
}

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television-xhdpi")
class TvTitleFocusTest {
    @get:Rule val rule = createComposeRule()

    private val title = Found("kitsu:5", "series", "Alpha", "/p/alpha.jpg", "2020")
    private var shown by mutableStateOf(true)

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

    private fun entry(status: String?, progress: Int = 0, score: Double = 0.0) = AniList.Entry(status, score, progress, 0, "", "", 3)

    private fun logIn() {
        Settings(RuntimeEnvironment.getApplication()).prefs.edit().putString("anilist_token", "t").commit()
    }

    private fun syncOf(fake: FakeAniList) = AniListSync(Settings(RuntimeEnvironment.getApplication()).prefs, api = fake.api,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

    private fun open(watched: List<Boolean>, fake: FakeAniList = FakeAniList({ entry(null) }), sync: AniListSync = syncOf(fake)) {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("backdrops_kitsu", 0).edit().putString("kitsu:5", "/b/5.jpg").commit()
        val queue = JitenQueue(JitenStore(File(app.cacheDir, "jiten-title-${System.nanoTime()}.json")), JitenApi(JitenHttp), resolve = { null }, idle = { false })
        rule.setContent { if (shown) DiscoverScreen(discovery(watched), Settings(RuntimeEnvironment.getApplication()), start = title, jiten = queue, sync = sync) }
    }

    private fun focused(): String? = rule.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()?.config?.let { c ->
        c.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text ?: c.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
    }

    private fun press(k: Key) {
        rule.onRoot().performKeyInput { pressKey(k) }
        rule.waitForIdle()
    }

    private fun waitFocus(want: (String?) -> Boolean) = rule.waitUntil(5_000) { want(focused()) }

    private fun toStatus() {
        waitFocus { it?.startsWith("▶") == true }
        rule.waitUntil(5_000) { hasText("Status") }
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        waitFocus { it == "Status" }
    }

    private fun hasText(t: String) = rule.onAllNodesWithText(t).fetchSemanticsNodes().isNotEmpty()

    @Test fun downFromPlayLandsOnTheNextUpEpisodeAndUpComesBack() {
        open(listOf(true, false, false))
        waitFocus { it?.startsWith("▶") == true }
        press(Key.DirectionDown)
        waitFocus { it == "Episode 2" }
        press(Key.DirectionUp)
        waitFocus { it?.startsWith("▶") == true }
    }

    @Test fun rightFromTheButtonsReachesTheStatusRowEvenWithoutAUsername() {
        logIn()
        open(listOf(true, false, false), FakeAniList({ entry("CURRENT", 1) }))
        toStatus()
        press(Key.DirectionDown)
        assertEquals("Your score", focused())
        press(Key.DirectionDown)
        assertEquals("Episode 2", focused())
    }

    @Test fun withoutALoginTheCardHasNothingToFocus() {
        val fake = FakeAniList({ entry("CURRENT", 1) })
        open(listOf(true, false, false), fake)
        waitFocus { it?.startsWith("▶") == true }
        rule.waitUntil(5_000) { hasText("Log in with AniList to see your list here") }
        repeat(3) { press(Key.DirectionRight) }
        assertEquals("+ Home", focused())
        assertTrue(!hasText("Status"))
        assertEquals(0, fake.reads)
    }

    @Test fun aFinishedTitleWithoutAScoreOpensOnYourScore() {
        logIn()
        open(listOf(true, true, true), FakeAniList({ entry("COMPLETED", 3) }))
        waitFocus { it == "Your score" }
        assertTrue(hasText("You finished it. Rate it?"))
    }

    @Test fun aFinishedTitleThatHasAScoreOpensOnPlay() {
        logIn()
        val fake = FakeAniList({ entry("COMPLETED", 3, 8.0) })
        open(listOf(true, true, true), fake)
        waitFocus { it?.startsWith("▶") == true }
        rule.waitUntil(5_000) { hasText("Status") }
        assertTrue(focused()?.startsWith("▶") == true)
        assertTrue(!hasText("You finished it. Rate it?"))
        assertEquals(0, fake.saved.size)
    }

    @Test fun whenTheReadFailsPlayHasTheFocusAndTheCardHasNothingToFocus() {
        logIn()
        val fake = FakeAniList({ throw IOException("down") })
        open(listOf(true, false, false), fake)
        waitFocus { it?.startsWith("▶") == true }
        rule.waitUntil(5_000) { hasText("Couldn't read your AniList list") }
        repeat(3) { press(Key.DirectionRight) }
        assertEquals("Try again", focused())
        assertEquals(0, fake.saved.size)
    }

    @Test fun theFailedCardOffersTryAgainWithRightFromTheButtonsAndItReReads() {
        logIn()
        val fake = FakeAniList({ throw IOException("down") })
        open(listOf(true, false, false), fake)
        waitFocus { it?.startsWith("▶") == true }
        rule.waitUntil(5_000) { hasText("Couldn't read your AniList list") }
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        assertEquals("Try again", focused())
        fake.entry = { entry("CURRENT", 1) }
        press(Key.DirectionCenter)
        rule.waitUntil(5_000) { hasText("Status") && !hasText("Couldn't read your AniList list") }
    }

    @Test fun whileTheReadIsLoadingPlayHasTheFocusAndRightStopsAtHome() {
        logIn()
        val fake = FakeAniList({ awaitCancellation() })
        open(listOf(true, false, false), fake)
        waitFocus { it?.startsWith("▶") == true }
        repeat(3) { press(Key.DirectionRight) }
        assertEquals("+ Home", focused())
        assertTrue(!hasText("Status") || fake.saved.isEmpty())
        assertEquals(0, fake.saved.size)
    }

    @Test fun aLoggedOutTokenShowsTheLoginNoteAndNothingToFocus() {
        logIn()
        open(listOf(true, false, false), FakeAniList({ throw AniList.LoggedOut() }))
        waitFocus { it?.startsWith("▶") == true }
        rule.waitUntil(5_000) { hasText("Log in with AniList to see your list here") }
        repeat(3) { press(Key.DirectionRight) }
        assertEquals("+ Home", focused())
    }

    @Test fun aPlayThatHasNotBeenSentYetShowsAsWatchingAndRightNeverWritesPlanning() {
        logIn()
        val fake = FakeAniList({ entry(null) })
        val sync = syncOf(fake)
        fake.online = false
        sync.watching("kitsu:5", 1)
        fake.online = true
        open(listOf(false, false, false), fake, sync)
        toStatus()
        assertTrue(hasText("‹  Watching  ›"))
        press(Key.DirectionRight)
        rule.waitUntil(5_000) { fake.saved.isNotEmpty() }
        assertEquals(listOf("COMPLETED"), fake.saved.map { it.status })
    }

    @Test fun theScoreStepsInTheFormatReadNowNotTheOneSavedAtLogin() {
        logIn()
        Settings(RuntimeEnvironment.getApplication()).anilistFormat = "POINT_10"
        val fake = FakeAniList({ entry("CURRENT", 1, 85.0) }, { "POINT_100" })
        open(listOf(true, false, false), fake)
        toStatus()
        press(Key.DirectionDown)
        assertTrue(hasText("‹  85  ›"))
        press(Key.DirectionLeft)
        rule.waitUntil(5_000) { fake.saved.isNotEmpty() }
        assertEquals(listOf(84.0), fake.saved.map { it.score })
    }

    @Test fun whenTheFormatCannotBeReadTheScoreRowIsNotFocusable() {
        logIn()
        open(listOf(true, false, false), FakeAniList({ entry("CURRENT", 1, 8.0) }, { throw IOException("no") }))
        toStatus()
        press(Key.DirectionDown)
        assertEquals("Episode 2", focused())
    }

    @Test fun aRewatchShowsAsRewatching() {
        logIn()
        open(listOf(true, false, false), FakeAniList({ entry("REPEATING", 1) }))
        toStatus()
        assertTrue(hasText("‹  Rewatching  ›"))
    }

    @Test fun whenTheFormatCannotBeReadHisRealScoreShowsAndNoRateLine() {
        logIn()
        open(listOf(true, true, true), FakeAniList({ entry("COMPLETED", 3, 8.0) }, { throw IOException("no") }))
        waitFocus { it?.startsWith("▶") == true }
        rule.waitUntil(5_000) { hasText("Status") }
        assertTrue(hasText("‹  8  ›"))
        assertTrue(!hasText("You finished it. Rate it?"))
        assertTrue(focused()?.startsWith("▶") == true)
    }

    @Test fun downStillReachesAnEpisodeAfterTheRowWasScrolledFar() {
        logIn()
        open(List(30) { false }, FakeAniList({ entry("CURRENT", 1) }))
        waitFocus { it?.startsWith("▶") == true }
        press(Key.DirectionDown)
        waitFocus { it == "Episode 1" }
        repeat(20) { press(Key.DirectionRight) }
        press(Key.DirectionUp)
        waitFocus { it?.startsWith("▶") == true }
        press(Key.DirectionDown)
        waitFocus { it?.startsWith("Episode") == true }
        press(Key.DirectionUp)
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        waitFocus { it == "Status" }
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        waitFocus { it?.startsWith("Episode") == true }
    }
}
