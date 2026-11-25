package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.pedrubik2000.kumapie.lang.DeviceSync
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AllowPromptTest {
    @get:Rule val rule = createComposeRule()

    private val a = DeviceSync.Asking("Tablet", "s3cret", "pubkey")

    @After fun reset() { DeviceSync.asking.value = emptyList(); DeviceSync.conflicts.value = emptySet() }

    @Test fun askingShowsCodeNotSecretAndAllowCallsAllow() {
        DeviceSync.asking.value = listOf(a)
        rule.setContent { AllowPrompt(hold = false, onSettings = {}) }
        rule.onNodeWithText("Tablet wants to pair. Code ${a.code}.").assertIsDisplayed()
        rule.onNodeWithText("s3cret", substring = true).assertDoesNotExist()
        rule.onNodeWithText("pubkey", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Allow").performClick()
        rule.waitForIdle()
        assertTrue(DeviceSync.asking.value.isEmpty())
    }

    @Test fun anAskTheClientStoppedPollingNeverShowsAndOneThatGoesStaleClosesByItself() {
        var t = 1_000_000L
        val gone = DeviceSync.Asking("Gone", "x", "y").apply { seen = t - 30_000 }
        val going = DeviceSync.Asking("Tablet", "s3cret", "pubkey").apply { seen = t - 19_500 }
        DeviceSync.asking.value = listOf(gone, going)
        rule.setContent { AllowPrompt(hold = false, onSettings = {}, clock = { t }) }
        rule.waitForIdle()
        rule.onNodeWithText("Gone wants", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Tablet wants to pair. Code ${going.code}.").assertIsDisplayed()
        t += 1_000
        rule.mainClock.advanceTimeBy(5_100)
        rule.onNodeWithText("Tablet wants", substring = true).assertDoesNotExist()
    }

    @Test fun notNowHidesThatRequestEvenWhenItIsPolledAgainAndANewDeviceShows() {
        DeviceSync.asking.value = listOf(a)
        rule.setContent { AllowPrompt(hold = false, onSettings = {}, paceMs = 0) }
        rule.onNodeWithText("Not now").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Allow").assertDoesNotExist()
        assertEquals(listOf(a), DeviceSync.asking.value)
        DeviceSync.asking.value = listOf(DeviceSync.Asking("Tablet", "s3cret", "pubkey"))
        rule.waitForIdle()
        rule.onNodeWithText("Allow").assertDoesNotExist()
        val b = DeviceSync.Asking("Phone", "other", "pub2")
        DeviceSync.asking.value = DeviceSync.asking.value + b
        rule.waitForIdle()
        rule.onNodeWithText("Phone wants to pair. Code ${b.code}.").assertIsDisplayed()
    }

    @Test fun aSecondRequestWaitsTenSecondsAfterTheFirst() {
        val b = DeviceSync.Asking("Phone", "other", "pub2")
        DeviceSync.asking.value = listOf(a, b)
        rule.setContent { AllowPrompt(hold = false, onSettings = {}) }
        rule.onNodeWithText("Not now").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Allow").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(10_100)
        rule.onNodeWithText("Phone wants to pair. Code ${b.code}.").assertIsDisplayed()
    }

    @Test fun nothingShowsWhileThePlayerIsUpAndItComesAfterItCloses() {
        DeviceSync.asking.value = listOf(a)
        var hold by mutableStateOf(true)
        rule.setContent { AllowPrompt(hold = hold, onSettings = {}) }
        rule.waitForIdle()
        rule.onNodeWithText("Allow").assertDoesNotExist()
        DeviceSync.conflicts.value = setOf("Tablet")
        rule.waitForIdle()
        rule.onNodeWithText("OK").assertDoesNotExist()
        hold = false
        rule.waitForIdle()
        rule.onNodeWithText("OK").assertIsDisplayed()
    }

    @Test fun conflictShowsItsLineAndOkDismisses() {
        DeviceSync.conflicts.value = setOf("Tablet")
        rule.setContent { AllowPrompt(hold = false, onSettings = {}) }
        rule.onNodeWithText(DeviceSync.conflictLine("Tablet")).assertIsDisplayed()
        rule.onNodeWithText("OK").performClick()
        rule.waitForIdle()
        assertTrue(DeviceSync.conflicts.value.isEmpty())
    }

    private fun four() = (1..4).map { DeviceSync.Asking("Dev$it", "sec$it", "pub$it") }

    @Test fun manyRequestsShowOneSettingsPromptNotACycleAndOpenSettingsGoesThere() {
        DeviceSync.asking.value = four()
        var opened = 0
        rule.setContent { AllowPrompt(hold = false, onSettings = { opened++ }) }
        rule.onNodeWithText("Several devices are asking to pair. Check the codes in Settings › Sync.").assertIsDisplayed()
        rule.onNodeWithText("Allow").assertDoesNotExist()
        rule.onNodeWithText("Open Settings").performClick()
        rule.waitForIdle()
        assertEquals(1, opened)
        rule.onNodeWithText("Several devices are asking to pair. Check the codes in Settings › Sync.").assertDoesNotExist()
        rule.onNodeWithText("Allow").assertDoesNotExist()
        assertEquals(4, DeviceSync.asking.value.size)
    }

    @Test fun threeRequestsAreStillOneByOne() {
        DeviceSync.asking.value = four().take(3)
        rule.setContent { AllowPrompt(hold = false, onSettings = {}) }
        rule.onNodeWithText("Allow").assertIsDisplayed()
    }
}
