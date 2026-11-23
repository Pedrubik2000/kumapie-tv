package io.github.pedrubik2000.kumapie

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.pedrubik2000.kumapie.data.Backdrops
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Step 3: a hero picture is Kitsu's cover, else the addon's background, asked once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackdropsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val calls = ArrayList<String>()

    private fun hero(id: String, kitsu: String = "K", meta: String = "M", kitsuFails: Boolean = false, metaFails: Boolean = false) = runBlocking {
        Backdrops.get(context, id, { n -> calls += "kitsu$n"; if (kitsuFails) error("offline") else kitsu }, { calls += "meta"; if (metaFails) error("offline") else meta })
    }

    @Test fun kitsuCoverFirst() { assertEquals("K", hero("kitsu:1")); assertEquals(listOf("kitsu1"), calls) }

    @Test fun otherIdsUseMeta() { assertEquals("M", hero("tt123")); assertEquals(listOf("meta"), calls) }

    @Test fun skipListUsesMeta() { assertEquals("M", hero("kitsu:50021")); assertEquals(listOf("meta"), calls) }

    @Test fun noCoverFallsToMeta() { assertEquals("M", hero("kitsu:2", kitsu = "")); assertEquals(listOf("kitsu2", "meta"), calls) }

    @Test fun nothingAtAllIsNull() = assertNull(hero("kitsu:3", kitsu = "", meta = ""))

    @Test fun failedAskIsNotKept() {
        assertEquals("M", hero("kitsu:4", kitsuFails = true))
        assertNull(Backdrops.saved(context, "kitsu:4"))
        assertEquals("K", hero("kitsu:4")) // a later open tries Kitsu again
        assertEquals("K", Backdrops.saved(context, "kitsu:4"))
    }

    @Test fun failedEverythingIsNotKept() {
        assertNull(hero("kitsu:5", kitsuFails = true, metaFails = true))
        assertEquals("K", hero("kitsu:5"))
    }

    @Test fun keptAnswerMakesNoCall() {
        hero("kitsu:6"); calls.clear()
        assertEquals("K", hero("kitsu:6"))
        assertEquals(emptyList<String>(), calls)
    }
}
