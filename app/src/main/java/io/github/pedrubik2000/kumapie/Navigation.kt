package io.github.pedrubik2000.kumapie

internal fun MutableList<Screen>.goTop(next: Screen) {
    val top = lastOrNull()
    if (top == Screen.Library || (top is Screen.Discover && top.open == null)) removeAt(lastIndex)
    add(next)
}
