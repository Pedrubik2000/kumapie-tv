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

    @Test fun pieceMath() {
        val pl = 262_144
        val offset = 1_000_000L
        val size = 100L * pl
        assertEquals(3, TorrentPlan.firstPiece(offset, pl))
        assertEquals(103, TorrentPlan.lastPiece(offset, size, pl))
        assertEquals(3, TorrentPlan.pieceAt(offset, 0, pl))
        assertEquals(4, TorrentPlan.pieceAt(offset, pl.toLong(), pl))
        assertEquals(3..18, TorrentPlan.head(3, 103, pl))
        assertEquals(96..103, TorrentPlan.tail(3, 103, pl))
        assertEquals(3..5, TorrentPlan.head(3, 5, pl).let { it.first..minOf(it.last, 5) })
    }

    @Test fun priorities() {
        val p = TorrentPlan.priorities(400, 10, 300, 80, 262_144)
        assertEquals(0, p[9])
        assertEquals(0, p[301])
        assertEquals(TorrentPlan.TOP, p[10])
        assertEquals(TorrentPlan.TOP, p[300])
        assertEquals(TorrentPlan.IGNORE, p[50])
        assertEquals(TorrentPlan.NORMAL, p[200])
        assertEquals(TorrentPlan.TOP, p[80])
        assertEquals(TorrentPlan.TOP, p[80 + 31])
        assertEquals(TorrentPlan.NORMAL, p[80 + 64])
        assertEquals(80..87, TorrentPlan.urgent(80, 300, 262_144))
    }

    @Test fun readableBytesStopAtTheFirstMissingPiece() {
        val pl = 1000
        val have = setOf(2, 3, 4, 6)
        assertEquals(2500L, TorrentPlan.available(2500, 10_000, 0, pl) { it in have })
        assertEquals(1000L, TorrentPlan.available(2500, 10_000, 1500, pl) { it in have })
        assertEquals(0L, TorrentPlan.available(2500, 10_000, 2500, pl) { it in have })
        assertEquals(1000L, TorrentPlan.available(2500, 10_000, 3500, pl) { it in have })
        assertEquals(10_000L, TorrentPlan.available(0, 10_000, 0, pl) { true })
        assertEquals(0L, TorrentPlan.available(0, 10_000, 10_000, pl) { true })
    }

    @Test fun lastPieceIsCutAtTheFileEnd() {
        assertEquals(500L, TorrentPlan.available(0, 4500, 4000, 1000) { true })
    }

    @Test fun aSeekIsAJump() {
        val mb = 1L shl 20
        assertFalse(TorrentPlan.jumped(10 * mb, 10 * mb))
        assertFalse(TorrentPlan.jumped(10 * mb, 12 * mb))
        assertTrue(TorrentPlan.jumped(10 * mb, 40 * mb))
        assertTrue(TorrentPlan.jumped(100 * mb, 20 * mb))
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
