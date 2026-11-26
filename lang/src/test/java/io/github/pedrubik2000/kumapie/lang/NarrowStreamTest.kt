package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Playable
import io.github.pedrubik2000.kumapie.data.nextQuery
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NarrowStreamTest {
    private val all = setOf("avc", "hevc10", "4k")
    private val ended = mapOf("type" to "series", "id" to "kitsu:1:3", "show" to "S", "file" to "[Sub] S - 03 (1080p x265).mkv")
    private fun f(file: String) = JSONObject().put("label", "T").put("about", file).put("file", file).put("url", "http://a/$file")
    private val files = listOf(f("[Sub] S - 04 (2160p HEVC 10bit).mkv"), f("[Sub] S - 04 (1080p x265).mkv"), f("[Er] S - 04 (1080p x264).mkv"))

    @Test fun weights() {
        assertEquals(0, Playable.weight("S 1080p x264", true))
        assertEquals(1, Playable.weight("S 1080p x265", true))
        assertTrue(Playable.weight("S 2160p x265 10bit", true) > Playable.weight("S 1080p x265", true))
        assertEquals(0, Playable.weight("S 2160p x265 10bit", false))
    }

    @Test fun narrowDeviceKeepsAGroupTheUserPickedAtTheSameWeight() {
        assertEquals("[Sub] S - 04 (1080p x265).mkv", nextQuery(ended, files, all, true)?.get("file"))
    }

    @Test fun narrowDeviceDoesNotLetTheGroupCreepUp() {
        val x264 = ended + ("file" to "[Sub] S - 03 (1080p x264).mkv")
        assertEquals("[Sub] S - 04 (1080p x264).mkv", nextQuery(x264, files + f("[Sub] S - 04 (1080p x264).mkv"), all, true)?.get("file"))
        assertEquals("[Er] S - 04 (1080p x264).mkv", nextQuery(x264, files, all, true)?.get("file"))
    }

    @Test fun tenBitWords() {
        assertTrue(Playable.weight("S Hi10P", true) > 0)
        assertEquals(2, Playable.weight("S hevc10", true))
    }

    @Test fun wideDeviceKeepsItsOrderAndRelease() {
        assertEquals("[Sub] S - 04 (2160p HEVC 10bit).mkv", nextQuery(ended, files, all, false)?.get("file"))
    }
}
