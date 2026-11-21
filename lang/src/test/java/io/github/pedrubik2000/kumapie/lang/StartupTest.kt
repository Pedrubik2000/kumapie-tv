package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Gate
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
