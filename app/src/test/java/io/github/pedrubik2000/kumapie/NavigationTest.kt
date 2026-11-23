package io.github.pedrubik2000.kumapie

import io.github.pedrubik2000.kumapie.data.Found
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationTest {
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

    @Test fun aTitlePageStaysUnderTheScreenOpenedFromItsRail() {
        val title = Screen.Discover(Found("kitsu:5", "series", "Alpha", "", ""))
        val stack = mutableListOf(Screen.Home, Screen.Library, title)
        stack.goTop(Screen.Library)
        assertEquals(listOf(Screen.Home, Screen.Library, title, Screen.Library), stack)
    }
}
