package io.github.pedrubik2000.kumapie.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import io.github.pedrubik2000.kumapie.data.Onboarding
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.Startup
import io.github.pedrubik2000.kumapie.lang.DeviceSync
import io.github.pedrubik2000.kumapie.lang.MainDeviceFlow
import io.github.pedrubik2000.kumapie.lang.Pairing
import io.github.pedrubik2000.kumapie.lang.SetupBundle
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television-xhdpi")
class TvOnboardingFlowTest {
    @get:Rule val rule = createComposeRule()

    private val settings get() = Settings(RuntimeEnvironment.getApplication())
    private val hub = "Tab S7+" to "192.168.0.154:47823"

    @org.junit.After fun forgetTheLanguageKit() {
        listOf(io.github.pedrubik2000.kumapie.lang.LangKit::class.java, TvMining::class.java).forEach { c ->
            c.declaredFields.firstOrNull { it.name == "kit" }?.apply { isAccessible = true }?.set(if (c == TvMining::class.java) TvMining else null, null)
        }
    }

    @Before fun fresh() {
        Onboarding.reset(settings.prefs)
        settings.prefs.edit().remove("onboarded").remove("sync_with").apply()
        Onboarding.markDone(settings.prefs, Onboarding.LANGUAGE)
    }

    private class Fake(val hubs: List<Pair<String, String>>, val allowed: DeviceSync.Poll, val got: SetupBundle.Got, val onApply: () -> Unit) : Pairing {
        private var asked: MainDeviceFlow.Hub? = null
        override suspend fun discover(ms: Long) = hubs
        override suspend fun ask(name: String, address: String) = "4829".also { asked = MainDeviceFlow.Hub(name, address) }
        override suspend fun allowed() = allowed
        override suspend fun fetch() = got
        override fun apply(got: SetupBundle.Got.Ok, ticked: Set<String>) = onApply()
        override fun peer() = asked
        override fun hasKey() = asked != null
        override fun me() = "TV"
        override fun forget() { asked = null }
    }

    private fun api(
        hubs: List<Pair<String, String>> = listOf(hub),
        allowed: DeviceSync.Poll = DeviceSync.Poll.WAITING,
        fetch: SetupBundle.Got = SetupBundle.Got.Old,
        apply: () -> Unit = {},
    ): Pairing = Fake(hubs, allowed, fetch, apply)

    private fun open(api: Pairing = api(), onDone: () -> Unit = {}) {
        rule.setContent { TvOnboarding(settings, api, onDone) }
        rule.waitForIdle()
    }

    /** What the D-pad is on: its text, its description, or "field" for the text field. */
    private fun focused(): String? = rule.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()?.config?.let { c ->
        if (c.contains(SemanticsProperties.EditableText)) "field" else c.getOrNull(SemanticsProperties.Text)?.joinToString(" ") ?: c.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
    }

    private fun press(k: Key) { rule.onRoot().performKeyInput { pressKey(k) }; rule.waitForIdle() }

    @Test fun theDpadStartsOnPairAndUpWalksTheFieldTheTicksAndTheHub() {
        open()
        assertEquals("Pair and copy", focused())
        press(Key.DirectionUp)
        assertEquals("field", focused())
        val trail = mutableListOf<String?>()
        repeat(8) { if (focused().orEmpty().startsWith("kumapie on").not()) { press(Key.DirectionUp); trail += focused() } }
        assertTrue(focused().orEmpty().startsWith("kumapie on Tab S7+"))
        assertEquals(5, trail.size - 1)
        repeat(8) { if (focused() != "Pair and copy") press(Key.DirectionDown) }
        assertEquals("Pair and copy", focused())
    }

    @Test fun fieldRightGoesToContinueAndContinueRightToStartFresh() {
        open()
        press(Key.DirectionUp)
        assertEquals("field", focused())
        press(Key.DirectionRight)
        assertEquals("Pair and copy", focused())
        press(Key.DirectionRight)
        assertEquals("Start fresh", focused())
        press(Key.DirectionLeft)
        assertEquals("Pair and copy", focused())
    }

    @Test fun backLeavesForTheLanguageStep() {
        open()
        rule.onNodeWithText("Set up from your main device?").assertExists()
        press(Key.Back)
        rule.onNodeWithText("Languages").assertExists()
        assertFalse(Onboarding.MAIN in Onboarding.done(settings.prefs))
    }

