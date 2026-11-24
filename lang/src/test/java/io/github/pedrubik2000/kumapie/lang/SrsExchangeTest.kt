package io.github.pedrubik2000.kumapie.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SrsExchangeTest {
    @Test fun smallAnswerIsNotCut() {
        assertEquals(listOf(5, 5) to false, takeCapped(sequenceOf(5, 5), { it }, cap = 10))
    }

    @Test fun cutsOnceTheCapIsPassedButFinishesTheCardInHand() {
        assertEquals(listOf(8, 8) to true, takeCapped(sequenceOf(8, 8, 8), { it }, cap = 10))
    }

    @Test fun firstCardAlwaysGoesOut() {
        assertEquals(listOf(50) to true, takeCapped(sequenceOf(50, 1), { it }, cap = 10))
    }

    @Test fun noCardsNoMore() {
        assertEquals(emptyList<Int>() to false, takeCapped(emptySequence<Int>(), { it }, cap = 10))
    }

    private val mine = Mine(count = 3, modified = 100, maxId = 30)

    @Test fun callerIsAskedWhenKumapieLacksOrIsBehind() {
        assertTrue(callerShouldSend(null, Theirs(0, 1, null)))
        assertTrue(callerShouldSend(mine, Theirs(3, 101, 30)))
        assertTrue(callerShouldSend(mine, Theirs(4, 100, 40)))
    }

    @Test fun callerIsAskedWhenCountsMatchButLatestRatingDiffers() {
        assertTrue(callerShouldSend(mine, Theirs(3, 100, 31)))
        assertFalse(callerShouldSend(mine, Theirs(3, 100, null)))
        assertFalse(callerShouldSend(mine, Theirs(3, 100, 30)))
    }

    @Test fun callerIsNotAskedWhenKumapieIsAhead() {
        assertFalse(callerShouldSend(mine, Theirs(2, 99, 20)))
    }

    @Test fun kumapieSendsWhatTheCallerLacks() {
        assertTrue(sendCard(mine, null) && sendRevlog(mine, null))
        assertTrue(sendCard(mine, Theirs(3, 99, 30)))
        assertFalse(sendCard(mine, Theirs(3, 100, 30)))
        assertTrue(sendRevlog(mine, Theirs(2, 100, 20)))
        assertTrue(sendRevlog(mine, Theirs(3, 100, 29)))
        assertFalse(sendRevlog(mine, Theirs(3, 100, null)))
        assertFalse(sendRevlog(mine, Theirs(3, 100, 30)))
    }

    private val keys = listOf(
        1L to listOf(CardIndex.japaneseKey("上手", "じょうず"), "上手"),
        2L to listOf(CardIndex.japaneseKey("上手", "うわて"), "上手"),
        3L to listOf(CardIndex.japaneseKey("犬", "いぬ"), "犬"),
    )

    @Test fun wordMatchesItsReadingOnly() {
        assertEquals(listOf(1L), cardsOfWord(keys, "上手", "じょうず"))
        assertEquals(emptyList<Long>(), cardsOfWord(keys, "猫", "ねこ"))
    }

    @Test fun emptyReadingMatchesOnlyAUniqueSpelling() {
        assertEquals(listOf(3L), cardsOfWord(keys, "犬", ""))
        assertEquals(emptyList<Long>(), cardsOfWord(keys, "上手", ""))
    }
}
