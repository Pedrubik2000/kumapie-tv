package io.github.pedrubik2000.kumapie.lang

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Core plan 16a: one device's export file merged into another (two stores, both directions, twice, skew, deletes). */
class DeviceFileTest {
    private val day = Srs.DAY

    /** One device's stores, as DeviceFiles reads and writes them. */
    internal class Dev(
        val name: String, var parts: JSONObject, var known: Map<String, Pair<Long, Set<String>>>,
        var personal: List<PersonalDictionary.Entry>, val streams: MutableMap<String, Any?>, var events: JSONArray,
        val backups: MutableMap<String, JSONObject> = HashMap(), var made: Long = 1000,
    ) {
        fun export() = JSONObject().put("format", DeviceFile.FORMAT).put("device", name).put("person", "me").put("made", made++)
            .put("progress", parts)
            .put("known", JSONObject().apply { known.forEach { (l, p) -> put(l, JSONObject().put("time", p.first).put("words", JSONArray(p.second.sorted()))) } })
            .put("personal", PersonalDictionary.toJson(personal))
            .put("streams", JSONObject().apply { streams.forEach { (k, v) -> put(k, if (v is Set<*>) JSONArray(v) else v) } })
            .put("events", events)
            .put("cards", JSONObject().put("ja", JSONObject().put("cards", JSONArray().put(JSONObject().put("id", 1).put("modified", 5)))))

        fun mine(from: String) = DeviceFile.Mine(name, parts, known, personal, streams, events, backups[from])

        fun import(file: JSONObject): DeviceFile.Plan = DeviceFile.plan(mine(file.getString("device")), file).also { p ->
            parts = JSONObject(p.others.toString()).put(name, parts.getJSONObject(name))
            known = known + p.known
            personal = p.personal
            streams.putAll(p.streams)
            events = p.events
            if (p.backup) backups[p.from] = file
        }

        fun view() = Progress.merge(parts.keys().asSequence().map { parts.getJSONObject(it) }.toList())
    }

    internal fun part(watch: Double, pos: Map<String, Pair<Double, Long>> = emptyMap(), marks: Map<String, Pair<Boolean, Long>> = emptyMap()) =
        JSONObject().put("watch", JSONObject().put("2026-11-20", watch))
            .put("pos", JSONObject().apply { pos.forEach { (e, p) -> put(e, JSONArray().put(p.first).put(p.second)) } })
            .put("marked", JSONObject().apply { marks.forEach { (w, m) -> put(w, JSONArray().put(m.first).put(m.second)) } })
            .put("lookups", JSONObject())

    internal fun event(id: Long, key: String, ease: Int = 3) = JSONObject().put("id", id).put("key", key).put("time", id / 1000).put("ease", ease).put("device", "x")

    internal fun chromecast() = Dev("Chromecast-aaa", JSONObject().put("Chromecast-aaa", part(3600.0, mapOf("ep1" to (600.0 to 200L)), mapOf("猫" to (true to 100L)))),
        mapOf("ja" to (50L to setOf("犬", "鳥"))),
        listOf(PersonalDictionary.Entry("ja", "フリーレン", meaning = "Frieren", time = 10)),
        mutableMapOf("alone_saved" to """[{"id":"kitsu:1","name":"Frieren"}]""", "alone_seen" to setOf("kitsu:1:1"),
            "alone_last_kitsu:1" to """{"id":"kitsu:1:2","at":300}""", "alone_pos_kitsu:1:2" to 500f),
        JSONArray().put(event(1_000_000, "ja|猫\tねこ")).put(event(2_000_000, "ja|犬\tいぬ")))

    internal fun tablet() = Dev("SM-T970-bbb", JSONObject().put("SM-T970-bbb", part(1200.0, mapOf("ep2" to (60.0 to 250L)), mapOf("猫" to (false to 50L)))),
        mapOf("ja" to (20L to setOf("犬"))), emptyList(), mutableMapOf("alone_saved" to "[]"),
        JSONArray().put(event(3_000_000, "ja|猫\tねこ")))

    @Test fun bothDirectionsTwiceAndTheSameEverywhere() {
        val cc = chromecast()
        val tab = tablet()
        val p = tab.import(cc.export())
        val counts = p.counts.associateBy { it.what }
        assertEquals(DeviceFile.Count("Review events", 2, 0, 1), counts["Review events"])
        assertEquals(DeviceFile.Count("Known words", 1, 0, 1), counts["Known words"])  // Chromecast's list is newer: 鳥 added
        assertEquals(DeviceFile.Count("Marks", 1, 0, 0), counts["Marks"])              // 猫 marked known later on the Chromecast
        assertEquals(DeviceFile.Count("Places", 2, 0, 1), counts["Places"])            // ep1 + Frieren's stream; ep2 kept
        assertEquals(DeviceFile.Count("Cards backed up", 1, 0, 0), counts["Cards backed up"])
        assertEquals(1, counts["Personal dictionary"]?.add)
        assertEquals(1, counts["Kept titles"]?.add)
        assertEquals(4800.0, tab.view().watch["2026-11-20"]!!, 0.0) // per-day time per device, summed

        assertFalse("importing the same file again changes nothing", tab.import(cc.export()).changes)

        cc.import(tab.export())
        assertFalse(cc.import(tab.export()).changes)
        assertEquals(tab.view().watch, cc.view().watch)
        assertEquals(tab.view().marked, cc.view().marked)
        assertEquals(tab.view().pos, cc.view().pos)
        assertEquals(tab.events.toString(), cc.events.toString())
        assertEquals(tab.known, cc.known)
        assertEquals(tab.personal, cc.personal)
        assertEquals(setOf("kitsu:1:1"), tab.streams["alone_seen"])
    }

