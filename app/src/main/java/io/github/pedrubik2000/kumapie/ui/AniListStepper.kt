package io.github.pedrubik2000.kumapie.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.pedrubik2000.kumapie.data.AniList
import io.github.pedrubik2000.kumapie.i18n.tr
import kotlin.math.abs

class Stepper<T : Any>(private val steps: List<T>, start: T?, private val send: (T) -> Unit) {
    var shown by mutableStateOf(start)
        private set
    private var sent = start

    fun step(by: Int) {
        val at = steps.indexOf(shown)
        if (at >= 0) shown = steps[(at + by).coerceIn(steps.indices)] else if (by > 0) shown = steps.firstOrNull()
    }

    fun commit() {
        val value = shown ?: return
        if (value == sent) return
        sent = value
        send(value)
    }
}

val STATUS_STEPS = listOf("PLANNING", "CURRENT", "COMPLETED", "PAUSED", "DROPPED")

fun statusStart(status: String?): String? = if (status == "REPEATING") "CURRENT" else status

fun statusLabel(status: String?): String = when (status) {
    "PLANNING" -> tr("Planning")
    "CURRENT", "REPEATING" -> tr("Watching")
    "COMPLETED" -> tr("Completed")
    "PAUSED" -> tr("Paused")
    "DROPPED" -> tr("Dropped")
    else -> tr("Not on your list")
}

fun scoreSteps(format: String): List<Double> =
    if (format == "POINT_10_DECIMAL") (0..100).map { it / 10.0 } else (0..AniList.maxScore(format)).map { it.toDouble() }

fun scoreStart(steps: List<Double>, score: Double): Double = steps.minBy { abs(it - score) }

fun scoreLabel(score: Double, format: String): String = if (score <= 0.0) tr("no score") else AniList.showScore(score, format)
