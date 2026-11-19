package io.github.pedrubik2000.kumapie.lang

import org.junit.Assert.assertEquals
import org.junit.Test

/** An own card's line audio and picture names ([CardMedia.names]): what Review checks and a moved card's old files. */
class CardMediaTest {
    @Test fun audioAndPicture() = assertEquals("space_dandy_1200-3400.ogg" to "space_dandy_1200.webp",
        CardMedia.names(listOf("[audio:space_dandy_1200-3400.ogg]", "<img src=\"space_dandy_1200.webp\">", "銀河")))

    @Test fun textOnlyCard() = assertEquals(null to null, CardMedia.names(listOf("銀河", "galaxy")))

    @Test fun sizes() {
        assertEquals(480, CardMedia.WIDTH) // Pedro, 2026-11-19: the smallest of the three offered
        assertEquals(16_000, CardMedia.AUDIO_BITRATE)
    }
}
