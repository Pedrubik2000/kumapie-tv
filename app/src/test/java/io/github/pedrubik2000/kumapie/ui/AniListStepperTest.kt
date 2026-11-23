package io.github.pedrubik2000.kumapie.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AniListStepperTest {
    private val sent = ArrayList<String>()

    private fun status(start: String?) = Stepper(STATUS_STEPS, start) { sent += it }

    @Test fun statusesStepInTheMockupsOrderAndStopAtBothEnds() {
        val s = status("PLANNING")
        s.step(-1)
        assertEquals("PLANNING", s.shown)
        repeat(9) { s.step(1) }
        assertEquals("DROPPED", s.shown)
        assertEquals(listOf("PLANNING", "CURRENT", "COMPLETED", "PAUSED", "DROPPED"), STATUS_STEPS)
    }

    @Test fun aTitleOnNoListStartsEmptyAndTheFirstRightPutsItOnPlanning() {
        val s = status(null)
        s.step(-1)
        assertEquals(null, s.shown)
        s.step(1)
        assertEquals("PLANNING", s.shown)
        s.commit()
        assertEquals(listOf("PLANNING"), sent)
    }

    @Test fun nothingIsSentWhenTheValueIsBackAtTheStart() {
        val s = status("CURRENT")
        s.step(1)
        s.step(1)
        s.step(-1)
        s.step(-1)
        s.commit()
        assertEquals(emptyList<String>(), sent)
    }

    @Test fun onlyTheLastValueIsSentNotTheOnesSteppedPast() {
        val s = status("CURRENT")
        s.step(1)
        s.step(1)
        s.step(1)
        s.step(1)
        s.step(-1)
        s.commit()
        s.commit()
        assertEquals(listOf("PAUSED"), sent)
    }

    @Test fun goingBackToTheStartAfterASendSendsItAgain() {
        val s = status("CURRENT")
        s.step(1)
        s.commit()
        s.step(-1)
        s.commit()
        assertEquals(listOf("COMPLETED", "CURRENT"), sent)
    }

    @Test fun rewatchingStartsAsWatching() {
        assertEquals("CURRENT", statusStart("REPEATING"))
        assertEquals(null, statusStart(null))
    }

    @Test fun scoreStepsFollowTheScoreFormat() {
        assertEquals((0..100).map { it.toDouble() }, scoreSteps("POINT_100"))
        assertEquals((0..10).map { it.toDouble() }, scoreSteps("POINT_10"))
        assertEquals((0..5).map { it.toDouble() }, scoreSteps("POINT_5"))
        assertEquals(listOf(0.0, 1.0, 2.0, 3.0), scoreSteps("POINT_3"))
        val decimal = scoreSteps("POINT_10_DECIMAL")
        assertEquals(101, decimal.size)
        assertEquals(0.0, decimal.first(), 0.0)
        assertEquals(10.0, decimal.last(), 0.0)
        assertEquals(7.7, decimal[77], 0.0)
    }

    @Test fun scoresStopAtNoScoreAndAtTheTop() {
        val steps = scoreSteps("POINT_5")
        val out = ArrayList<Double>()
        val s = Stepper(steps, 0.0) { out += it }
        s.step(-1)
        assertEquals(0.0, s.shown!!, 0.0)
        repeat(9) { s.step(1) }
        assertEquals(5.0, s.shown!!, 0.0)
        s.commit()
        assertEquals(listOf(5.0), out)
    }

    @Test fun aScoreOfTheListStartsOnTheNearestStep() {
        assertEquals(7.7, scoreStart(scoreSteps("POINT_10_DECIMAL"), 7.7), 0.0)
        assertEquals(8.0, scoreStart(scoreSteps("POINT_10"), 7.9), 0.0)
        assertEquals(0.0, scoreStart(scoreSteps("POINT_100"), 0.0), 0.0)
    }
}