    @Test fun aSkewedClockLosesNoTimeAndAnOlderCopyOfAPartNeverWins() {
        val tab = tablet()
        tab.parts.put("SM-T970-bbb", part(1200.0, mapOf("ep1" to (900.0 to 9_999_999L)))) // the tablet's clock far ahead
        val cc = chromecast()
        tab.import(cc.export())
        assertEquals(4800.0, tab.view().watch["2026-11-20"]!!, 0.0)
        assertEquals(900.0, tab.view().pos["ep1"]!!, 0.0) // a place: newest wins, as with the PC

        val stale = chromecast().apply { parts = JSONObject().put("Chromecast-aaa", part(100.0)) }
        val relay = Dev("Phone-ccc", JSONObject().put("Phone-ccc", part(0.0)).put("Chromecast-aaa", stale.parts.getJSONObject("Chromecast-aaa")),
            emptyMap(), emptyList(), mutableMapOf(), JSONArray())
        tab.import(relay.export())
        assertEquals(4800.0, tab.view().watch["2026-11-20"]!!, 0.0) // the relayed older Chromecast part didn't replace the newer one
    }

    @Test fun aDeleteOnOneSideWinsWhenItIsNewer() {
        val cc = chromecast()
        val tab = tablet()
        tab.import(cc.export())
        cc.personal = listOf(PersonalDictionary.Entry("ja", "フリーレン", time = 20, deleted = true))
        cc.parts.getJSONObject("Chromecast-aaa").getJSONObject("marked").put("猫", JSONArray().put(false).put(400L))
        val p = tab.import(cc.export())
        assertTrue(tab.personal.single().deleted)
        assertEquals(1, p.counts.single { it.what == "Personal dictionary" }.change)
        assertEquals(1, p.counts.single { it.what == "Marks" }.change)
        assertFalse("猫" in tab.view().marked)

        cc.personal = listOf(PersonalDictionary.Entry("ja", "フリーレン", meaning = "old", time = 15)) // older than the delete
        tab.import(cc.export())
        assertTrue(tab.personal.single().deleted)
    }

    @Test fun anOlderExportKeepsTheNewerBackup() {
        val cc = chromecast()
        val old = cc.export()
        val tab = tablet()
        tab.import(cc.export())
        val p = tab.import(old)
        assertFalse(p.backup)
        assertEquals(DeviceFile.Count("Cards backed up", 0, 0, 1), p.counts.single { it.what == "Cards backed up" })
    }

    @Test fun streamsTakeTheNewerTitlePlaceOnly() {
        val mine = mapOf<String, Any?>("alone_last_kitsu:1" to """{"id":"kitsu:1:3","at":900}""", "alone_pos_kitsu:1:3" to 100f, "alone_pos_kitsu:1:2" to 1f)
        val theirs = JSONObject().put("alone_last_kitsu:1", """{"id":"kitsu:1:2","at":300}""").put("alone_pos_kitsu:1:2", 500.0)
            .put("alone_last_kitsu:2", """{"id":"kitsu:2:1","at":5}""").put("alone_pos_kitsu:2:1", 7.0)
        val out = DeviceFile.streams(mine, theirs)
        assertEquals(setOf("alone_last_kitsu:2", "alone_pos_kitsu:2:1"), out.keys)
        assertEquals(7f, out["alone_pos_kitsu:2:1"])
    }

    @Test fun revlogBecomesWordEventsTheSameEveryTime() {
        val dump = JSONObject()
            .put("cards", JSONArray()
                .put(JSONObject().put("id", 1).put("word", "上手[じょうず]:3").put("fields", "{}"))
                .put(JSONObject().put("id", 2).put("word", "verstehe[→ verstehen]").put("fields", "{}")))
            .put("revlog", JSONArray()
                .put(JSONObject().put("id", 7_000).put("card", 1).put("time", 7L).put("rating", 3).put("device", "TV"))
                .put(JSONObject().put("id", 2_000).put("card", 1).put("time", 2L).put("rating", 1).put("device", "TV"))
                .put(JSONObject().put("id", (2 * day + 9) * 1000).put("card", 1).put("time", 2 * day + 9).put("rating", 3).put("device", "TV"))
                .put(JSONObject().put("id", 5_000).put("card", 2).put("time", 5L).put("rating", 4).put("device", "TV"))
                .put(JSONObject().put("id", 6_000).put("card", 99).put("time", 6L).put("rating", 4).put("device", "TV")))
        val de = DeviceFile.events("de", dump)
        val a = DeviceFile.events("ja", dump)
        assertEquals(a.map { it.toString() }, DeviceFile.events("ja", dump).map { it.toString() })
        assertEquals(listOf(2_000L, 5_000L, 7_000L, (2 * day + 9) * 1000), a.map { it.getLong("id") }) // card 99 has no card: dropped
        assertEquals("ja|上手\tじょうず", a[0].getString("key"))
        assertEquals("de|verstehe", de.single { it.getLong("id") == 5_000L }.getString("key"))

        val union = DeviceFile.union(DeviceFile.union(JSONArray(), a), a)
        assertEquals(a.size, union.length())
        val f = Fsrs6()
        val byWord = DeviceFile.replay(union, f)["ja|上手\tじょうず"]
        assertEquals(Srs.replay(listOf(2L to 1, 7L to 3, 2 * day + 9 to 3), f), byWord)
    }

    @Test(expected = IllegalArgumentException::class) fun notAnExportFile() {
        DeviceFile.plan(tablet().mine("x"), JSONObject().put("device", "x"))
    }
}
