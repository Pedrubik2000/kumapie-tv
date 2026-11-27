package io.github.pedrubik2000.kumapie.mobile.local

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionFilterTest {
    private fun sub(url: String, lang: String) = Subscriptions.Sub(url, url, "shorts", lang, 5)

    @Test fun onlyJapaneseSubscriptionsRun() {
        val subs = listOf(sub("a", "de"), sub("b", "ja"), sub("c", "en"))
        assertEquals(listOf("b"), SubscriptionWorker.runnable(subs).map { it.url })
        assertEquals(emptyList<String>(), SubscriptionWorker.runnable(listOf(sub("d", "de"))).map { it.url })
    }
}
