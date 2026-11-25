package io.github.pedrubik2000.kumapie.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.pedrubik2000.kumapie.data.Onboarding
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.lang.DeviceSync
import io.github.pedrubik2000.kumapie.lang.Pairing
import io.github.pedrubik2000.kumapie.lang.SetupBundle
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h720dp-television-xhdpi")
class TvOnboardingFitTest {
    @get:Rule val rule = createComposeRule()

    private val settings get() = Settings(RuntimeEnvironment.getApplication())

    private class Nobody : Pairing {
        override suspend fun discover(ms: Long) = emptyList<Pair<String, String>>()
        override suspend fun usePc(url: String) = "no"
        override suspend fun ask(name: String, address: String) = DeviceSync.Asked("1")
        override suspend fun allowed() = DeviceSync.Poll.WAITING
        override suspend fun fetch() = SetupBundle.Got.Old
        override fun apply(got: SetupBundle.Got.Ok, ticked: Set<String>) = Unit
        override fun peer() = null
        override fun hasKey() = false
        override fun me() = "TV"
        override fun forget() = Unit
    }

    @Before fun fresh() {
        Onboarding.reset(settings.prefs)
        settings.prefs.edit().remove("onboarded").remove("sync_with").apply()
    }

    private fun at(vararg done: String) {
        done.forEach { Onboarding.markDone(settings.prefs, it) }
        rule.setContent { TvOnboarding(settings, Nobody()) {} }
        rule.waitForIdle()
    }

    @Test fun mainServerShowsItsTitleAndButtonsWithoutScrolling() {
        at("language")
        rule.onNodeWithText("Set up from your main device or PC?").assertIsDisplayed()
        rule.onNodeWithText("Start fresh").assertIsDisplayed()
        rule.onNodeWithText("this is my first device").assertIsDisplayed()
        rule.onNodeWithText("Pair and copy").assertDoesNotExist()
    }

    @Test fun aniListFitsWithFocusOnContinue() {
        at("language", "main")
        rule.onNodeWithText("Home, Library and Discover come from your AniList anime list.").assertIsDisplayed()
        rule.onNodeWithText("Scan this with your phone's camera", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Continue ›").assertIsDisplayed()
    }

    @Test fun videosFits() {
        at("language", "main", "anilist")
        rule.onNodeWithText("Videos & subtitles").assertIsDisplayed()
        rule.onNodeWithText("Real-Debrid (paid) streams", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Scan this with your phone's camera", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Continue ›").assertIsDisplayed()
        rule.onNodeWithText("Skip").assertIsDisplayed()
    }
}
