package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.pedrubik2000.kumapie.lang.DeviceSync
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

    private val a = DeviceSync.Asking("Tablet", "s3cret", "pub")

    @Test fun askingShowsCodeNotSecretAndAllowCallsAllow() {
        DeviceSync.asking.value = listOf(a)
        rule.setContent { AllowPrompt() }
        rule.onNodeWithText("Tablet wants to pair. Code ${a.code}.").assertIsDisplayed()
        rule.onNodeWithText("Allow").performClick()
        rule.waitForIdle()
        assertTrue(DeviceSync.asking.value.isEmpty()) // allow() takes it off the list
    }

    @Test fun notNowHidesThatRequest() {
        DeviceSync.asking.value = listOf(a)
        rule.setContent { AllowPrompt() }
        rule.onNodeWithText("Not now").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Allow").assertDoesNotExist()
        assertEquals(listOf(a), DeviceSync.asking.value)
        DeviceSync.asking.value = emptyList()
    }

    @Test fun conflictShowsItsLineAndOkDismisses() {
        DeviceSync.conflicts.value = setOf("Tablet")
        rule.setContent { AllowPrompt() }
        rule.onNodeWithText(DeviceSync.conflictLine("Tablet")).assertIsDisplayed()
        rule.onNodeWithText("OK").performClick()
        rule.waitForIdle()
        assertTrue(DeviceSync.conflicts.value.isEmpty())
    }
}
