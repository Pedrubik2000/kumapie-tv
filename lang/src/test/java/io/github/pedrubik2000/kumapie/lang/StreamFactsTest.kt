package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.StreamFacts
import io.github.pedrubik2000.kumapie.data.streamFacts
import org.junit.Assert.assertEquals
import org.junit.Test

/** The TV file list's facts from a Torrentio stream's name and description (title page, option D, 2026-11-27). */
class StreamFactsTest {
    @Test fun cachedGroupRelease() = assertEquals(
        StreamFacts(true, "1080p", "HorribleSubs", emptyList(), "1.3 GB", 41, "NyaaSi"),
        streamFacts("[RD+] Torrentio\n1080p", "[HorribleSubs] Ore Monogatari!! (01-24) [1080p] (Batch)\n[HorribleSubs] Ore Monogatari!! - 09 [1080p].mkv\n👤 41 💾 1.3 GB ⚙️ NyaaSi"))

    @Test fun cyrillicTitleShowsItsSite() = assertEquals(
        StreamFacts(false, "1080p", "RuTracker", listOf("WEB", "JP"), "1.1 GB", 12, ""),
        streamFacts("[RD download] Torrentio 1080p", "Моя история!! / Ore Monogatari!! / My Love Story!! [TV] [24 из 24] [JAP+Sub] [2015 WEB-DL] [1080p]\nOre Monogatari 09.mkv\n👤 12 💾 1.1 GB ⚙️ RuTracker"))

    @Test fun remuxIsNotAlsoBd() = assertEquals(
        StreamFacts(true, "1080p", "PMR", listOf("BD Remux", "AVC", "Dual audio"), "6.8 GB", 3, "NyaaSi"),
        streamFacts("[RD+] Torrentio 1080p", "[PMR] Ore Monogatari!! (BD Remux 1080p AVC FLAC) [Dual Audio]\nS01E09.mkv\n👤 3 💾 6.8 GB ⚙️ NyaaSi"))

    @Test fun anotherAddonKeepsItsText() = assertEquals(
        StreamFacts(null, "720p", "Episode 9 720p", emptyList(), "", null, ""),
        streamFacts("Some addon", "Episode 9 720p"))
}
