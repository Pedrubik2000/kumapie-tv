package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Discovery
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Found
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.data.StreamStart
import io.github.pedrubik2000.kumapie.data.TitlePage
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [TitlePage]: a Discover title page's deciding, the TV's and the phone's (▶, play, the file list, + Home, ✓). */
class TitlePageTest {
    private val frieren = Found("kitsu:46474", "series", "Sousou no Frieren", "", "")

    private class Fake(var files: JSONArray = JSONArray()) : Discovery {
        val saved = mutableListOf<Found>()
        val watched = mutableSetOf("kitsu:46474:1")
        var ready = ""
        var metaFails = false
        var going: List<Pair<Show, Episode>>? = null
        var twoSeasons = false
        override fun goingNow() = going
        override suspend fun board() = emptyList<Pair<String, List<Found>>>()
        override suspend fun discover(q: String) = emptyList<Found>()
        override suspend fun discoverMeta(type: String, id: String): JSONObject {
            if (metaFails) error("offline")
            return JSONObject().put("id", id).put("name", "Sousou no Frieren").put("episodes", JSONArray((1..3).map { it to 0 }.plus(if (twoSeasons) (1..3).map { it to 10 } else emptyList()).map { (n, off) ->
                val eid = "$id:${n + off}"
                JSONObject().put("id", eid).put("number", n).put("watched", eid in watched).put("ready", eid == ready)
            }))
        }
        override suspend fun discoverStreams(type: String, id: String) = files
        override suspend fun saved() = saved.toList()
        override suspend fun save(title: Found, on: Boolean): List<Found> { saved.removeAll { it.id == title.id }; if (on) saved += title; return saved.toList() }
        override suspend fun seen(id: String, on: Boolean) { if (on) watched += id else watched -= id }
    }

    private fun page(d: Discovery) = TitlePage(d, frieren, has = { setOf("avc") })
    private fun file(name: String, url: String? = null) = JSONObject().put("label", name).put("file", name).apply { url?.let { put("url", it) } }

    @Test fun playIsNextUp() = runBlocking {
        val p = page(Fake()).apply { load() }
        assertEquals("kitsu:46474:2", p.next?.getString("id"))
    }

    @Test fun nextUpFollowsAniListWhenLocalHistoryIsBehind() = runBlocking {
        val p = page(Fake().apply { watched.clear() }).apply { load(); aniProgress = 2 }
        assertEquals("kitsu:46474:3", p.next?.getString("id"))
        p.aniProgress = 3
        assertEquals("kitsu:46474:1", p.next?.getString("id")) // no episode 4 in the meta: as before
    }

    @Test fun aHalfWatchedLocalEpisodeWinsOverAniList() = runBlocking {
        val e = Episode("kitsu:46474:1", "Episode 1", 1, 1, 1400.0, "", 0, 0, 0, 300.0)
        val d = Fake().apply { watched.clear(); going = listOf(Show(frieren.id, "Frieren", "stream", "", listOf(e), "ja") to e) }
        val p = page(d).apply { load(); aniProgress = 2 }
        assertEquals("kitsu:46474:1", p.next?.getString("id"))
    }

    @Test fun repeatedEpisodeNumbersFallBackToTheOldNextUp() = runBlocking {
        val p = page(Fake().apply { watched.clear(); twoSeasons = true }).apply { load(); aniProgress = 2 }
        assertEquals("kitsu:46474:1", p.next?.getString("id"))
    }

    @Test fun localHistoryAheadOfAniListWins() = runBlocking {
        val p = page(Fake().apply { watched += "kitsu:46474:2" }).apply { load(); aniProgress = 1 }
        assertEquals("kitsu:46474:3", p.next?.getString("id"))
    }

    @Test fun aReadyFileStartsAtOnce() = runBlocking {
        val p = page(Fake().apply { ready = "kitsu:46474:2" }).apply { load() }
        assertEquals(StreamStart.ready(frieren, "kitsu:46474:2", "Sousou no Frieren 2"), p.play(p.next))
        assertNull(p.picking)
    }

    @Test fun withoutOneTheFilesShowPlayableFirst() = runBlocking {
        val d = Fake(JSONArray(listOf(file("[A] Frieren - 02 HEVC 10bit.mkv"), file("[B] Frieren - 02 x264.mkv"))))
        val p = page(d).apply { load() }
        assertNull(p.play(p.next))
        assertEquals("kitsu:46474:2" to "Sousou no Frieren 2", p.picking)
        p.loadFiles()
        assertEquals(listOf("[B] Frieren - 02 x264.mkv", "[A] Frieren - 02 HEVC 10bit.mkv"), p.files?.map { it.getString("file") })
        assertTrue(p.missing(p.files!![1]).isNotEmpty())
    }

    @Test fun noNextOpensTheTitlesFiles() {
        val p = page(Fake())
        assertNull(p.play(null))
        assertEquals(frieren.id to frieren.name, p.picking)
    }

    @Test fun noFilesIsAnEmptyList() = runBlocking {
        val p = page(Fake()).apply { load(); play(next); loadFiles() }
        assertEquals(emptyList<JSONObject>(), p.files)
    }

    @Test fun aPickedLinkKeepsTheTitleOnHome() = runBlocking {
        val d = Fake()
        val p = page(d).apply { loadKept() }
        assertEquals(false, p.kept)
        val q = p.pick("kitsu:46474:2" to "Sousou no Frieren 2", file("[B] Frieren - 02.mkv", "http://addon/2.mp4"))
        assertEquals("http://addon/2.mp4", q["url"])
        assertEquals(listOf(frieren), d.saved)
        assertEquals(true, p.kept)
    }


    @Test fun markWatchedUpdatesTheEpisodes() = runBlocking {
        val p = page(Fake()).apply { load() }
        p.mark(p.episodes[1])
        assertTrue(p.episodes[1].getBoolean("watched"))
        assertEquals("kitsu:46474:3", p.next?.getString("id"))
        p.mark(p.episodes[0])
        assertFalse(p.episodes[0].getBoolean("watched"))
    }

    @Test fun anErrorOnlyWithNothingToShowAndCloseClearsIt() = runBlocking {
        val d = Fake()
        val p = page(d).apply { load() }
        d.metaFails = true
        p.load(); assertNull(p.error)
        val q = page(d).apply { load() }
        assertEquals("offline", q.error)
        q.play(null); q.closeFiles()
        assertNull(q.picking); assertNull(q.error)
    }
}
