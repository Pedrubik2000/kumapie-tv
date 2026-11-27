package io.github.pedrubik2000.kumapie

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key

internal fun MutableList<Screen>.goTop(next: Screen) {
    while (size > 1) removeAt(lastIndex)
    add(next)
}

@Composable
internal fun <T> StackEntry(screen: Screen, content: @Composable () -> T): T = key(System.identityHashCode(screen)) { content() }

/** The side rail's targets: each top-level screen replaces the others, so Back from any of them is Home. */
internal fun MutableList<Screen>.railNav(lang: String = "ja") = io.github.pedrubik2000.kumapie.ui.RailNav(
    onHome = { while (size > 1) removeAt(lastIndex) }, onLibrary = { goTop(Screen.Library) }, onDiscover = { goTop(Screen.Discover()) },
    onReview = { goTop(Screen.Review(lang)) }, onStats = { goTop(Screen.Stats) }, onSettings = { goTop(Screen.Settings) })

/** Settings opened before a profile is picked: every rail item goes Home (the profile list), as there is no profile for the other pages yet. */
internal fun MutableList<Screen>.homeNav() = railNav().let { n ->
    val home = n.onHome
    io.github.pedrubik2000.kumapie.ui.RailNav(home, home, home, home, home, home)
}
