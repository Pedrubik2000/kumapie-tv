package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Chromecast walk 2026-11-24: texts that wrapped where one line was meant. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television-xhdpi")
class OneLineTextTest {
    @get:Rule val rule = createComposeRule()

    @Test fun aFilterLabelStaysOnOneLineWithALongValue() {
        rule.setContent { Box(Modifier.width(210.dp)) { StepRow("My list", "Only Planning", Modifier, {}, {}) } }
        val h = rule.onNodeWithText("My list", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
        assertTrue("label height $h", h < rule.density.run { 28.dp.toPx() })
    }

    @Test fun aDuelTitleNeverBreaksInsideAWord() {
        rule.setContent { Box(Modifier.width(150.dp)) { FitTitle("PSYCHO-PASS サイコパス", 24, 2) } }
        rule.waitForIdle()
        rule.onNodeWithText("PSYCHO-PASS サイコパス").assertExists()
        val h = rule.onNodeWithText("PSYCHO-PASS サイコパス").fetchSemanticsNode().boundsInRoot.height
        assertTrue("two lines at most: $h", h <= rule.density.run { 62.dp.toPx() })
    }
}
