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
import io.github.pedrubik2000.kumapie.lang.DeviceSync
import org.junit.After
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

    private val a = DeviceSync.Asking("Tablet", "s3cret", "pubkey")

    @After fun reset() { DeviceSync.asking.value = emptyList(); DeviceSync.conflicts.value = emptySet() }

    private fun focused(): String? = rule.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(SemanticsProperties.Text)?.joinToString(" ")
    private fun press(vararg keys: Long) = keys.map(::Key).forEach { k -> rule.onAllNodes(isRoot()).onLast().performKeyInput { pressKey(k) }; rule.waitForIdle() }

    @Test fun askingShowsTheCodeNotTheSecretFocusesNotNowAndAllowAllows() {
        DeviceSync.asking.value = listOf(a)
        rule.setContent { TvAllowPrompt(hold = false, onSettings = {}, guardMs = 0) }
        rule.waitForIdle()
        rule.onNodeWithText("Tablet wants to pair. Code ${a.code}.").assertIsDisplayed()
        rule.onNodeWithText("s3cret", substring = true).assertDoesNotExist()
        rule.onNodeWithText("pubkey", substring = true).assertDoesNotExist()
        assertEquals("Not now", focused())
        press(Key.DirectionLeft.keyCode, Key.DirectionCenter.keyCode)
        assertTrue(DeviceSync.asking.value.isEmpty())
    }

    @Test fun backIsNotNowAndFocusReturnsToTheScreenBehind() {
        rule.setContent { Column { Button(onClick = {}) { Text("Behind") }; TvAllowPrompt(hold = false, onSettings = {}) } }
        rule.onNodeWithText("Behind").requestFocus()
        DeviceSync.asking.value = listOf(a)
        rule.waitForIdle()
        rule.onNodeWithText("Allow").assertIsDisplayed()
        press(Key.Back.keyCode)
        rule.onNodeWithText("Allow").assertDoesNotExist()
        assertEquals("Behind", focused())
        assertEquals(listOf(a), DeviceSync.asking.value)
    }

    @Test fun aQuickOkRightAfterItOpensDoesNothing() {
        DeviceSync.asking.value = listOf(a)
        rule.setContent { TvAllowPrompt(hold = false, onSettings = {}, guardMs = 60_000) }
        rule.waitForIdle()
        press(Key.DirectionLeft.keyCode, Key.DirectionCenter.keyCode, Key.Enter.keyCode)
        rule.onNodeWithText("Allow").assertIsDisplayed()
        assertEquals(listOf(a), DeviceSync.asking.value)
    }

    @Test fun nothingShowsWhileThePlayerIsUpAndItComesAfterItCloses() {
        DeviceSync.asking.value = listOf(a)
        var hold by mutableStateOf(true)
        rule.setContent { TvAllowPrompt(hold = hold, onSettings = {}) }
        rule.waitForIdle()
        rule.onNodeWithText("Allow").assertDoesNotExist()
        hold = false
        rule.waitForIdle()
        rule.onNodeWithText("Allow").assertIsDisplayed()
    }

    @Test fun notNowStaysHiddenOnAPollAgainAndTheNextRequestWaitsTenSeconds() {
        val b = DeviceSync.Asking("Phone", "other", "pub2")
        DeviceSync.asking.value = listOf(a)
        rule.setContent { TvAllowPrompt(hold = false, onSettings = {}, guardMs = 0) }
        rule.waitForIdle()
        press(Key.DirectionCenter.keyCode)
        DeviceSync.asking.value = listOf(DeviceSync.Asking("Tablet", "s3cret", "pubkey"), b)
        rule.waitForIdle()
        rule.onNodeWithText("Allow").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(10_100)
        rule.onNodeWithText("Phone wants to pair. Code ${b.code}.").assertIsDisplayed()
    }

    @Test fun conflictShowsItsLineAndOkDismisses() {
        DeviceSync.conflicts.value = setOf("Tablet")
        rule.setContent { TvAllowPrompt(hold = false, onSettings = {}, guardMs = 0) }
        rule.waitForIdle()
        rule.onNodeWithText(DeviceSync.conflictLine("Tablet")).assertIsDisplayed()
        assertEquals("OK", focused())
        press(Key.DirectionCenter.keyCode)
        assertTrue(DeviceSync.conflicts.value.isEmpty())
    }

    @Test fun manyRequestsShowOneSettingsPromptWithOkFocusedAndOpenSettingsGoesThere() {
        DeviceSync.asking.value = (1..4).map { DeviceSync.Asking("Dev$it", "sec$it", "pub$it") }
        var opened = 0
        rule.setContent { TvAllowPrompt(hold = false, onSettings = { opened++ }, guardMs = 0) }
        rule.waitForIdle()
        rule.onNodeWithText("Several devices are asking to pair. Check the codes in Settings › Sync.").assertIsDisplayed()
        rule.onNodeWithText("Allow").assertDoesNotExist()
        assertEquals("OK", focused())
        press(Key.DirectionLeft.keyCode, Key.DirectionCenter.keyCode)
        assertEquals(1, opened)
        rule.onNodeWithText("Open Settings").assertDoesNotExist()
        assertEquals(4, DeviceSync.asking.value.size)
    }
}
