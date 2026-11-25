package io.github.pedrubik2000.kumapie

import io.github.pedrubik2000.kumapie.data.Onboarding
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.lang.DeviceSync
import io.github.pedrubik2000.kumapie.lang.DeviceSync.Poll
import io.github.pedrubik2000.kumapie.lang.MainDeviceFlow
import io.github.pedrubik2000.kumapie.lang.MainDeviceFlow.Hub
import io.github.pedrubik2000.kumapie.lang.MainDeviceFlow.Phase
import io.github.pedrubik2000.kumapie.lang.Pairing
import io.github.pedrubik2000.kumapie.lang.SetupBundle.Got
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private class FakePairing(var polls: List<Poll>, private val got: Got = Got.Ok(emptyMap(), emptyMap())) : Pairing {
    var asked: Hub? = null
    var asks = 0
    var oldHub = false
    var calls = 0
    var key = true
    var fails: Exception? = null
    var forgot = 0
    var applied: Set<String>? = null
    private var at = 0
    var pcs = listOf("192.168.0.10:8769")
    var pcUp = setOf<String>()
    var triedPc = mutableListOf<String>()
    override suspend fun discover(ms: Long) = listOf("Tab S7+" to "192.168.0.154:47823")
    override suspend fun discoverPcs(ms: Long) = pcs
    override suspend fun usePc(url: String): String {
        triedPc += url
        return if (url in pcUp) { Settings(RuntimeEnvironment.getApplication()).server = url; "" } else "No answer from $url"
    }
    override suspend fun ask(name: String, address: String): DeviceSync.Asked { fails?.let { throw it }; asks++; asked = Hub(name, address); return DeviceSync.Asked("4829", oldHub) }
    override suspend fun allowed(): Poll { calls++; return polls[minOf(at++, polls.size - 1)] }
    fun script(p: List<Poll>) { polls = p; at = 0 }
    override suspend fun fetch() = got
    override fun apply(got: Got.Ok, ticked: Set<String>) { applied = ticked }
    override fun peer() = asked
    override fun forget() { forgot++ }
    override fun hasKey() = key
    override fun me() = "Phone"
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainDeviceFlowTest {
    private val prefs = Settings(RuntimeEnvironment.getApplication()).prefs.also { Onboarding.reset(it) }
    private val hub = Hub("Tab S7+", "192.168.0.154:47823")
    private val bundle = Got.Ok(
        mapOf("anilist" to JSONObject().put("anilist_user", "pedro"), "keys" to JSONObject().put("rd_token", "x").put("jimaku_key", "y").put("addons", "a"), "colouring" to JSONObject().put("plain_words", false)),
        mapOf("anilist" to "pedro"))

    @Test fun aTypedAddressWithoutAPortAsksOnTheSyncPortAndATypedPortStays() = runBlocking {
        val pairing = FakePairing(listOf(Poll.ALLOWED))
        MainDeviceFlow(prefs, pairing) {}.pair(Hub("", "127.0.0.1"))
        assertEquals("127.0.0.1:47823", pairing.asked?.address)
        MainDeviceFlow(prefs, pairing) {}.pair(Hub("", "192.168.0.5:9000"))
        assertEquals("192.168.0.5:9000", pairing.asked?.address)
    }

    @Test fun waitingKnowsWhenTheHubIsOldSoTheSettingsHintShowsOnlyThen() = runBlocking {
        for (old in listOf(false, true)) {
            val pairing = FakePairing(listOf(Poll.WAITING, Poll.ALLOWED)).apply { oldHub = old }
            lateinit var flow: MainDeviceFlow
            var during: Phase? = null
            flow = MainDeviceFlow(prefs, pairing) { during = flow.phase.value }
            flow.pair(hub)
            assertEquals(Phase.Waiting(hub, "4829", old), during)
        }
    }

    @Test fun freshPathMarksMainAndAsksTheRest() {
        Onboarding.markDone(prefs, "language")
        val pairing = FakePairing(emptyList())
        MainDeviceFlow(prefs, pairing).fresh()
        assertEquals(1, pairing.forgot)
        assertEquals("anilist", Onboarding.next(Onboarding.done(prefs), Onboarding.copied(prefs), true))
    }

    @Test fun copiedPathSkipsTheCopiedSteps() = runBlocking {
        Onboarding.markDone(prefs, "language")
        val pairing = FakePairing(listOf(Poll.WAITING, Poll.WAITING, Poll.ALLOWED), bundle)
        val flow = MainDeviceFlow(prefs, pairing) {}
        flow.pair(hub)
        val ready = flow.phase.value as Phase.Ready
        assertEquals(bundle, ready.got)
        prefs.edit().putStringSet(Onboarding.COPIED, setOf("anilist", "keys", "colouring")).commit()
        flow.finish(ready.hub, ready.got, setOf("anilist", "keys", "colouring", "sync"))
        assertEquals(setOf("anilist", "keys", "colouring", "sync"), pairing.applied)
        assertEquals(setOf("anilist", "videos", "colouring"), Onboarding.copied(prefs))
        assertEquals("downloads", Onboarding.next(Onboarding.done(prefs), Onboarding.copied(prefs), true))
        assertEquals("Tab S7+", MainDeviceFlow.hub(prefs))
    }

    @Test fun incompleteKeysLeaveTheVideosStep() = runBlocking {
        val half = Got.Ok(mapOf("keys" to JSONObject().put("rd_token", "x")), mapOf("keys" to "Real-Debrid"))
        val pairing = FakePairing(listOf(Poll.ALLOWED), half)
        val flow = MainDeviceFlow(prefs, pairing) {}
        flow.pair(hub)
        prefs.edit().putStringSet(Onboarding.COPIED, setOf("keys")).commit()
        flow.finish(hub, half, setOf("keys"))
        assertEquals(emptySet<String>(), Onboarding.copied(prefs))
    }

    @Test fun conflictGoesBackToPickWithItsLine() = runBlocking {
        val pairing = FakePairing(listOf(Poll.WAITING, Poll.CONFLICT))
        val flow = MainDeviceFlow(prefs, pairing) {}
        flow.pair(hub)
        val pick = flow.phase.value as Phase.Pick
        assertTrue(pick.notice.contains("Phone"))
        assertEquals(hub, pick.hubs.single())
    }

    @Test fun goneAndOldHubs() = runBlocking {
        val gone = FakePairing(listOf(Poll.GONE))
        MainDeviceFlow(prefs, gone) {}.also { it.pair(hub) }
        assertEquals(1, gone.forgot)
        val old = FakePairing(listOf(Poll.ALLOWED), Got.Old)
        val flow = MainDeviceFlow(prefs, old) {}
        flow.pair(hub)
        val ready = flow.phase.value as Phase.Ready
        assertEquals(Got.Old, ready.got)
        flow.finish(ready.hub, ready.got, setOf("sync"))
        assertEquals(setOf("sync"), old.applied)
        assertTrue("main" in Onboarding.done(prefs))
    }

    @Test fun startKeepsAReadyFlow() = runBlocking {
        val pairing = FakePairing(listOf(Poll.ALLOWED), bundle)
        val flow = MainDeviceFlow(prefs, pairing) {}
        flow.pair(hub)
        pairing.script(listOf(Poll.GONE))
        flow.start()
        assertTrue(flow.phase.value is Phase.Ready)
        assertEquals(0, pairing.forgot)
    }

    @Test fun timeoutKeepsThePairingAndRetryReusesIt() = runBlocking {
        var now = 0L
        val pairing = FakePairing(listOf(Poll.WAITING), bundle)
        val flow = MainDeviceFlow(prefs, pairing, { now }) { now += it }
        flow.pair(hub)
        val pick = flow.phase.value as Phase.Pick
        assertTrue(pick.notice.contains("Tab S7+"))
        assertEquals(1, pairing.asks)
        assertEquals(0, pairing.forgot)
        pairing.script(listOf(Poll.ALLOWED))
        flow.pair(hub)
        assertTrue(flow.phase.value is Phase.Ready)
        assertEquals(1, pairing.asks)
    }

    @Test fun pollingStopsAtTheDeadlineNotAtACount() = runBlocking {
        var now = 0L
        val slow = FakePairing(listOf(Poll.WAITING))
        MainDeviceFlow(prefs, slow, { now }) { now += 60_000 }.pair(hub)
        assertEquals(3, slow.calls)
        now = 0L
        val fast = FakePairing(listOf(Poll.WAITING))
        MainDeviceFlow(prefs, fast, { now }) { now += 2_000 }.pair(hub)
        assertEquals(90, fast.calls)
    }

    @Test fun hubConflictOnAskShowsOurTranslatedLine() = runBlocking {
        val pairing = FakePairing(listOf(Poll.WAITING)).apply { fails = DeviceSync.Conflict("Two devices asked as Phone. Pair again.") }
        val flow = MainDeviceFlow(prefs, pairing) {}
        flow.pair(hub)
        assertEquals("Two devices asked as Phone. Pair again.", (flow.phase.value as Phase.Pick).notice)
    }

    @Test fun afterProcessDeathItAsksToPairAgainWithoutUnpairing() = runBlocking {
        val pairing = FakePairing(listOf(Poll.WAITING)).apply { asked = hub; key = false }
        val flow = MainDeviceFlow(prefs, pairing) {}
        flow.start()
        assertTrue((flow.phase.value as Phase.Pick).notice.contains("closed"))
        assertEquals(0, pairing.forgot)
        assertEquals(0, pairing.calls)
    }

    @Test fun afterFinishTheFlowIsDoneAndCannotCopyAgain() = runBlocking {
        val pairing = FakePairing(listOf(Poll.ALLOWED), bundle)
        val flow = MainDeviceFlow(prefs, pairing) {}
        flow.pair(hub)
        flow.finish(hub, bundle, setOf("anilist"))
        assertEquals(Phase.Done("Tab S7+", Onboarding.copied(prefs)), flow.phase.value)
        pairing.applied = null
        flow.finish(hub, bundle, setOf("anilist", "keys"))
        flow.fresh()
        assertEquals(null, pairing.applied)
        assertEquals(0, pairing.forgot)
        flow.start()
        assertTrue(flow.phase.value is Phase.Done)
    }

    @Test fun startWhenMainIsDoneShowsTheSummary() = runBlocking {
        Onboarding.markDone(prefs, "main")
        prefs.edit().putString(MainDeviceFlow.HUB, "Tab S7+").commit()
        val flow = MainDeviceFlow(prefs, FakePairing(listOf(Poll.WAITING)))
        flow.start()
        assertEquals(Phase.Done("Tab S7+", emptySet()), flow.phase.value)
    }

    @Test fun freshEndsInDone() {
        val flow = MainDeviceFlow(prefs, FakePairing(emptyList()))
        flow.fresh()
        assertEquals(Phase.Done("", emptySet()), flow.phase.value)
    }

    @Test fun aWaitingFlowWithoutACodeStartsLookingAgain() = runBlocking {
        val pairing = FakePairing(listOf(Poll.WAITING)).apply { fails = kotlinx.coroutines.CancellationException("left") }
        val flow = MainDeviceFlow(prefs, pairing) {}
        runCatching { flow.pair(hub) }
        assertEquals(Phase.Waiting(hub, ""), flow.phase.value)
        pairing.fails = null
        flow.start()
        assertTrue(flow.phase.value is Phase.Pick)
    }

    @Test fun networkErrorsShowATranslatedReachLineNotTheJavaText() = runBlocking {
        val pairing = FakePairing(listOf(Poll.WAITING)).apply { fails = java.net.SocketTimeoutException("connect timed out") }
        val flow = MainDeviceFlow(prefs, pairing) {}
        flow.pair(hub)
        assertEquals("Couldn't reach Tab S7+", (flow.phase.value as Phase.Pick).notice)
    }

    @Test fun unreachableForFifteenSecondsForgetsThePairing() = runBlocking {
        var now = 0L
        val pairing = FakePairing(listOf(Poll.UNREACHABLE))
        val flow = MainDeviceFlow(prefs, pairing, { now }) { now += 2_000 }
        flow.pair(hub)
        assertEquals("Couldn't reach Tab S7+: pair again.", (flow.phase.value as Phase.Pick).notice)
        assertEquals(1, pairing.forgot)
        assertTrue(now in 15_000..20_000)
    }

    @Test fun aBlipUnderFifteenSecondsKeepsPolling() = runBlocking {
        var now = 0L
        val pairing = FakePairing(listOf(Poll.UNREACHABLE, Poll.UNREACHABLE, Poll.WAITING, Poll.ALLOWED), bundle)
        val flow = MainDeviceFlow(prefs, pairing, { now }) { now += 2_000 }
        flow.pair(hub)
        assertTrue(flow.phase.value is Phase.Ready)
        assertEquals(0, pairing.forgot)
    }

    @Test fun lookFindsBothKindsAndAPcCardIsOffered() = runBlocking {
        val flow = MainDeviceFlow(prefs, FakePairing(emptyList()))
        flow.look()
        val pick = flow.phase.value as Phase.Pick
        assertEquals(listOf("192.168.0.10:8769"), pick.pcs)
        assertEquals(hub, pick.hubs.single())
        assertEquals(hub.address, pick.auto)
    }

    @Test fun usePcSetsTheServerAndOnboardsAndAFailureStaysOnTheStep() = runBlocking {
        Settings(RuntimeEnvironment.getApplication()).server = ""
        prefs.edit().remove("onboarded").commit()
        val pairing = FakePairing(emptyList())
        val flow = MainDeviceFlow(prefs, pairing)
        flow.look()
        flow.usePc("192.168.0.10:8769")
        val pick = flow.phase.value as Phase.Pick
        assertTrue(pick.notice.startsWith("No answer from http://192.168.0.10:8769"))
        assertEquals(hub, pick.hubs.single())
        assertEquals("", Settings(RuntimeEnvironment.getApplication()).server)
        pairing.pcUp = setOf("http://192.168.0.10:8769")
        flow.usePc("192.168.0.10:8769")
        assertEquals(Phase.PcDone("192.168.0.10:8769"), flow.phase.value)
        assertEquals("http://192.168.0.10:8769", Settings(RuntimeEnvironment.getApplication()).server)
        assertTrue(io.github.pedrubik2000.kumapie.data.Startup.onboarded(prefs.all))
    }

    @Test fun aTypedPcAddressWorksWithOrWithoutAPortAndADeviceFallsThroughToPairing() = runBlocking {
        val pairing = FakePairing(listOf(Poll.ALLOWED)).apply { pcUp = setOf("http://10.0.0.5:8769", "http://10.0.0.6:9000") }
        val a = MainDeviceFlow(prefs, pairing) {}
        a.pair(Hub("", "10.0.0.5"))
        assertEquals(Phase.PcDone("10.0.0.5"), a.phase.value)
        val b = MainDeviceFlow(prefs, pairing) {}
        b.pair(Hub("", "10.0.0.6:9000"))
        assertEquals(Phase.PcDone("10.0.0.6:9000"), b.phase.value)
        assertEquals(0, pairing.asks)
        val c = MainDeviceFlow(prefs, pairing) {}
        c.pair(Hub("", "192.168.0.7"))
        assertEquals("http://192.168.0.7:8769", pairing.triedPc.last())
        assertEquals("192.168.0.7:47823", pairing.asked?.address)
        assertTrue(c.phase.value is Phase.Ready)
    }

    @Test fun aTypedAddressWithASchemeIsOnlyAPcAndKeepsItsError() = runBlocking {
        val pairing = FakePairing(listOf(Poll.ALLOWED))
        val flow = MainDeviceFlow(prefs, pairing) {}
        flow.look()
        flow.pair(Hub("", "https://pc.ts.net:8445"))
        assertEquals(0, pairing.asks)
        val pick = flow.phase.value as Phase.Pick
        assertTrue(pick.notice.startsWith("No answer from https://pc.ts.net:8445"))
        assertEquals(hub, pick.hubs.single())
    }

    @Test fun failAndLookDoNothingOnceDone() = runBlocking {
        val pairing = FakePairing(listOf(Poll.ALLOWED), bundle)
        val flow = MainDeviceFlow(prefs, pairing) {}
        flow.pair(hub)
        flow.finish(hub, bundle, setOf("anilist"))
        flow.look("x")
        assertTrue(flow.phase.value is Phase.Done)
    }
}
