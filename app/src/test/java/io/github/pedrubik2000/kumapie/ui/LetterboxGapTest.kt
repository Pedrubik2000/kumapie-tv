package io.github.pedrubik2000.kumapie.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LetterboxGapTest {
    @Test fun aFullScreenPictureKeepsTheUsualGap() = assertEquals(40f, letterboxGap(960f, 540f, 1920f, 1080f), 0.01f)

    @Test fun aWidePictureLiftsTheBoxAboveItsBlackBand() {
        val band = (540f - 960f * 804f / 1920f) / 2f
        assertEquals(band + 16f, letterboxGap(960f, 540f, 1920f, 804f), 0.01f)
    }

    @Test fun noVideoSizeYetKeepsTheUsualGap() = assertEquals(40f, letterboxGap(960f, 540f, 0f, 0f), 0.01f)

    @Test fun aTallPictureHasNoBandBelow() = assertEquals(40f, letterboxGap(960f, 540f, 1080f, 1920f), 0.01f)
}
