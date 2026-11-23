package io.github.pedrubik2000.kumapie.lang

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    private inner class Device(val dev: DeviceFileTest.Dev, val cards: List<String>, val tapped: Map<String, String> = emptyMap()) {
        /** [Language.cardKeys] here: the card's keys and the form its sentence bolds (Language.tapped). */
        fun keys(note: AnkiCards.Note) = CardIndex.japaneseKeys(note) + listOfNotNull(tapped[note.fields["Word"]])

        fun export(): JSONObject = dev.export().put("carded", WordSync.carded(
            WordSync.own(dev.name, dev.made, cards.map(::key), tapped.mapKeys { key(it.key) }.mapValues { listOf(it.value) }), dev.carded, dev.name))

        fun import(file: JSONObject): DeviceFile.Plan = dev.import(file)

        fun known(): List<String> {
            val replay = DeviceFile.replay(dev.events, fsrs)
            val due = cards.map { w -> replay[key(w)]?.let { it to it.lastReview + fsrs.interval(it.state.stability) * Srs.DAY } }
            val cs = cards.indices.map { i -> AnkiCards.Card(i + 1L, i + 1L, due[i] != null, due[i]?.first?.state?.stability, (due[i]?.second ?: 0) / 1000, type = if (due[i] == null) 0 else 2) }
            val queue = cards.indices.mapNotNull { i -> when (Srs.state(due[i]?.second, now)) { "d" -> i + 1L to "review"; "b" -> i + 1L to "new"; else -> null } }.toMap()
            WordSync.words = WordSync.elsewhere(dev.events, dev.carded, dev.name, cards.map(::key).toSet(), { fsrs }, now)
            val index = CardIndex("ja").apply { build(cards.mapIndexed { i, w -> note(i + 1L, w) }, cs, queue, ::keys) }
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

    /**
     * Core plan 16e, the real 85: a rated Chromecast card 罠[わな] whose sentence bolds わな is white there under わな too
     * (the TV's card index keys the tapped form), but the tablet heard only the card's key: わな counted on one device.
     * Also the other way: the tablet's own new card of that form is blue there and white on the Chromecast.
     */
    @Test fun aCardsTappedFormCountsOnBothDevices() {
        val cc = Device(DeviceFileTest.Dev("Chromecast-aaa", JSONObject().put("Chromecast-aaa", files.part(0.0)), emptyMap(), emptyList(), mutableMapOf(),
            JSONArray().put(rated(3, "罠[わな]"))), listOf("罠[わな]"), mapOf("罠[わな]" to "わな"))
        val tab = Device(DeviceFileTest.Dev("SM-T970-bbb", JSONObject().put("SM-T970-bbb", files.part(0.0)), emptyMap(), emptyList(), mutableMapOf(),
            JSONArray()), listOf("ワナ[わな]"), mapOf("ワナ[わな]" to "わな"))
        assertTrue("わな" in cc.known())
        sync(cc, tab)
        assertEquals(listOf("わな", "罠"), cc.known())
        assertEquals("the Chromecast's tapped form, white as there", cc.known(), tab.known())
    }

    /** Core plan 16f, the export's "counted" section: the list Home counts per language, and no import ever reads it back. */
    @Test fun theExportedCountedSectionIsTheHomeListAndImportIgnoresIt() {
        val cc = chromecast()
        val tab = tablet()
        sync(cc, tab)
        val counted = DeviceFile.counted(mapOf("ja" to cc.known()))
        assertEquals(JSONArray(cc.known()).toString(), counted.getJSONArray("ja").toString())
        val plain = cc.export()
        val withCounted = JSONObject(plain.toString()).put("counted", counted)
        val before = tab.known()
        val a = tab.import(plain)
        val b = tab.import(withCounted)
        assertEquals(a.changes, b.changes)
        assertEquals(a.known.keys, b.known.keys)
        assertEquals(before, tab.known())
        assertEquals(cc.known(), tab.known())
    }

    /**
     * Core plan 16f, the real 12(tablet 17,435, Chromecast 17,423): the same card 罠[わな] on both devices, each with its own
     * tapped form. Each device threw away the other's extra form because the key is carded here, so each counted only its own.
     */
    @Test fun aFormTheOtherDeviceHoldsForASharedCardCountsHereToo() {
        val cc = Device(DeviceFileTest.Dev("Chromecast-aaa", JSONObject().put("Chromecast-aaa", files.part(0.0)), emptyMap(), emptyList(), mutableMapOf(),
            JSONArray().put(rated(3, "罠[わな]"))), listOf("罠[わな]"), mapOf("罠[わな]" to "罠を"))
        val tab = Device(DeviceFileTest.Dev("SM-T970-bbb", JSONObject().put("SM-T970-bbb", files.part(0.0)), emptyMap(), emptyList(), mutableMapOf(),
            JSONArray()), listOf("罠[わな]"), mapOf("罠[わな]" to "罠が"))
        sync(cc, tab)
        sync(cc, tab)
        assertTrue("罠が" in cc.known())
        assertTrue("罠を" in tab.known())
        assertEquals(cc.known(), tab.known())
        assertNull("no Already a card for the shared card's forms", WordSync.refusal("ja", key("罠[わな]")))
        assertNull(WordSync.refusal("ja", "ja|罠を"))
    }

    /** The two-emulator sync proof (core plan 16e): the TV rates its card of フランメ, the tablet marks 魂, they sync; the tablet makes its own card of フランメ after "Already a card on <TV>", they sync again. */
    @Test fun aWordCardedOnBothDevicesAndAMarkCountTheSameOnBoth() {
        val tv = Device(DeviceFileTest.Dev("sdk_google_atv64-aaa", JSONObject().put("sdk_google_atv64-aaa", files.part(0.0)), emptyMap(), emptyList(), mutableMapOf(),
            JSONArray().put(rated(0, "フランメ"))), listOf("フランメ"))
        val tabDev = DeviceFileTest.Dev("sdk_gphone64-bbb", JSONObject().put("sdk_gphone64-bbb", files.part(0.0, marks = mapOf("魂" to (true to 100L)))),
            emptyMap(), emptyList(), mutableMapOf(), JSONArray())
        sync(tv, Device(tabDev, emptyList()))
        val tab = Device(tabDev, listOf("フランメ"))
        assertEquals(listOf("フランメ", "魂"), tab.known())
        assertEquals("the TV before its next sync", listOf("フランメ", "魂"), tv.known())
        sync(tv, tab)
        assertEquals(listOf("フランメ", "魂"), tv.known())
        assertEquals(listOf("フランメ", "魂"), tab.known())
    }
}
