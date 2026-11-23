package io.github.pedrubik2000.kumapie.lang

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Core plan 16c: the word events drive every device's schedule and colours (two stores, synced as DeviceSync does). */
class WordSyncTest {
    private val files = DeviceFileTest()
    private val day = Srs.DAY
    private val cat = "ja|猫\tねこ"
    private val fsrs = Fsrs6()

    @After fun clear() { WordSync.words = emptyMap() }

    private fun due(rows: List<Pair<Long, Int>>) = Srs.replay(rows, fsrs)?.let { it.lastReview + fsrs.interval(it.state.stability) * day }

    @Test fun aRatingOnAChangesTheDueOnBAfterSyncAndTheSameEventsTwiceChangeNothing() {
        val a = files.chromecast().apply { events = JSONArray() }
        val b = files.tablet().apply { events = JSONArray() }
        val rated = 10 * day
        a.events = DeviceFile.union(a.events, listOf(JSONObject().put("id", rated * 1000).put("key", cat).put("time", rated).put("ease", 3).put("device", "A")))
        val bCards = mapOf(7L to cat, 8L to "ja|犬\tいぬ")
        val bRevlog = mutableListOf<Pair<Long, Int>>()
        assertNull(due(bRevlog))

        b.import(a.export())
        val add = WordSync.toAdd(b.events, bCards, emptySet())
        assertEquals(listOf(rated * 1000 to 7L), add.map { it.first.getLong("id") to it.second })
        add.forEach { (e, _) -> bRevlog += e.getLong("time") to e.getInt("ease") }
        assertEquals(due(listOf(rated to 3)), due(bRevlog))
        assertEquals(DeviceFile.replay(a.events, fsrs)[cat], Srs.replay(bRevlog, fsrs))

        b.import(a.export())
        assertTrue(WordSync.toAdd(b.events, bCards, add.map { it.first.getLong("id") }.toSet()).isEmpty())
        assertEquals(1, b.events.length())
    }

    @Test fun aCardOnAOnlyColoursTheWordOnBAndMineThereRefusesOnce() {
        val a = files.chromecast().apply { events = JSONArray() }
        val b = files.tablet().apply { events = JSONArray() }
        val file = a.export().put("carded", WordSync.own("Chromecast-aaa", 5, listOf(cat)))
        val mine = DeviceFile.Mine("SM-T970-bbb", b.parts, b.known, b.personal, b.streams, b.events, null)
        val p = DeviceFile.plan(mine, file)
        assertEquals(DeviceFile.Count("Words with a card on another device", 1, 0, 0), p.counts.single { it.what.startsWith("Words with") })
        val again = DeviceFile.plan(DeviceFile.Mine("SM-T970-bbb", b.parts, b.known, b.personal, b.streams, b.events, null, p.carded), file)
        assertEquals(DeviceFile.Count("Words with a card on another device", 0, 0, 1), again.counts.single { it.what.startsWith("Words with") })

        WordSync.words = WordSync.elsewhere(p.events, p.carded, "SM-T970-bbb", emptySet(), { fsrs })
        assertEquals("b", WordSync.state("ja", "猫\tねこ"))
        assertEquals("b", WordSync.state("ja", "猫"))
        val refused = WordSync.refusal("ja", cat)
        assertTrue(refused!!.contains("Chromecast-aaa"))
        assertNull("a second Mine makes the card here", WordSync.refusal("ja", cat))

        val rated = WordSync.elsewhere(JSONArray().put(JSONObject().put("id", 1).put("key", cat).put("time", 1000L).put("ease", 3)), p.carded,
            "SM-T970-bbb", emptySet(), { fsrs }, now = 1000L)
        assertEquals("k", rated["ja"]!!["猫\tねこ"]!!.state)
        assertEquals("d", WordSync.elsewhere(JSONArray().put(JSONObject().put("id", 1).put("key", cat).put("time", 1000L).put("ease", 1)), p.carded,
            "SM-T970-bbb", emptySet(), { fsrs }, now = 1000L + 30 * day)["ja"]!!["猫"]!!.state)
        assertTrue("a card of its own here: its own state decides", WordSync.elsewhere(p.events, p.carded, "SM-T970-bbb", setOf(cat), { fsrs }).isEmpty())
    }

    @Test fun thisDevicesOwnEntryIsNeverTakenFromAnotherFile() {
        val mine = WordSync.own("TV", 10, listOf("de|haus"))
        val theirs = WordSync.own("TV", 99, emptyList()).put("Phone", JSONObject().put("made", 1).put("keys", JSONArray().put("de|baum")))
        val out = WordSync.carded(mine, theirs, "TV")
        assertEquals(10, out.getJSONObject("TV").getLong("made"))
        assertEquals("de|baum", out.getJSONObject("Phone").getJSONArray("keys").getString(0))
        assertNotEquals(out.toString(), WordSync.carded(mine, null, "TV").toString())
    }

    @Test fun aSharedCardsFormNeverSilencesTheRefusalOfAWordCardedOnlyOnTheOtherDevice() {
        val dog = "ja|犬\tいぬ"
        val fish = "ja|魚	さかな"
        val carded = WordSync.own("Chromecast-aaa", 5, listOf(fish, dog), mapOf(dog to listOf("魚")))
        val rated = JSONArray().put(JSONObject().put("id", 1).put("key", dog).put("time", 1000L).put("ease", 3))
        WordSync.words = WordSync.elsewhere(rated, carded, "SM-T970-bbb", setOf(dog), { fsrs }, now = 1000L)
        assertEquals("the shared card's form is the higher state", "k", WordSync.state("ja", "魚"))
        assertTrue("but 魚 is carded only on the other device: still refused", WordSync.refusal("ja", fish)!!.contains("Chromecast-aaa"))
    }
}
