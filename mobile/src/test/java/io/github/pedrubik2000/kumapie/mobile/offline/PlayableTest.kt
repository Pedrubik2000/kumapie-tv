package io.github.pedrubik2000.kumapie.mobile.offline

import io.github.pedrubik2000.kumapie.data.Playable
import org.junit.Assert.assertEquals
import org.junit.Test

/** A stream file's name -> the decoders it needs (real Torrentio names, AoT ep 1, 2026-11-17). */
class PlayableTest {
    private fun needs(n: String) = Playable.needs(n).sorted().joinToString(",")

    @Test fun names() {
        assertEquals("", needs("[HorribleSubs] Shingeki no Kyojin - 01 [1080p].mkv"))
        assertEquals("", needs("Attack on Titan 2013 Seasons 1 to 4 Complete 1080p WEB x264 [i_c]"))
        assertEquals("", needs("Attack.on.Titan.S01.BluRay.1080p.FLAC.2.0.AVC.REMUX-FraMeSToR"))
        assertEquals("hevc10", needs("[Salieri] Attack On Titan S1 - 01 (1080p) (HDR) [Dual Audio].mkv"))
        assertEquals("hevc10", needs("[Judas] Shingeki no Kyojin (Season 1) [BD 1080p][HEVC x265 10bit][Dual-Audio][Eng-Subs]"))
        assertEquals("hevc10", needs("[Netaro] Attack on Titan - S01E01 [BD 1080p Ma10] [Dual-Audio FLAC]"))
        assertEquals("hevc10", needs("[pseudo] Attack on Titan S01E01 To You, Two Thousand Years Later [1080p] [h.265].mkv"))
        assertEquals("hevc10", needs("[Ryuuga] - Attack on Titan S01-S03 Bluray [1080p-AC3-x265]"))
        assertEquals("av1", needs("[Trix] Shingeki no Kyojin (BD 1080p AV1 Opus) [Dual Audio]"))
        assertEquals("avc10", needs("[SCY] Attack on Titan - 01v2 (BD 1080p Hi10 FLAC) [CBD993E4].mkv"))
        assertEquals("4k,hevc10", needs("Show.S01E01.2160p.WEB-DL.DV.HDR.H.265-GRP.mkv"))
    }

    @Test fun missing() {
        assertEquals(listOf("HEVC 10 bit"), Playable.missing("[Judas] [HEVC x265 10bit]", setOf("avc")))
        assertEquals(emptyList<String>(), Playable.missing("[Judas] [HEVC x265 10bit]", setOf("avc", "hevc10")))
    }
}
