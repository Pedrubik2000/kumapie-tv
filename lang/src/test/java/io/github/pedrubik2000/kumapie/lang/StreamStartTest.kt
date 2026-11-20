package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Discovery
import io.github.pedrubik2000.kumapie.data.Found
import io.github.pedrubik2000.kumapie.data.StreamStart
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [StreamStart]: a Discover episode plays alone from an addon's link (from its saved place) or goes to the PC; a picked link puts its title on Home. */
class StreamStartTest {
    private val frieren = Found("kitsu:46474", "series", "Sousou no Frieren", "", "")
    private val alone = mapOf("type" to "series", "id" to "kitsu:46474:3", "url" to "http://addon/clip.mp4", "file" to "[Proof] Frieren - 03.mp4")

    private class Home : Discovery {
        val saved = mutableListOf<Found>()
        override suspend fun board() = emptyList<Pair<String, List<Found>>>()
        override suspend fun discover(q: String) = emptyList<Found>()
        override suspend fun discoverMeta(type: String, id: String) = JSONObject()
        override suspend fun discoverStreams(type: String, id: String) = JSONArray()
        override suspend fun saved() = saved.toList()
        override suspend fun save(title: Found, on: Boolean): List<Found> { saved.removeAll { it.id == title.id }; if (on) saved += title; return saved.toList() }
        override suspend fun seen(id: String, on: Boolean) = Unit
    }

    @Test fun aLinkPlaysAloneFromItsSavedPlace() =
        assertEquals(StreamStart.Alone("http://addon/clip.mp4", 612.0), StreamStart.of(alone, 612.0))

    @Test fun resumeRule() {
        assertNull("nothing saved", (StreamStart.of(alone, null) as StreamStart.Alone).resumeAt)
        assertNull("0 = start over (it was played to its end)", (StreamStart.of(alone, 0.0) as StreamStart.Alone).resumeAt)
        assertNull("5 s in is the start", (StreamStart.of(alone, 5.0) as StreamStart.Alone).resumeAt)
        assertEquals(5.5, (StreamStart.of(alone, 5.5) as StreamStart.Alone).resumeAt)
    }

    @Test fun noLinkGoesToThePc() {
        val pc = alone - "url"
        assertEquals(StreamStart.Pc(pc), StreamStart.of(pc, 612.0))
        assertEquals(StreamStart.Pc(alone + ("url" to "")), StreamStart.of(alone + ("url" to ""), 612.0))
    }

    @Test fun aPickedLinkPutsTheTitleOnHome() = runBlocking {
        val home = Home()
        val f = JSONObject().put("url", "http://addon/clip.mp4").put("file", "[Proof] Frieren - 03.mp4")
        val (query, kept) = StreamStart.picked(home, frieren, "kitsu:46474:3", "Sousou no Frieren 3", f, kept = false)
        assertEquals(listOf(frieren), home.saved)
        assertEquals(listOf(frieren), kept)
        assertEquals("http://addon/clip.mp4", query["url"])
        assertEquals("[Proof] Frieren - 03.mp4", query["file"])
        assertTrue(StreamStart.of(query, null) is StreamStart.Alone)
    }

    @Test fun alreadyOnHomeIsNotSavedAgain() = runBlocking {
        val home = Home()
        val (_, kept) = StreamStart.picked(home, frieren, "kitsu:46474:3", "Sousou no Frieren 3", JSONObject().put("url", "http://x"), kept = true)
        assertEquals(emptyList<Found>(), home.saved)
        assertNull(kept)
    }

    @Test fun aPickedTorrentGoesToThePcWithoutHome() = runBlocking {
        val home = Home()
        val f = JSONObject().put("hash", "abc").put("file", "Frieren 03.mkv").put("idx", 2).put("short", "1080p")
        val (query, kept) = StreamStart.picked(home, frieren, "kitsu:46474:3", "Sousou no Frieren 3", f, kept = false)
        assertEquals(emptyList<Found>(), home.saved)
        assertNull(kept)
        assertEquals(StreamStart.Pc(query), StreamStart.of(query, 612.0))
        assertEquals(listOf("abc", "2", "1080p"), listOf(query["hash"], query["idx"], query["label"]))
    }

    @Test fun theReadyFileGoesToThePc() {
        val q = StreamStart.ready(frieren, "kitsu:46474:3", "Sousou no Frieren 3")
        assertEquals(mapOf("lang" to "ja", "type" to "series", "id" to "kitsu:46474:3", "title" to "Sousou no Frieren 3", "show" to "Sousou no Frieren"), q)
        assertEquals(StreamStart.Pc(q), StreamStart.of(q, null))
    }
}
