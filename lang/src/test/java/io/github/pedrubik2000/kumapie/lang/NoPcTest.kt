package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.NoPc
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Without a PC (Settings: no server) no call reaches the network: the tablet logged "no protocol" for each (2026-11-19). */
class NoPcTest {
    private fun refused(call: suspend Api.() -> Unit) {
        try { runBlocking { Api("", "pedro").call() }; fail("asked the PC") } catch (_: NoPc) { }
    }

    @Test fun everyPcCallIsRefusedBeforeConnecting() {
        refused { knownWords("de", listOf("Haus")) }
        refused { knownLists("de", JSONObject()) }
        refused { srsSync("de", JSONObject()) }
        refused { deviceWatch(mapOf("de" to mapOf("2026-11-19" to 60.0))) }
        refused { info() }
    }

    @Test fun parsingStillWorksWithoutAPc() {
        assertEquals("http://x/a.mp4", Api("").url("http://x/a.mp4"))
    }
}
