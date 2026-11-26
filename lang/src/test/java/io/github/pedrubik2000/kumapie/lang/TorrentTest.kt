package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Addons
import io.github.pedrubik2000.kumapie.data.RdGate
import io.github.pedrubik2000.kumapie.data.Startup
import io.github.pedrubik2000.kumapie.torrent.TorrentPlan
import io.github.pedrubik2000.kumapie.torrent.TorrentRef
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentTest {
    private val hash = "08ada5a7a6183aae1e09d831df6748d566095a10"
    private val stream = JSONObject(
        """{"name":"Torrentio\n1080p","title":"Sintel.2010.1080p.mkv\n👤 24 💾 1.4 GB ⚙️ YTS","infoHash":"${hash.uppercase()}","fileIdx":3,
        "behaviorHints":{"bingeGroup":"x","filename":"Sintel.2010.1080p.mkv"},"sources":["tracker:udp://a.example:80/announce","dht:$hash","tracker:http://b.example/announce"]}""")

    @Test fun parsesATorrentioStream() {
        val ref = TorrentRef.of(stream)!!
        assertEquals(hash, ref.hash)
        assertEquals(3, ref.index)
        assertEquals("Sintel.2010.1080p.mkv", ref.name)
        assertEquals(listOf("udp://a.example:80/announce", "http://b.example/announce"), ref.trackers)
    }

    @Test fun withoutFileIdxOrTrackers() {
        val ref = TorrentRef.of(JSONObject("""{"infoHash":"$hash"}"""))!!
        assertEquals(-1, ref.index)
        assertTrue(ref.trackers.isNotEmpty())
        assertNull(TorrentRef.of(JSONObject("""{"infoHash":"nope"}""")))
        assertNull(TorrentRef.of(JSONObject("""{"url":"https://x/y.mkv"}""")))
    }

    @Test fun uriRoundTrip() {
        val ref = TorrentRef(hash, 2, "My Show - 01 [1080p].mkv", listOf("udp://t.example:1337/announce?x=1&y=2"))
        assertEquals(ref, TorrentRef.parse(ref.uri()))
        assertTrue(ref.uri().startsWith("torrent://$hash/My%20Show"))
        assertEquals("magnet:?xt=urn:btih:$hash&tr=udp%3A%2F%2Ft.example%3A1337%2Fannounce%3Fx%3D1%26y%3D2", ref.magnet())
        assertNull(TorrentRef.parse("https://x/y.mkv"))
        assertNull(TorrentRef.parse("torrent://short/x?i=1"))
    }

    @Test fun seedersFromTheTitle() {
        assertEquals(24, TorrentRef.seeders("Sintel\n👤 24 💾 1.4 GB"))
        assertEquals(0, TorrentRef.seeders("no numbers here"))
    }

    @Test fun streamEntryKeepsFileNameAndSeeders() {
        val f = Addons.torrentFile(stream)!!
        assertTrue(f.getString("url").startsWith("torrent://$hash/"))
        assertEquals("Sintel.2010.1080p.mkv", f.getString("file"))
        assertEquals(24, f.getInt("seeders"))
        assertTrue(f.getBoolean("torrent"))
        assertNull(Addons.files(stream))
    }

    @Test fun pickTheVideoFile() {
        val names = listOf("Sintel.txt", "Sintel.mkv", "Extras.mp4", "poster.jpg")
        val sizes = listOf(10L, 900L, 50L, 20L)
        assertEquals(2, TorrentPlan.pick(names, sizes, 2))
        assertEquals(1, TorrentPlan.pick(names, sizes, -1))
        assertEquals(1, TorrentPlan.pick(names, sizes, 9))
        assertEquals(-1, TorrentPlan.pick(emptyList(), emptyList(), -1))
    }

    @Test fun torrentsOnNeedNoDebridKey() {
        assertEquals(RdGate.MISSING, Startup.rdGate(false, false, "", null, 0))
        assertEquals(RdGate.NOT_ASKED, Startup.rdGate(false, false, "", null, 0, torrents = true))
        assertEquals(RdGate.OK, Startup.rdGate(false, false, "k", "ok", 0, torrents = true))
    }

    @Test fun plainTorrentioIsAddedOnlyWithTorrentsOnAndNoKey() {
        assertEquals(listOf(Addons.KITSU, Addons.TORRENTIO_PLAIN), Addons.sources(emptyList(), true, false, "", torrents = true))
        assertEquals(listOf(Addons.KITSU), Addons.sources(emptyList(), true, false, ""))
        val keyed = Addons.sources(emptyList(), false, false, "abc", torrents = true)
        assertEquals(listOf(Addons.torrentio("abc")), keyed)
        val own = "https://torrentio.strem.fun/providers=yts|qualityfilter=480p/manifest.json"
        assertEquals(listOf(own), Addons.sources(listOf(own), false, false, "", torrents = true))
    }
}

class TorrentSpaceAndNextTest {
    @Test fun theCacheWindowIsSmallOn32BitOrLowSpace() {
        val mb = 1L shl 20
        assertEquals(200 * mb, TorrentPlan.cap(true, 8_000 * mb))
        assertEquals(64 * mb, TorrentPlan.cap(false, 8_000 * mb))
        assertEquals(64 * mb, TorrentPlan.cap(true, 900 * mb))
        assertTrue(TorrentPlan.cap(true, Long.MAX_VALUE) <= 256 * mb)
    }

    @Test fun aFailureIsRetriedOnlyAfterTheWindow() {
        assertFalse(TorrentPlan.retryable(1_000, 5_000, 15_000))
        assertTrue(TorrentPlan.retryable(1_000, 17_000, 15_000))
    }

    @Test fun nextEpisodePrefersATorrentWithSeeders() {
        fun t(seeders: Int, file: String) = JSONObject().put("label", file).put("about", "").put("file", file).put("url", "torrent://x").put("torrent", true).put("seeders", seeders)
        val files = listOf(t(0, "a.mkv"), t(5, "b.mkv"))
        val q = io.github.pedrubik2000.kumapie.data.nextQuery(mapOf("type" to "series", "id" to "tt1:1:1", "show" to "S"), files, setOf("avc"), false)!!
        assertEquals("b.mkv", q["file"])
    }
}

class TorrentFirstUseTest {
    @Test fun onlyTheFirstOpenOfAProcessWipesLeftovers() {
        val use = io.github.pedrubik2000.kumapie.torrent.FirstUse()
        assertTrue(use.take())
        assertFalse(use.take())
    }

    @Test fun wipeRemovesEveryLeftoverSessionFolder() {
        val parent = java.nio.file.Files.createTempDirectory("torrent").toFile()
        java.io.File(parent, "3").mkdirs()
        java.io.File(parent, "7/Show").mkdirs()
        java.io.File(parent, "7/Show/a.mkv").writeText("x")
        io.github.pedrubik2000.kumapie.torrent.FirstUse().wipe(parent)
        assertFalse(parent.exists())
    }
}
