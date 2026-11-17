package io.github.pedrubik2000.kumapie.lang

import org.junit.Assert.assertEquals
import org.junit.Test

/** A card's place in its episode, read back from its Context field: a time (ms) since 2026-11-17, a scene number before. */
class ContextTest {
    @Test fun time() = assertEquals(787_000, SrsCards.placeOf("Mined in kumapie: Yani Neko · Yani Neko 1, at 13:07."))
    @Test fun overAnHour() = assertEquals(4_205_000, SrsCards.placeOf("Rated in kumapie: Show · A film, at 70:05."))
    @Test fun oldScene() = assertEquals(12, SrsCards.placeOf("Mined in kumapie: Show · Episode 3, scene 12."))
    @Test fun none() = assertEquals(null, SrsCards.placeOf("Mined in kumapie's Japanese dictionary."))
}
