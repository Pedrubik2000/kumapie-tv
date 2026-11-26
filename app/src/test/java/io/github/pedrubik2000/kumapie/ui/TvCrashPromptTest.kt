package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.lang.Delivery
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television-xhdpi")
class TvCrashPromptTest {
    @get:Rule val rule = createComposeRule()

    private var marked = 0
    private var closed = 0
    private var sends = 0

    private fun focused(): String? = rule.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(SemanticsProperties.Text)?.joinToString(" ")
    private fun press(vararg keys: Long) = keys.map(::Key).forEach { k -> rule.onAllNodes(isRoot()).onLast().performKeyInput { pressKey(k) }; rule.waitForIdle() }
    private fun show(answer: String?, guardMs: Long = 0) = rule.setContent {
        TvCrashDialog(send = { sends++; Delivery(answer) }, mark = { marked++ }, close = { closed++ }, guardMs = guardMs)
    }

    @Test fun asksWithWhatIsSentAndFocusesSend() {
        show(null)
        rule.waitForIdle()
        rule.onNodeWithText("kumapie closed unexpectedly last time. Send the crash report?").assertIsDisplayed()
        rule.onNodeWithText("Sends the error, the device model and the app version — nothing else.").assertIsDisplayed()
        assertEquals("Send", focused())
    }

    @Test fun dpadRightReachesNotNowAndNotNowMarksAndCloses() {
        show(null)
        rule.waitForIdle()
        press(Key.DirectionRight.keyCode)
        assertEquals("Not now", focused())
        press(Key.DirectionCenter.keyCode)
        assertEquals(1, marked)
        assertEquals(1, closed)
        assertEquals(0, sends)
    }

    @Test fun backIsNotNow() {
        show(null)
        rule.waitForIdle()
        press(Key.Back.keyCode)
        assertEquals(1, marked)
        assertEquals(1, closed)
        assertEquals(0, sends)
    }

    @Test fun sendThatReachedThePcSaysSoAndMarksOnce() {
        show(null)
        rule.waitForIdle()
        press(Key.DirectionCenter.keyCode)
        rule.waitForIdle()
        rule.onNodeWithText("Sent to your kumapie PC.").assertIsDisplayed()
        assertEquals("OK", focused())
        assertEquals(1, sends)
        assertEquals(1, marked)
        press(Key.DirectionCenter.keyCode)
        assertEquals(1, closed)
    }

    @Test fun sendThatFindsNoPcShowsTheQrAndCloseIsFocused() {
        show("https://github.com/Pedrubik2000/kumapie-tv/issues/new?title=x&body=y")
        rule.waitForIdle()
        press(Key.DirectionCenter.keyCode)
        rule.waitForIdle()
        rule.onNodeWithText("Scan with your phone to open a ready-filled GitHub issue.").assertIsDisplayed()
        assertEquals("Close", focused())
        assertEquals(1, marked)
    }

    @Test fun aRefusedReportSaysSoBeforeTheQr() {
        rule.setContent { TvCrashDialog(send = { Delivery("https://github.com/x/y/issues/new?title=a", refused = true) }, mark = {}, close = {}, guardMs = 0) }
        rule.waitForIdle()
        press(Key.DirectionCenter.keyCode)
        rule.waitForIdle()
        rule.onNodeWithText("Your kumapie PC refused the report.").assertIsDisplayed()
        rule.onNodeWithText("Scan with your phone to open a ready-filled GitHub issue.").assertIsDisplayed()
    }

    @Test fun aQuickOkRightAfterItOpensDoesNothing() {
        show(null, guardMs = 60_000)
        rule.waitForIdle()
        press(Key.DirectionCenter.keyCode, Key.Enter.keyCode)
        rule.onNodeWithText("Send").assertIsDisplayed()
        assertEquals(0, sends)
    }

    @Test fun focusReturnsToTheScreenBehindAfterNotNow() {
        var open by mutableStateOf(true)
        rule.setContent {
            Column { Button(onClick = {}) { Text("Behind") }; if (open) TvCrashDialog(send = { Delivery() }, mark = {}, close = { open = false }, guardMs = 0) }
        }
        rule.onNodeWithText("Behind").requestFocus()
        rule.waitForIdle()
        press(Key.Back.keyCode)
        rule.onNodeWithText("Send").assertDoesNotExist()
        assertEquals("Behind", focused())
    }
}
