package io.github.pedrubik2000.kumapie.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.update.Updater
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Bug hunt 2026-11-24: the update dialog cut its notes and lost its buttons; Pair was out of the D-pad's reach. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television-xhdpi")
class TvUpdateAndPairTest {
    @get:Rule val rule = createComposeRule()

    private fun press(k: Key) { rule.onNode(isFocused()).performKeyInput { pressKey(k) }; rule.waitForIdle() }
    private fun focused() = rule.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()?.config
        ?.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text

    @Test fun longNotesScrollAndTheButtonsStayOnScreen() {
        val notes = (1..80).joinToString("\n") { "Change number $it of this release" }
        rule.setContent { UpdateDialog(Updater.Release("0.42.1", "http://x/apk", notes), onClose = {}) }
        rule.waitUntil(5_000) { focused() == "Update" }
        rule.onNodeWithText("Update").assertIsDisplayed()
        rule.onNodeWithText("Later").assertIsDisplayed()
        press(Key.DirectionRight)
        assertEquals("Later", focused())
        press(Key.DirectionLeft)
        press(Key.DirectionUp)
        assertEquals("Notes take focus above the buttons", true, focused()?.startsWith("Change number 1") == true)
        press(Key.DirectionDown) // scrolls the notes first
        assertEquals(true, focused()?.startsWith("Change number 1"))
        repeat(40) { press(Key.DirectionDown) }
        assertEquals("Update", focused())
    }

    @Test fun downAndRightFromTheAddressFieldReachPair() {
        rule.setContent { androidx.compose.foundation.layout.Column { TvSync(Settings(LocalContext.current)) } }
        rule.onNode(hasImeAction(androidx.compose.ui.text.input.ImeAction.Done)).requestFocus()
        press(Key.DirectionRight)
        assertEquals("Pair", focused())
        rule.onNode(hasImeAction(androidx.compose.ui.text.input.ImeAction.Done)).requestFocus()
        press(Key.DirectionDown)
        assertEquals("Pair", focused())
    }
}
