package io.github.pedrubik2000.kumapie.lang

import android.content.Context
import android.content.SharedPreferences
import io.github.pedrubik2000.kumapie.data.Onboarding
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface Pairing {
    suspend fun discover(ms: Long): List<Pair<String, String>>
    suspend fun ask(name: String, address: String): String
    suspend fun allowed(): DeviceSync.Poll
    suspend fun fetch(): SetupBundle.Got
    fun apply(got: SetupBundle.Got.Ok, ticked: Set<String>)
    fun peer(): MainDeviceFlow.Hub?
    fun forget()
}

class LivePairing(private val context: Context) : Pairing {
    override suspend fun discover(ms: Long) = DeviceSync.discover(context, ms)
    override suspend fun ask(name: String, address: String) = DeviceSync.ask(context, name, address)
    override suspend fun allowed() = DeviceSync.allowed(context)
    override suspend fun fetch() = SetupBundle.fetch(context)
    override fun apply(got: SetupBundle.Got.Ok, ticked: Set<String>) = SetupBundle.apply(context, got, ticked)
    override fun peer() = DeviceSync.with(Settings(context))?.let { MainDeviceFlow.Hub(it.name, it.address) }
    override fun forget() = DeviceSync.unpair(context)
}

class MainDeviceFlow(private val prefs: SharedPreferences, private val pairing: Pairing, private val pause: suspend (Long) -> Unit = { delay(it) }) {
    data class Hub(val name: String, val address: String)

    sealed interface Phase {
        data object Looking : Phase
        data class Pick(val hubs: List<Hub>, val notice: String = "") : Phase
        data class Waiting(val hub: Hub, val code: String) : Phase
        data class Ready(val hub: Hub, val got: SetupBundle.Got) : Phase
    }

    private val state = MutableStateFlow<Phase>(Phase.Looking)
    val phase: StateFlow<Phase> get() = state

    suspend fun start() {
        val peer = pairing.peer()
        if (peer != null && Onboarding.MAIN !in Onboarding.done(prefs)) poll(peer, prefs.getString(CODE, "").orEmpty()) else look()
    }

    suspend fun look(notice: String = "") {
        state.value = Phase.Looking
        state.value = Phase.Pick(pairing.discover(DISCOVER_MS).map { Hub(it.first, it.second) }, notice)
    }

    suspend fun pair(hub: Hub) {
        state.value = Phase.Waiting(hub, "")
        val code = runCatching { pairing.ask(hub.name, hub.address) }.getOrElse { return fail(tr("Couldn't pair: %s", it.message ?: it.toString())) }
        prefs.edit().putString(CODE, code).apply()
        poll(hub.copy(name = hub.name.ifEmpty { pairing.peer()?.name.orEmpty() }), code)
    }

    private suspend fun poll(hub: Hub, code: String) {
        state.value = Phase.Waiting(hub, code)
        repeat(POLLS) {
            when (pairing.allowed()) {
                DeviceSync.Poll.ALLOWED -> return fetched(hub)
                DeviceSync.Poll.CONFLICT -> return look(DeviceSync.conflictLine(hub.name))
                DeviceSync.Poll.GONE -> return fail(tr("Pairing was lost: pair again."))
                DeviceSync.Poll.WAITING -> pause(POLL_MS)
            }
        }
        fail(tr("%s didn't allow it in time: pair again.", hub.name))
    }

    private suspend fun fetched(hub: Hub) {
        when (val got = pairing.fetch()) {
            is SetupBundle.Got.Failed -> fail(got.message)
            else -> state.value = Phase.Ready(hub, got)
        }
    }

    private suspend fun fail(notice: String) {
        pairing.forget()
        prefs.edit().remove(CODE).apply()
        look(notice)
    }

    fun finish(hub: Hub, got: SetupBundle.Got, ticked: Set<String>) {
        val ok = got as? SetupBundle.Got.Ok ?: SetupBundle.Got.Ok(emptyMap(), emptyMap())
        pairing.apply(ok, ticked)
        val keys = ok.parts["keys"]
        val steps = Onboarding.copied(prefs).mapNotNull {
            when (it) {
                "languages" -> Onboarding.LANGUAGE
                "keys" -> Onboarding.VIDEOS.takeIf { keys != null && keys.has("jimaku_key") && (keys.has("rd_token") || keys.has("addons")) }
                else -> it
            }
        }
        prefs.edit().putStringSet(Onboarding.COPIED, steps.toSet()).putString(HUB, hub.name).remove(CODE).commit()
        Onboarding.markDone(prefs, Onboarding.MAIN)
    }

    fun fresh() {
        pairing.forget()
        Onboarding.startFresh(prefs)
        prefs.edit().remove(CODE).apply()
    }

    companion object {
        const val CODE = "onb_code"
        const val HUB = "onb_hub"
        const val DISCOVER_MS = 8_000L
        const val POLL_MS = 2_000L
        const val POLLS = 90
        val ROWS = listOf("anilist", "keys", "colouring", SetupBundle.SYNC, "languages")

        fun hub(prefs: SharedPreferences): String = prefs.getString(HUB, "").orEmpty()

        fun clear(prefs: SharedPreferences) = prefs.edit().remove(HUB).remove(CODE).apply()
    }
}
