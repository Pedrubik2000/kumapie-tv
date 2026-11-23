package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
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
class TitleCommitTest {
    @get:Rule val rule = createComposeRule()

    private val sent = ArrayList<String>()
    private val stepper = Stepper(STATUS_STEPS, "CURRENT") { sent += it }
    private val row = FocusRequester()
    private val other = FocusRequester()
    private var present by mutableStateOf(true)

    private fun host() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            if (present) Column {
                CommitSoon(stepper)
                StepRow("Status", statusLabel(stepper.shown), Modifier.focusRequester(row), stepper::step, stepper::commit)
                Box(Modifier.size(20.dp).focusRequester(other).focusable())
            }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.runOnIdle { row.requestFocus() }
        rule.mainClock.advanceTimeByFrame()
    }

    private fun step(by: Int) {
        rule.runOnIdle { stepper.step(by) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
    }

    @Test fun sendsOnceAfterAQuietSecondAndAHalfAndOnlyTheLastValue() {
        host()
        step(1)
        rule.mainClock.advanceTimeBy(1_000)
        step(1)
        rule.mainClock.advanceTimeBy(1_400)
        assertEquals(emptyList<String>(), sent)
        rule.mainClock.advanceTimeBy(200)
        assertEquals(listOf("PAUSED"), sent)
        rule.mainClock.advanceTimeBy(5_000)
        assertEquals(listOf("PAUSED"), sent)
    }

    @Test fun sendsNothingWhenSteppedBackToTheStart() {
        host()
        step(1)
        step(-1)
        rule.mainClock.advanceTimeBy(5_000)
        assertEquals(emptyList<String>(), sent)
    }

    @Test fun leavingTheRowSendsAtOnce() {
        host()
        step(1)
        val before = rule.mainClock.currentTime
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.mainClock.advanceTimeByFrame()
        assertTrue(rule.mainClock.currentTime - before < 1_500)
        assertEquals(listOf("COMPLETED"), sent)
        rule.mainClock.advanceTimeBy(5_000)
        assertEquals(listOf("COMPLETED"), sent)
    }

    @Test fun theScreenGoingAwaySendsTheLastValueOnceWhenReady() {
        val edits = TitleEdits({ sent += it }, {})
        edits.rebase(CardData("CURRENT", 0.0, 1, 3, 0), "POINT_10")
        edits.status.step(1)
        rule.mainClock.autoAdvance = false
        rule.setContent { if (present) CommitOnExit(edits, true) }
        rule.mainClock.advanceTimeByFrame()
        present = false
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        assertEquals(listOf("COMPLETED"), sent)
    }

    @Test fun theScreenGoingAwaySendsNothingWhenTheReadIsNotReady() {
        val edits = TitleEdits({ sent += it }, {})
        edits.rebase(CardData("CURRENT", 0.0, 1, 3, 0), "POINT_10")
        edits.status.step(1)
        rule.mainClock.autoAdvance = false
        rule.setContent { if (present) CommitOnExit(edits, false) }
        rule.mainClock.advanceTimeByFrame()
        present = false
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        assertEquals(emptyList<String>(), sent)
    }
}
