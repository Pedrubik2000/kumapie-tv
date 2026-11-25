package io.github.pedrubik2000.kumapie.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import io.github.pedrubik2000.kumapie.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television-xhdpi")
class TvWordColouringTest {
    @get:Rule val rule = createComposeRule()

    @org.junit.After fun forgetTheLanguageKit() {
        listOf(io.github.pedrubik2000.kumapie.lang.LangKit::class.java, TvMining::class.java).forEach { c ->
            c.declaredFields.firstOrNull { it.name == "kit" }?.apply { isAccessible = true }?.set(if (c == TvMining::class.java) TvMining else null, null)
        }
    }

    private fun focused(): String? = rule.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()?.config?.let { c ->
        c.getOrNull(SemanticsProperties.Text)?.joinToString(" ") ?: c.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
    }

    private fun press(k: Key) {
        rule.onRoot().performKeyInput { pressKey(k) }
        rule.waitForIdle()
    }

    private fun open(settings: Settings, onboarding: Boolean = false, onBack: () -> Unit = {}) {
        val language = TvMining.languages(RuntimeEnvironment.getApplication(), settings).japanese
        rule.setContent { TvWordColouring(settings, language, onboarding, onBack, 4, 5, {}, {}) }
        rule.waitForIdle()
    }

    @Test fun opensOnTheSwitchAndOkTurnsColoursOffAndOn() {
        val settings = Settings(RuntimeEnvironment.getApplication()).apply { plainWords = false }
        open(settings)
        assertEquals("Colour words in subtitles", focused())
        press(Key.DirectionCenter)
        assertEquals(true, settings.plainWords)
        rule.onNodeWithText("Plain subtitles").assertExists()
        press(Key.DirectionCenter)
        assertEquals(false, settings.plainWords)
        rule.onNodeWithText("Known words come from".uppercase()).assertExists()
    }

    @Test fun downFromTheSwitchWalksTheRadiosAndRightGoesToTheSources() {
        open(Settings(RuntimeEnvironment.getApplication()).apply { plainWords = false })
        assertEquals("Colour words in subtitles", focused())
        press(Key.DirectionDown)
        assertEquals("Sync with a main server Known words and new cards come from the main kumapie on the home Wi-Fi", focused())
        press(Key.DirectionDown)
        assertEquals("This device is the main server Phone, tablet, TV kumapie and kumahon sync with it", focused())
        press(Key.DirectionRight)
        assertEquals(false, focused()?.contains("main server"))
        press(Key.DirectionLeft)
        assertEquals("Count AnkiDroid as known words", focused())
        press(Key.DirectionLeft)
        assertEquals(true, focused()?.let { it.contains("main server") || it.startsWith("Colour words") })
        press(Key.DirectionRight)
        rule.onNodeWithContentDescription("Count Most common words (Jiten) as known words").requestFocus()
        press(Key.DirectionLeft)
        assertEquals(true, focused()?.let { it.contains("main server") || it.startsWith("Colour words") })
    }

    @Test fun theChipsAreFocusableByTheirKeys() {
        open(Settings(RuntimeEnvironment.getApplication()).apply { plainWords = false })
        rule.onNodeWithText("5,000").requestFocus()
        press(Key.DirectionRight)
        assertEquals("10,000", focused())
    }

    @Test fun backFromTheScreenReturnsToSettingsOnTheButton() {
        rule.setContent { SettingsScreen(Settings(RuntimeEnvironment.getApplication()), firstRun = false, onSaved = {}, onUpdate = {}) }
        rule.onNodeWithText("Word colouring: on", substring = true).requestFocus()
        press(Key.DirectionCenter)
        rule.onNodeWithText("Colour words in subtitles").assertExists()
        press(Key.Back)
        rule.waitForIdle()
        assertEquals(true, focused()?.startsWith("Word colouring: "))
    }

    @Test fun afterKeepFocusIsBackOnTheRadioAndBackCallsOnBackOnce() {
        var backs = 0
        val settings = Settings(RuntimeEnvironment.getApplication()).apply {
            plainWords = false
            prefs.edit().putBoolean(io.github.pedrubik2000.kumapie.lang.DeviceSync.ANSWERS, true).apply()
        }
        open(settings) { backs++ }
        rule.onNodeWithText("Sync with a main server").requestFocus()
        press(Key.DirectionCenter)
        rule.onNodeWithText("Keep").requestFocus()
        press(Key.DirectionCenter)
        assertEquals(true, focused()?.startsWith("This device is the main server"))
        press(Key.Back)
        assertEquals(1, backs)
    }

    @Test fun anAnkiRowOpensWithOk() {
        open(Settings(RuntimeEnvironment.getApplication()).apply { plainWords = false })
        rule.onNodeWithText("kuma3").requestFocus()
        press(Key.DirectionCenter)
        rule.onNodeWithText("Install it on this device to use it.").assertExists()
    }

    @Test fun backLeavesAndInOnboardingNotNowMakesTheSubtitlesPlain() {
        var backs = 0
        val settings = Settings(RuntimeEnvironment.getApplication()).apply { plainWords = false }
        open(settings, onboarding = true) { backs++ }
        rule.onNodeWithText("Step 4 of 5 · Optional".uppercase()).assertExists()
        rule.onNodeWithText("Not now").requestFocus()
        press(Key.DirectionCenter)
        assertEquals(true, settings.plainWords)
        rule.onNodeWithText("‹ Back").requestFocus()
        press(Key.DirectionCenter)
        assertEquals(1, backs)
    }
}
