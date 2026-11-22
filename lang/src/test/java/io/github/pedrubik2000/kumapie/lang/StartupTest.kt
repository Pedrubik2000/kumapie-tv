package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Gate
import io.github.pedrubik2000.kumapie.data.RdGate
import io.github.pedrubik2000.kumapie.data.Startup
import io.github.pedrubik2000.kumapie.update.Updater
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Startup]: which gate both apps show first, the onboarded flag, the update check. */
class StartupTest {
    @Test fun firstRunIsSettings() = assertEquals(Gate.FIRST_RUN, Startup.gate("", standalone = false, onboarded = false))

    @Test fun noPcNotOnboardedIsOnboarding() = assertEquals(Gate.ONBOARDING, Startup.gate("", standalone = true, onboarded = false))

    @Test fun noPcOnboardedIsTheApp() = assertEquals(Gate.APP, Startup.gate("", standalone = true, onboarded = true))

    @Test fun aServerIsTheApp() {
        assertEquals(Gate.APP, Startup.gate("http://pc:8769", standalone = false, onboarded = false))
        assertEquals(Gate.APP, Startup.gate("http://pc:8769", standalone = true, onboarded = false))
    }

    @Test fun onboardedIsTheKeyBothAppsWrote() {
        // The TV's TV_ONBOARDED and the phone's ONBOARDED were both "onboarded": nobody's flag is lost.
        assertEquals("onboarded", Startup.ONBOARDED)
        assertTrue(Startup.onboarded(mapOf("onboarded" to true)))
        assertFalse(Startup.onboarded(mapOf("onboarded" to false)))
        assertFalse(Startup.onboarded(emptyMap<String, Any?>()))
    }

    @Test fun noPcOnboardingNeedsAKeyRealDebridAccepts() {
        fun g(check: String?, key: String = "k", failed: Int = 0) = Startup.rdGate(pc = false, proof = false, key = key, check = check, failed = failed)
        assertEquals(RdGate.MISSING, g("ok", key = " "))
        assertEquals(RdGate.OK, g("ok"))
        assertEquals(RdGate.REJECTED, g("rejected"))
        assertEquals(RdGate.REJECTED, g("rejected", failed = 5))
        assertEquals(listOf(true, false, false, false, true, true), listOf(RdGate.OK, RdGate.MISSING, RdGate.REJECTED, RdGate.UNREACHABLE,
            RdGate.UNCHECKED, RdGate.NOT_ASKED).map { it.mayFinish })
    }

    @Test fun aNetworkFailureLetsHimGoOnTheThirdTime() {
        val tries = (0 until Startup.RD_TRIES).map { Startup.rdGate(false, false, "k", if (it == 0) "offline" else "http 503", it) }
        assertEquals(listOf(RdGate.UNREACHABLE, RdGate.UNREACHABLE, RdGate.UNCHECKED), tries)
    }

    @Test fun aPcOrTheProofAddonIsNotAsked() {
        assertEquals(RdGate.NOT_ASKED, Startup.rdGate(pc = true, proof = false, key = "", check = null, failed = 0))
        assertEquals(RdGate.NOT_ASKED, Startup.rdGate(pc = false, proof = true, key = "", check = null, failed = 0))
        assertTrue(Startup.proof(listOf("https://torrentio.strem.fun/manifest.json", "http://10.0.2.2:8795/manifest.json")))
        assertFalse(Startup.proof(listOf("http://10.0.2.2:8796/manifest.json", "https://10.0.2.2:8795/manifest.json", "http://10.0.2.20:8795/manifest.json")))
    }

    @Test fun theKeyIsTheTokenElseTorrentiosAddress() {
        assertEquals("abc", Startup.rdKey("abc", listOf("https://torrentio.strem.fun/realdebrid=xyz/manifest.json")))
        assertEquals("xyz", Startup.rdKey("", listOf("https://torrentio.strem.fun/realdebrid=xyz/manifest.json")))
        assertEquals("", Startup.rdKey("", listOf("https://torrentio.strem.fun/providers=yts|qualityfilter=480p/manifest.json")))
    }

    @Test fun debugBuildDoesNotCheck() = runBlocking {
        var asked = 0
        val release = Updater.Release("9.9.9", "u", "")
        assertNull(Startup.update(debug = true) { asked++; release })
        assertEquals(0, asked)
        assertEquals(release, Startup.update(debug = false) { asked++; release })
        assertEquals(1, asked)
        assertNull(Startup.update(debug = false) { error("offline") })
    }
}
