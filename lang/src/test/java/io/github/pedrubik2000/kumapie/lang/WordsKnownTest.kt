package io.github.pedrubik2000.kumapie.lang

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Core plan 16d: after a sync every device counts the same "Words known" (KnownWords.knownList = CardIndex.known). */
class WordsKnownTest {
    private val files = DeviceFileTest()
    private val fsrs = Fsrs6()
    private val now = System.currentTimeMillis()

    @After fun clear() { WordSync.words = emptyMap() }

    private fun rated(daysAgo: Long, word: String) = (now - daysAgo * Srs.DAY).let { t ->
        JSONObject().put("id", t).put("key", key(word)).put("time", t).put("ease", 3).put("device", "x")
    }

    private fun note(id: Long, word: String) = AnkiCards.Note(id, mapOf("Word" to word), emptySet())

    private fun key(word: String) = "ja|" + CardIndex.japaneseKeys(note(0, word)).first()

    /** A device as DeviceFiles and SrsCards see it: its own cards' schedules come from the word events (16c). */
    private inner class Device(val dev: DeviceFileTest.Dev, val cards: List<String>) {
        fun export(): JSONObject = dev.export().put("carded", WordSync.carded(WordSync.own(dev.name, dev.made, cards.map(::key)), dev.carded, dev.name))

        fun import(file: JSONObject): DeviceFile.Plan = dev.import(file)

        fun known(): List<String> {
            val replay = DeviceFile.replay(dev.events, fsrs)
            val due = cards.map { w -> replay[key(w)]?.let { it to it.lastReview + fsrs.interval(it.state.stability) * Srs.DAY } }
            val cs = cards.indices.map { i -> AnkiCards.Card(i + 1L, i + 1L, due[i] != null, due[i]?.first?.state?.stability, (due[i]?.second ?: 0) / 1000, type = if (due[i] == null) 0 else 2) }
            val queue = cards.indices.mapNotNull { i -> when (Srs.state(due[i]?.second, now)) { "d" -> i + 1L to "review"; "b" -> i + 1L to "new"; else -> null } }.toMap()
            WordSync.words = WordSync.elsewhere(dev.events, dev.carded, dev.name, cards.map(::key).toSet(), { fsrs }, now)
            val index = CardIndex("ja").apply { build(cards.mapIndexed { i, w -> note(i + 1L, w) }, cs, queue, CardIndex::japaneseKeys) }
            val imported = dev.known["ja"]?.second.orEmpty()
            return index.known(imported, dev.view().marked.mapNotNull { markWord(it, null) }.toSet(), { it in imported }, { null })
        }
    }

    private fun chromecast() = Device(DeviceFileTest.Dev("Chromecast-aaa", JSONObject().put("Chromecast-aaa", files.part(0.0, marks = mapOf("猫" to (true to 100L)))),
        mapOf("ja" to (50L to setOf("犬", "鳥", "魚"))), emptyList(), mutableMapOf(), JSONArray().put(rated(3, "雨[あめ]"))),
        listOf("雨[あめ]", "花[はな]"))

    private fun tablet() = Device(DeviceFileTest.Dev("SM-T970-bbb", JSONObject().put("SM-T970-bbb", files.part(0.0, marks = mapOf("猫" to (false to 50L)))),
        mapOf("ja" to (20L to setOf("犬"))), emptyList(), mutableMapOf(), JSONArray().put(rated(2, "山[やま]")).put(rated(5, "空[そら]"))),
        listOf("山[やま]", "空[そら]", "魚[さかな]"))

    private fun sync(client: Device, answering: Device): Pair<DeviceFile.Plan, DeviceFile.Plan> {
        val there = answering.import(client.export())
        return there to client.import(answering.export().apply { remove("cards") })
    }

    @Test fun bothDevicesCountTheSameWordsAfterASyncAndASecondSyncChangesNothing() {
        val cc = chromecast()
        val tab = tablet()
        assertEquals(listOf("犬", "猫", "雨", "魚", "鳥").sorted(), cc.known())
        assertEquals(listOf("犬", "山", "空").sorted(), tab.known())

        sync(cc, tab)
        val words = listOf("犬", "猫", "山", "空", "雨", "鳥").sorted()
        assertEquals("a studied card on the other device counts here", words, cc.known())
        assertEquals(words, tab.known())

        val (again, back) = sync(cc, tab)
        assertFalse(again.changes)
        assertFalse(back.changes)
        assertEquals(words, cc.known())
        assertEquals(words, tab.known())
    }

    @Test fun anImportedWordWithANewCardOnTheOtherDeviceStopsCountingHereToo() {
        val cc = chromecast()
        val tab = tablet()
        assertTrue("魚" in cc.known())
        sync(cc, tab)
        assertFalse("blue as on the tablet, where it is a new card", "魚" in cc.known())
        assertFalse("魚" in tab.known())
        assertEquals(cc.known().size, tab.known().size)
    }
}
