package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.BestForYou
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [BestForYou] is Next Volume's "Best for you" (tools/mangapick/index.html `build()`), ported. The resource is
 * Pedrubik's public AniList lists (anime + manga, 2026-11-22, only the fields the score reads); the expected ids are
 * what the page's own code gives for that file, anime + Planning (C:\dojo\work\kumapie\bestforyou_ref.js).
 */
class BestForYouTest {
    private val saved = JSONObject(javaClass.classLoader!!.getResource("anilist_pedrubik.json")!!.readText())

    @Test fun sameTop20AsThePage() = assertEquals(
        listOf(237, 323, 13601, 5630, 5681, 185660, 106240, 2164, 21637, 11977, 110350, 1033, 213338, 4087, 98658, 128545, 6547, 99900, 19115, 98505),
        BestForYou.planning(BestForYou.items(saved)).take(20),
    )

    @Test fun customListsDontRepeatATitle() {
        val items = BestForYou.items(saved)
        assertEquals(items.size, items.map { it.id }.distinct().size)
        assertEquals(69, BestForYou.planning(items).size)
    }
}
