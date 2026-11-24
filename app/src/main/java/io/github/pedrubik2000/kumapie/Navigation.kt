package io.github.pedrubik2000.kumapie

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key

internal fun MutableList<Screen>.goTop(next: Screen) {
    while (size > 1) removeAt(lastIndex)
    add(next)
}

@Composable
internal fun <T> StackEntry(screen: Screen, content: @Composable () -> T): T = key(System.identityHashCode(screen)) { content() }
