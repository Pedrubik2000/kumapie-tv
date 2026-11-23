package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.BestForYou
import io.github.pedrubik2000.kumapie.data.BestForYou.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BestForYouWhyTest {
    private fun item(id: Int, status: String, score: Double, vararg features: String, average: Int? = 80) =
        Item(id, true, status, score, "", average, features.toList())

    private val items = listOf(
        item(1, "COMPLETED", 9.0, "Fantasy", "Cooking"),
        item(2, "COMPLETED", 9.0, "Fantasy"),
        item(3, "DROPPED", 3.0, "Sports"),
        item(4, "COMPLETED", 8.0, "Comedy"),
        item(10, "PLANNING", 0.0, "Fantasy", "Sports", "Comedy", "Unseen"),
    )

    @Test fun theFeaturesThatPullUpComeFirstWithWhatTheyAreMadeOf() {
        val why = BestForYou.why(items, 10)
        assertEquals(listOf("Fantasy", "Comedy"), why.map { it.feature })
        assertEquals(2, why[0].scored)
        assertEquals(9.0, why[0].average, 1e-9)
        assertEquals(2, why[0].finished)
        assertEquals(1, why[1].scored)
        assertEquals(8.0, why[1].average, 1e-9)
    }

    @Test fun aDroppedFeatureAndAnUnseenOneNeverExplainAPick() {
        val names = BestForYou.why(items, 10).map { it.feature }
        assertTrue("Sports" !in names)
        assertTrue("Unseen" !in names)
    }

    @Test fun topLimitsTheListAndAnUnknownTitleHasNone() {
        assertEquals(listOf("Fantasy"), BestForYou.why(items, 10, top = 1).map { it.feature })
        assertTrue(BestForYou.why(items, 99).isEmpty())
    }
}
