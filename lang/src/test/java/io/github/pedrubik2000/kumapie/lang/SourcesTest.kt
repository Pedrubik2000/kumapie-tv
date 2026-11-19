package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Addons
import org.junit.Assert.assertEquals
import org.junit.Test

/** The setup's "What to watch", one rule for the TV's setup page and the phone's onboarding ([Addons.sources]). */
class SourcesTest {
    private val own = "https://example.org/my-addon/manifest.json"

    @Test fun firstSetup() = assertEquals(listOf(Addons.KITSU, Addons.OPENSUBTITLES, Addons.torrentio("t1")),
        Addons.sources(emptyList(), kitsu = true, english = true, rd = " t1 "))

    @Test fun newTokenReplacesTheOldTorrentioUntickedComeOffOwnStays() = assertEquals(listOf(own, Addons.KITSU, Addons.torrentio("t2")),
        Addons.sources(listOf(own, Addons.KITSU, Addons.OPENSUBTITLES, Addons.torrentio("t1")), kitsu = true, english = false, rd = "t2"))

    @Test fun blankTokenKeepsTheOldOne() = assertEquals(listOf(Addons.torrentio("t1"), Addons.KITSU),
        Addons.sources(listOf(Addons.torrentio("t1")), kitsu = true, english = false, rd = ""))
}
