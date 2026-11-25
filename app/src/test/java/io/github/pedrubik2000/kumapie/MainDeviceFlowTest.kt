package io.github.pedrubik2000.kumapie

import io.github.pedrubik2000.kumapie.data.Onboarding
import io.github.pedrubik2000.kumapie.data.Settings
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

private class FakePairing(private val polls: List<Poll>, private val got: Got = Got.Ok(emptyMap(), emptyMap())) : Pairing {
    var asked: Hub? = null
    var forgot = 0
    var applied: Set<String>? = null
    private var at = 0
    override suspend fun discover(ms: Long) = listOf("Tab S7+" to "192.168.0.154:47823")
    override suspend fun ask(name: String, address: String) = "4829".also { asked = Hub(name, address) }
    override suspend fun allowed() = polls[minOf(at++, polls.size - 1)]
    override suspend fun fetch() = got
    override fun apply(got: Got.Ok, ticked: Set<String>) { applied = ticked }
    override fun peer() = asked
    override fun forget() { forgot++ }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainDeviceFlowTest {
    private val prefs = Settings(RuntimeEnvironment.getApplication()).prefs.also { Onboarding.reset(it) }
    private val hub = Hub("Tab S7+", "192.168.0.154:47823")
    private val bundle = Got.Ok(
        mapOf("anilist" to JSONObject().put("anilist_user", "pedro"), "keys" to JSONObject().put("rd_token", "x").put("jimaku_key", "y"), "colouring" to JSONObject().put("plain_words", false)),
        mapOf("anilist" to "pedro"))

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
        assertTrue(pick.notice.contains("Tab S7+"))
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
}
