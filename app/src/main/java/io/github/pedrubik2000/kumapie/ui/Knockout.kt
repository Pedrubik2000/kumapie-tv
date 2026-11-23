package io.github.pedrubik2000.kumapie.ui

internal class Knockout private constructor(
    val entrants: List<Int>, private val alive: List<Int>, private val won: List<Int>, private val at: Int, val round: Int, val dropped: Set<Int>,
) {
    constructor(entrants: List<Int>) : this(entrants, entrants, emptyList(), 0, 1, emptySet()) {
        require(entrants.size >= 2)
    }

    val rounds: Int = generateSequence(entrants.size) { (it + 1) / 2 }.takeWhile { it > 1 }.count()

    val winner: Int? get() = alive.singleOrNull()

    val pair: Pair<Int, Int>? get() = if (alive.size < 2) null else alive[at * 2] to alive[at * 2 + 1]

    val left: Int get() = entrants.size - dropped.size

    fun choose(first: Boolean): Knockout {
        val (a, b) = pair ?: return this
        return Knockout(entrants, alive, won + if (first) a else b, at + 1, round, dropped + if (first) b else a).settled()
    }

    private fun settled(): Knockout {
        var a = alive
        var w = won
        var r = round
        var i = at
        while (a.size > 1 && i * 2 >= a.size - a.size % 2) {
            if (a.size % 2 == 1) w = w + a.last()
            a = w
            w = emptyList()
            i = 0
            r++
        }
        return Knockout(entrants, a, w, i, r, dropped)
    }
}
