package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performKeyPress
import androidx.tv.material3.Text
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The modifier of the menus opened by holding OK (Home's Continue menu, the episode Mark menu). */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television-xhdpi")
class HeldOkTest {
    @get:Rule val rule = createComposeRule()
    private var clicks = 0

    private fun show() {
        val focus = FocusRequester()
        rule.setContent {
            androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
            Box(Modifier.swallowHeldOk()) { Button(onClick = { clicks++ }, modifier = Modifier.focusRequester(focus)) { Text("Go") } }
        }
        rule.waitForIdle()
    }

    @Test fun theReleaseOfTheKeyThatOpenedTheMenuClicksNothing() {
        show()
        val up = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_CENTER) // the held key was pressed before the menu existed
        rule.onRoot().performKeyPress(androidx.compose.ui.input.key.KeyEvent(up))
        rule.waitForIdle()
        assertEquals(0, clicks)
    }

    @Test fun aFreshPressClicks() {
        show()
        rule.onRoot().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(1, clicks)
    }
}
