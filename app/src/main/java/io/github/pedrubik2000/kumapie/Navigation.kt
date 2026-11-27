package io.github.pedrubik2000.kumapie

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key

internal fun MutableList<Screen>.goTop(next: Screen) {
    while (size > 1) removeAt(lastIndex)
    add(next)
}

@Composable
internal fun <T> StackEntry(screen: Screen, content: @Composable () -> T): T = key(System.identityHashCode(screen)) { content() }

internal fun MutableList<Screen>.railNav(lang: String = "ja") = io.github.pedrubik2000.kumapie.ui.RailNav(
    onHome = { while (size > 1) removeAt(lastIndex) }, onLibrary = { goTop(Screen.Library) }, onDiscover = { goTop(Screen.Discover()) },
    onReview = { goTop(Screen.Review(lang)) }, onStats = { goTop(Screen.Stats) }, onSettings = { goTop(Screen.Settings) })
