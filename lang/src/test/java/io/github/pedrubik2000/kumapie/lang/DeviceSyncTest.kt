package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.i18n.Tr
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Core plan 16b: pairing, the guard and one exchange between two devices (the client sends, the answering one merges and answers its view). */
class DeviceSyncTest {
    private val files = DeviceFileTest()
    private val secret = "4f".repeat(32)

    @Test fun bothSidesShowTheSameCodeAndOnlyThePairSigns() {
        assertEquals(DeviceSync.code(secret), DeviceSync.code(secret))
        assertTrue(Regex("[0-9]{4}").matches(DeviceSync.code(secret)))
        assertNotEquals(DeviceSync.code(secret), DeviceSync.code("50".repeat(32)))
        val body = """{"device":"TV"}""".toByteArray()
        val sig = DeviceSync.sign(secret, body)
        assertTrue(DeviceSync.verify(secret, body, sig))
        assertFalse(DeviceSync.verify(secret, """{"device":"TW"}""".toByteArray(), sig))
        assertFalse(DeviceSync.verify("50".repeat(32), body, sig))
        assertFalse(DeviceSync.verify(secret, body, null))
    }

    @Test fun aPcStaysTheServerAndUnpairedRequestsAreRefused() {
        val body = "{}".toByteArray()
        val sig = DeviceSync.sign(secret, body)
        assertEquals(409, DeviceSync.refusal("http://192.168.0.10:8769", secret, body, sig)?.status)
        assertEquals(403, DeviceSync.refusal("", null, body, sig)?.status)
        assertEquals(403, DeviceSync.refusal("", secret, body, DeviceSync.sign("50".repeat(32), body))?.status)
        assertNull(DeviceSync.refusal("", secret, body, sig))
    }

    @Test fun oneExchangeLeavesBothWithTheUnionAndASecondMovesNothing() {
        val tv = files.chromecast()
        val tab = files.tablet()
        fun exchange(): Pair<DeviceFile.Plan, DeviceFile.Plan> {
            val there = tab.import(tv.export())
            val answer = tab.export().apply { remove("cards"); put("moved", DeviceSync.counts(there)) }
            return there to tv.import(answer)
        }
        val (there, back) = exchange()
        assertTrue(there.changes)
        assertTrue(back.changes)
        assertTrue(DeviceSync.moved(there.counts).contains("Review events +2"))
        assertEquals(tab.events.toString(), tv.events.toString())
        assertEquals(tab.view().marked, tv.view().marked)
        assertEquals(tab.view().watch, tv.view().watch)
        assertEquals(tab.known, tv.known)
        val (again, againBack) = exchange()
        assertFalse(again.changes)
        assertFalse(againBack.changes)
        assertEquals("nothing new", DeviceSync.moved(again.counts))
    }

    @Test fun whatTheAnsweringDeviceTookShowsInThisDevicesLanguage() {
        val there = files.tablet().import(files.chromecast().export())
        val answer = files.tablet().export().put("moved", DeviceSync.counts(there))
        try {
            Tr.use("es")
            assertTrue(DeviceSync.gave(answer), DeviceSync.gave(answer).contains("Repasos +2") && "Review" !in DeviceSync.gave(answer))
            assertEquals("nada nuevo", DeviceSync.gave(JSONObject().put("moved", JSONArray())))
        } finally { Tr.use("en") }
        assertEquals("an older device's line", "Marks +1", DeviceSync.gave(JSONObject().put("moved", "Marks +1")))
    }

    @Test(expected = IllegalArgumentException::class) fun anotherPersonsFileIsNotMerged() {
        files.tablet().import(files.chromecast().export().put("person", "giovanna"))
    }

    @Test fun theHubsPromptCodeIsTheCodeTheClientShows() {
        val a = DeviceSync.Asking("Tablet", secret, "pubkey")
        assertEquals(a.code, DeviceSync.shown(secret, "pubkey", JSONObject().put("setup", true)))
        assertNotEquals(DeviceSync.code(secret), a.code)
    }
}
