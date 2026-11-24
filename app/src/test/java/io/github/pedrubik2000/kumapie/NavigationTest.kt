package io.github.pedrubik2000.kumapie

import io.github.pedrubik2000.kumapie.data.Found
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTest {
    private fun title(id: String) = Screen.Discover(Found("kitsu:$id", "series", "T$id", "", ""))

    @Test fun switchingBetweenLibraryAndDiscoverReplacesTheTopLevelScreen() {
        val stack = mutableListOf<Screen>(Screen.Home)
        stack.goTop(Screen.Library)
        stack.goTop(Screen.Discover())
        stack.goTop(Screen.Library)
        assertEquals(listOf(Screen.Home, Screen.Library), stack)
    }

    @Test fun fromHomeTheTopLevelScreenIsPushed() {
        val stack = mutableListOf<Screen>(Screen.Home)
        stack.goTop(Screen.Discover())
        assertEquals(listOf(Screen.Home, Screen.Discover()), stack)
    }

    @Test fun discoverFromATitlePageGoesToDiscoverAndBackIsHome() {
        val stack = mutableListOf(Screen.Home, Screen.Library, title("5"))
        stack.goTop(Screen.Discover())
        assertEquals(listOf(Screen.Home, Screen.Discover()), stack)
        stack.removeAt(stack.lastIndex)
        assertEquals(listOf<Screen>(Screen.Home), stack)
    }

    @Test fun aLongBounceNeverLeavesMoreThanHomeAndOneTopLevelScreenAndWhatWasOpenedFromIt() {
        val stack = mutableListOf<Screen>(Screen.Home)
        repeat(20) { i ->
            stack.goTop(Screen.Library)
            assertEquals(2, stack.size)
            stack.add(title("$i"))
            stack.goTop(Screen.Discover())
            assertEquals(2, stack.size)
            stack.add(title("x$i"))
            assertTrue(stack.size <= 3)
            stack.goTop(Screen.Library)
        }
        assertEquals(listOf(Screen.Home, Screen.Library), stack)
    }
}
