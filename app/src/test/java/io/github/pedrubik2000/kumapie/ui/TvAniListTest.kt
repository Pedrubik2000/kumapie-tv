package io.github.pedrubik2000.kumapie.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import io.github.pedrubik2000.kumapie.data.AniListAccount
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.Tr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television-xhdpi")
class TvAniListTest {
    @get:Rule val rule = createComposeRule()
    private val settings get() = Settings(RuntimeEnvironment.getApplication())

    private fun focused(): String? = rule.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()?.config
        ?.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text

    private fun press(k: Key) { rule.onRoot().performKeyInput { pressKey(k) }; rule.waitForIdle() }

    private fun open() = rule.setContent { SettingsScreen(Settings(LocalContext.current), firstRun = false, onSaved = {}, onUpdate = {}) }

    @Test fun stateLineSaysLoggedInLoggedOutOrExpired() {
        assertEquals("Not logged in", AniListAccount.summary("", "", false))
        assertEquals("Login expired: log in again", AniListAccount.summary("", "pedro", true))
        assertEquals("Logged in as pedro", AniListAccount.summary("t", "pedro", false))
        assertEquals("Logged in", AniListAccount.summary("t", "", false))
        Tr.use("es")
        try { assertEquals("Sesión iniciada como pedro", AniListAccount.summary("t", "pedro", false)) } finally { Tr.use("en") }
    }

    @Test fun theSectionIsFirstAndLogInIsWhereTheFocusStarts() {
        open()
        rule.waitUntil(5_000) { focused() == "Log in with AniList" }
        rule.onNodeWithText("Not logged in").assertExists()
        rule.onNodeWithText(AniListAccount.why()).assertExists()
        val top = { t: String -> rule.onNodeWithText(t).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(top("AniList") < top("Server"))
        assertTrue(top("Settings") < top("AniList"))
    }

    @Test fun logInShowsTheQrPageWithOnlyTheAniListPart() {
        open()
        rule.waitUntil(5_000) { focused() == "Log in with AniList" }
        press(Key.DirectionCenter)
        rule.onNodeWithText("Hide the QR code").assertExists()
        rule.onNodeWithText("Log in to AniList from your phone").assertExists()
        rule.onNodeWithText("There: the AniList login link and the token field.").assertExists()
        assertTrue(rule.onAllNodesWithTextCount("Set up kumapie from your phone") == 0)
    }

    @Test fun expiredLoginSaysSo() {
        settings.prefs.edit().putBoolean("anilist_logged_out", true).commit()
        open()
        rule.onNodeWithText("Login expired: log in again").assertExists()
    }

    @Test fun logOutAsksFirstAndOnlyClearsKumapiesToken() {
        settings.anilist = "pedro"
        settings.anilistToken = "t"
        open()
        rule.onNodeWithText("Logged in as pedro").assertExists()
        rule.waitUntil(5_000) { focused() == "Log out" }
        press(Key.DirectionCenter)
        rule.onNodeWithText("Yes, log out").assertExists()
        rule.onNodeWithText("Keep me logged in").assertExists()
        assertEquals("t", settings.anilistToken)
        press(Key.DirectionRight)
        assertEquals("Keep me logged in", focused())
        press(Key.DirectionCenter)
        rule.onNodeWithText("Log out").assertExists()
        rule.onNodeWithText("Logged in as pedro").assertExists()
        assertEquals("t", settings.anilistToken)
        rule.onNodeWithText("Log out").requestFocus()
        press(Key.DirectionCenter)
        rule.onNodeWithText("Yes, log out").requestFocus()
        press(Key.DirectionCenter)
        rule.onNodeWithText("Not logged in").assertExists()
        rule.onNodeWithText("Log in with AniList").assertExists()
        assertEquals("", settings.anilistToken)
        assertEquals("pedro", settings.anilist)
    }

    private fun ComposeContentTestRule.onAllNodesWithTextCount(t: String) = onAllNodes(androidx.compose.ui.test.hasText(t)).fetchSemanticsNodes().size
}