    @Test fun startFreshMarksMainDoneAndAsksTheNextStepsOnly() {
        open()
        rule.onNodeWithText("Start fresh").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertTrue(Onboarding.MAIN in Onboarding.done(settings.prefs))
        assertTrue(Onboarding.copied(settings.prefs).isEmpty())
        rule.onNodeWithText("Home, Library and Discover come from your AniList anime list.").assertExists()
        rule.onNodeWithText("Continue ›").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("Videos & subtitles").assertExists()
        rule.onNodeWithText("Skip").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNodeWithText("Colour words in subtitles").assertExists()
    }

    @Test fun copiedStepsAreSkippedAndPairingCopiesThem() {
        val parts = mapOf("anilist" to JSONObject().put("anilist_token", "t"), "keys" to JSONObject().put("jimaku_key", "j").put("rd_token", "r").put("addons", org.json.JSONArray(listOf("a"))))
        val got = SetupBundle.Got.Ok(parts, mapOf("anilist" to "Pedrubik", "keys" to "Jimaku, Real-Debrid"))
        open(api(allowed = DeviceSync.Poll.ALLOWED, fetch = got, apply = { settings.prefs.edit().putStringSet(Onboarding.COPIED, setOf("anilist", "keys")).commit() }))
        rule.onNodeWithText("Pair and copy").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("Pedrubik")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Continue ›").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(setOf(Onboarding.ANILIST, Onboarding.VIDEOS), Onboarding.copied(settings.prefs))
        rule.onNodeWithText("Colour words in subtitles").assertExists()
    }

    @Test fun aTakenNameGoesBackToPickingWithTheConflictLine() {
        open(api(allowed = DeviceSync.Poll.CONFLICT))
        rule.onNodeWithText("Pair and copy").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("Two devices asked as TV. Pair again.")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Pair and copy").assertExists()
    }

    @Test fun resumesAtTheFirstStepNeitherDoneNorCopied() {
        Onboarding.markDone(settings.prefs, Onboarding.MAIN)
        Onboarding.markCopied(settings.prefs, listOf(Onboarding.ANILIST, Onboarding.VIDEOS))
        open()
        rule.onNodeWithText("Colour words in subtitles").assertExists()
        assertFalse(Startup.onboarded(settings.prefs.all))
    }

    @Test fun noJimakuKeyNoContinuePastTheVideosStep() {
        Onboarding.markDone(settings.prefs, Onboarding.MAIN)
        Onboarding.markCopied(settings.prefs, listOf(Onboarding.ANILIST))
        settings.jimakuKey = ""
        open()
        rule.onNodeWithText("Continue ›").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNodeWithText("Add your Jimaku key from your phone first: without it, streams get no Japanese lines.").assertExists()
        rule.onNodeWithText("Videos & subtitles").assertExists()
    }

    @Test fun noRealDebridKeyNoContinuePastTheVideosStep() {
        Onboarding.markDone(settings.prefs, Onboarding.MAIN)
        Onboarding.markCopied(settings.prefs, listOf(Onboarding.ANILIST))
        settings.apply { jimakuKey = "j"; rdToken = ""; addons = emptyList() }
        open()
        rule.onNodeWithText("Continue ›").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("Real-Debrid key missing", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Videos & subtitles").assertExists()
    }

    @Test fun backAfterTheRealDebridRefusalGoesToThePreviousStepAndDoesNotQuit() {
        Onboarding.markDone(settings.prefs, Onboarding.MAIN)
        Onboarding.markDone(settings.prefs, Onboarding.ANILIST)
        settings.apply { jimakuKey = "j"; rdToken = ""; addons = emptyList() }
        lateinit var activity: androidx.activity.ComponentActivity
        rule.setContent {
            activity = androidx.compose.ui.platform.LocalContext.current as androidx.activity.ComponentActivity
            TvOnboarding(settings, api(), {})
        }
        rule.onNodeWithText("Continue ›").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("Real-Debrid key missing", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        press(Key.Back)
        rule.onNodeWithText("Home, Library and Discover come from your AniList anime list.").assertExists()
        assertFalse(activity.isFinishing)
    }

    @Test fun startWatchingEndsTheOnboarding() {
        Onboarding.markDone(settings.prefs, Onboarding.MAIN)
        Onboarding.markCopied(settings.prefs, listOf(Onboarding.ANILIST, Onboarding.VIDEOS, Onboarding.COLOURING))
        var done = 0
        StepNav(settings, { Onboarding.DOWNLOADS }, {}, { done++ }).advance(Onboarding.DOWNLOADS)
        assertEquals(1, done)
        assertTrue(Startup.onboarded(settings.prefs.all))
        assertTrue(Onboarding.done(settings.prefs).isEmpty())
    }
}
