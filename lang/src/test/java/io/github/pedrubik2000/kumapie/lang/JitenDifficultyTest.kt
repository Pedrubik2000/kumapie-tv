package io.github.pedrubik2000.kumapie.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

const val DETAIL = """{"data":{"mainDeck":{"deckId":40963,"wordCount":44509,"uniqueWordCount":5239,"difficulty":2,"difficultyRaw":2.68,
"speechDuration":26007157,"speechSpeed":279.09},"parentDeck":null,"subDecks":[]}}"""

class Canned(private val answers: (String) -> Reply) : JitenTransport {
    val calls = mutableListOf<Pair<String, String>>()
    override fun get(url: String, userAgent: String): Reply { calls += url to userAgent; return answers(url) }
}

fun jitenOk(url: String): Reply = when {
    "by-link-id/4/154587" in url -> Reply(200, "[40963]")
    "by-link-id" in url -> Reply(200, "[]")
    "40963/detail" in url -> Reply(200, DETAIL)
    else -> Reply(404, "")
}

class JitenDifficultyTest {
    @Test fun parsesTheDetail() {
        val d = JitenApi.parseDetail(40963, DETAIL)!!
        assertEquals(2.68, d.raw, 0.0)
        assertEquals(54, d.percent)
        assertEquals(2, d.band)
        assertEquals("Average", d.label)
        assertEquals(44509, d.words)
        assertEquals(433, d.speechMinutes)
        assertEquals("https://jiten.moe/decks/media/40963/detail", d.url)
    }

    @Test fun missingFieldsAreNull() {
        val d = JitenApi.parseDetail(7, """{"data":{"mainDeck":{"difficulty":3}}}""")!!
        assertEquals(3.0, d.raw, 0.0)
        assertNull(d.words)
        assertNull(d.speechMinutes)
        assertEquals(7, d.deckId)
        assertNull(JitenApi.parseDetail(7, """{"data":{"mainDeck":{}}}"""))
        assertNull(JitenApi.parseDetail(7, """{"data":null}"""))
    }

    @Test fun bandsAtEachCut() {
        val at = listOf(0.0 to 0, 1.0 to 0, 1.01 to 1, 2.0 to 1, 2.01 to 2, 3.0 to 2, 3.01 to 3, 4.0 to 3, 4.01 to 4, 4.94 to 4, 4.95 to 5, 5.0 to 5)
        for ((raw, band) in at) assertEquals("$raw", band, TitleDifficulty.bandOf(raw))
        assertEquals(listOf("Beginner", "Easy", "Average", "Hard", "Expert", "Insane"), (0..5).map(TitleDifficulty::labelOf))
    }

    @Test fun percentRoundsAndClamps() {
        assertEquals(0, TitleDifficulty(0.0, 1, null, null).percent)
        assertEquals(100, TitleDifficulty(5.0, 1, null, null).percent)
        assertEquals(100, TitleDifficulty(7.0, 1, null, null).percent)
        assertEquals(0, TitleDifficulty(-1.0, 1, null, null).percent)
        assertEquals(54, TitleDifficulty(2.68, 1, null, null).percent)
    }

    @Test fun findsTheDeckAndTheRating() {
        val t = Canned(::jitenOk)
        val api = JitenApi(t)
        assertEquals(40963, api.deck(154587))
        val got = api.lookup(154587) as JitenResult.Got
        assertEquals(2.68, got.value!!.raw, 0.0)
        assertTrue(t.calls.all { it.second == Jiten.userAgent() && it.second.startsWith("kumapie/") })
    }

    @Test fun notOnJitenIsAnAnswer() {
        assertNull((JitenApi(Canned(::jitenOk)).lookup(5) as JitenResult.Got).value)
        assertNull((JitenApi(Canned { Reply(404, "") }).lookup(5) as JitenResult.Got).value)
    }

    @Test fun failuresAreTold() {
        assertEquals(30, (JitenApi(Canned { Reply(429, "", 30) }).lookup(5) as JitenResult.Limited).seconds)
        assertEquals(60, (JitenApi(Canned { Reply(429, "") }).lookup(5) as JitenResult.Limited).seconds)
        assertTrue(JitenApi(Canned { Reply(418, "") }).lookup(5) === JitenResult.Refused)
        assertTrue(JitenApi(Canned { Reply(403, "") }).lookup(5) === JitenResult.Refused)
        assertTrue(JitenApi(Canned { Reply(500, "") }).lookup(5) === JitenResult.Failed)
        assertTrue(JitenApi(Canned { throw java.io.IOException("down") }).lookup(5) === JitenResult.Failed)
        assertTrue(JitenApi(Canned { Reply(200, "<html>") }).lookup(5) === JitenResult.Failed)
    }

    @Test fun frequencyListSendsTheUserAgentToo() {
        val t = Canned { Reply(200, "Word,Form,Rank\nの,の,1\nに,に,2\nは,は,3\n") }
        assertEquals(setOf("の", "に"), Jiten.top(2, t))
        assertEquals(Jiten.userAgent(), t.calls.single().second)
        assertEquals("kumapie/0.0.0 (https://github.com/Pedrubik2000/kumapie-tv)", Jiten.userAgent())
    }

    @Test fun pacedTransportKeepsHalfASecond() {
        var now = 0L
        val t = Canned { Reply(200, "") }
        val paced = JitenPaced(t, 500, { now }, { now += it })
        val stamps = (1..3).map { paced.get("u", "ua"); now }
        assertEquals(listOf(500L, 1000L, 1500L), stamps)
    }

    @Test fun storeKeepsValuesAndExpiresAfterAWeek() {
        val f = File.createTempFile("jiten", ".json")
        var now = 1_000L
        val s = JitenStore(f) { now }
        s.put(1, TitleDifficulty(2.5, 9, 100, 10))
        s.put(2, null)
        s.linkKitsu("kitsu:1", 1)
        val again = JitenStore(f) { now }
        assertEquals(9, again.entry(1)!!.value!!.deckId)
        assertNull(again.entry(2)!!.value)
        assertEquals(1, again.anilistOf("kitsu:1"))
        assertTrue(again.fresh(2))
        now += JitenStore.WEEK_MS - 1_000
        assertTrue(again.fresh(2))
        now += 1_000
        assertTrue(!again.fresh(2))
        f.delete()
    }
}
