package io.github.pedrubik2000.kumapie.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import io.github.pedrubik2000.kumapie.lang.DeviceSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television-xhdpi")
class TvAllowPromptTest {
    @get:Rule val rule = createComposeRule()

    private val a = DeviceSync.Asking("Tablet", "s3cret", "pub")

    private fun focused(): String? = rule.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(SemanticsProperties.Text)?.joinToString(" ")
    private fun press(vararg keys: Long) = keys.map(::Key).forEach { k -> rule.onAllNodes(isRoot()).onLast().performKeyInput { pressKey(k) }; rule.waitForIdle() }

    @Test fun askingShowsTheCodeTakesFocusOnAllowAndOkAllows() {
        DeviceSync.asking.value = listOf(a)
        rule.setContent { TvAllowPrompt() }
        rule.waitForIdle()
        rule.onNodeWithText("Tablet wants to pair. Code ${a.code}.").assertIsDisplayed()
        assertEquals("Allow", focused())
        press(Key.DirectionRight.keyCode, Key.DirectionLeft.keyCode, Key.DirectionCenter.keyCode)
        assertTrue(DeviceSync.asking.value.isEmpty())
    }

    @Test fun rightThenOkIsNotNowAndTheRequestStaysButHides() {
        DeviceSync.asking.value = listOf(a)
        rule.setContent { TvAllowPrompt() }
        rule.waitForIdle()
        press(Key.DirectionRight.keyCode)
        assertEquals("Not now", focused())
        press(Key.DirectionCenter.keyCode)
        rule.onNodeWithText("Allow").assertDoesNotExist()
        assertEquals(listOf(a), DeviceSync.asking.value)
        DeviceSync.asking.value = emptyList()
    }

    @Test fun conflictShowsItsLineAndOkDismisses() {
        DeviceSync.conflicts.value = setOf("Tablet")
        rule.setContent { TvAllowPrompt() }
        rule.waitForIdle()
        rule.onNodeWithText(DeviceSync.conflictLine("Tablet")).assertIsDisplayed()
        assertEquals("OK", focused())
        press(Key.DirectionCenter.keyCode)
        assertTrue(DeviceSync.conflicts.value.isEmpty())
    }
}
