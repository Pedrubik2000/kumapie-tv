package io.github.pedrubik2000.kumapie.mobile.local

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionHoursTest {
    @Test fun nightWindow() {
        assertEquals(listOf(1, 2, 3, 4, 5), (0..23).filter { SubscriptionWorker.inHours(it, 1, 6) })
        assertEquals(listOf(0, 1, 22, 23), (0..23).filter { SubscriptionWorker.inHours(it, 22, 2) })
    }
}
