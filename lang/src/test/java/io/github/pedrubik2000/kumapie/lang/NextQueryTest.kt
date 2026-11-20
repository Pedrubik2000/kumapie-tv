package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.nextQuery
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [nextQuery]: at a no-PC episode's end, the next one's file (same release first, else the best that plays here); with a PC, its next id. */
class NextQueryTest {
    private val here = setOf("avc")
    private val ended = mapOf("lang" to "ja", "type" to "series", "id" to "kitsu:46474:3", "show" to "Sousou no Frieren",
        "url" to "http://a/3.mkv", "file" to "[SubsPlease] Sousou no Frieren - 03 (1080p).mkv")
    private fun f(file: String) = JSONObject().put("label", "Torrentio 1080p").put("about", file).put("file", file).put("url", "http://a/$file")

    @Test fun sameReleaseFirst() {
        val q = nextQuery(ended, listOf(f("[Erai-raws] Sousou no Frieren - 04 (1080p x264).mkv"), f("[SubsPlease] Sousou no Frieren - 04 (1080p).mkv")), here)
        assertEquals(mapOf("lang" to "ja", "type" to "series", "id" to "kitsu:46474:4", "show" to "Sousou no Frieren", "title" to "Sousou no Frieren 4",
            "url" to "http://a/[SubsPlease] Sousou no Frieren - 04 (1080p).mkv", "file" to "[SubsPlease] Sousou no Frieren - 04 (1080p).mkv"), q)
    }

    @Test fun elseTheFirstThatPlaysHere() {
        val q = nextQuery(ended, listOf(f("[Judas] Sousou no Frieren - 04 (1080p HEVC x265 10bit).mkv"), f("[Erai-raws] Sousou no Frieren - 04 (1080p x264).mkv")), here)
        assertEquals("[Erai-raws] Sousou no Frieren - 04 (1080p x264).mkv", q?.get("file"))
    }

    @Test fun theSameReleaseThatDoesNotPlayHereLosesToOneThatDoes() {
        val q = nextQuery(ended, listOf(f("[SubsPlease] Sousou no Frieren - 04 (1080p HEVC).mkv"), f("[Erai-raws] Sousou no Frieren - 04 (1080p x264).mkv")), here)
        assertEquals("[Erai-raws] Sousou no Frieren - 04 (1080p x264).mkv", q?.get("file"))
    }

    @Test fun nothingPlaysHereTakesTheFirst() {
        val q = nextQuery(ended, listOf(f("[Judas] Sousou no Frieren - 04 (HEVC x265 10bit).mkv"), f("[ASW] Sousou no Frieren - 04 (AV1).mkv")), here)
        assertEquals("[Judas] Sousou no Frieren - 04 (HEVC x265 10bit).mkv", q?.get("file"))
    }

    @Test fun noFilesOrNoNextIsNull() {
        assertNull("last episode: the addons have no file for the one after", nextQuery(ended, emptyList(), here))
        assertNull("a movie has no next", nextQuery(ended + ("type" to "movie") + ("id" to "tt0245429"), listOf(f("x.mkv")), here))
        assertNull("a whole title (kitsu:46474) is another show, not an episode", nextQuery(ended + ("id" to "kitsu:46474"), listOf(f("x.mkv")), here))
    }

    @Test fun withAPcTheNextIdFromThePc() {
        assertEquals(mapOf("lang" to "ja", "type" to "series", "id" to "kitsu:46474:4", "show" to "Sousou no Frieren", "title" to "Sousou no Frieren 4"),
            nextQuery(ended - "url" - "file", "kitsu:46474:4"))
        assertNull("the PC said no next", nextQuery(ended, ""))
    }
}
