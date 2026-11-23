package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television-xhdpi")
class TvNotForMeTest {
    @get:Rule val rule = createComposeRule()

    private fun focused(): String? = rule.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(SemanticsProperties.Text)?.joinToString(" ")

    private val ok = Key.DirectionCenter.keyCode
    private val down = Key.DirectionDown.keyCode

    private fun press(vararg keys: Long) = keys.map(::Key).forEach { k -> rule.onRoot().performKeyInput { pressKey(k) }; rule.waitForIdle() }

    @Test fun afterARemovalFocusGoesToTheNextThenThePreviousThenToTheRowAbove() {
        val settings = Settings(RuntimeEnvironment.getApplication())
        PickStore(settings.prefs).apply { notForMe(1, "A"); notForMe(2, "B"); notForMe(3, "C") }
        rule.setContent { Column { Button(onClick = {}) { Text("Above") }; TvNotForMe(settings) } }
        rule.onNodeWithText("Not for me: 3 titles").requestFocus()
        press(ok, down)
        assertEquals("✕ A", focused())
        press(ok)
        assertEquals("✕ B", focused())
        press(down)
        assertEquals("✕ C", focused())
        press(ok)
        assertEquals("✕ B", focused())
        press(ok)
        assertEquals("Above", focused())
        assertEquals(emptyList<Pair<Int, String>>(), PickStore(settings.prefs).neverList())
    }
}
