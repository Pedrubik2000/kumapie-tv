package io.github.pedrubik2000.kumapie.lang

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.pedrubik2000.kumapie.data.Onboarding
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import kotlinx.coroutines.CancellationException
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
    fun hasKey(): Boolean
    fun me(): String
    fun forget()
}

class LivePairing(private val context: Context) : Pairing {
    override suspend fun discover(ms: Long) = DeviceSync.discover(context, ms)
    override suspend fun ask(name: String, address: String) = DeviceSync.ask(context, name, address)
    override suspend fun allowed() = DeviceSync.allowed(context)
    override suspend fun fetch() = SetupBundle.fetch(context)
    override fun apply(got: SetupBundle.Got.Ok, ticked: Set<String>) = SetupBundle.apply(context, got, ticked)
    override fun peer() = DeviceSync.with(Settings(context))?.let { MainDeviceFlow.Hub(it.name, it.address) }
    override fun forget() { SetupBundle.clearKey(); DeviceSync.unpair(context) }
    override fun hasKey() = SetupBundle.pub() != null
    override fun me() = Progress.of(context).device
}

class MainDeviceFlow(private val prefs: SharedPreferences, private val pairing: Pairing, private val clock: () -> Long = System::currentTimeMillis, private val pause: suspend (Long) -> Unit = { delay(it) }) {
    data class Hub(val name: String, val address: String)

    sealed interface Phase {
        data object Looking : Phase
        data class Pick(val hubs: List<Hub>, val notice: String = "") : Phase
        data class Waiting(val hub: Hub, val code: String) : Phase
        data class Ready(val hub: Hub, val got: SetupBundle.Got) : Phase
        data class Done(val hub: String, val copied: Set<String>) : Phase
    }

    private val state = MutableStateFlow<Phase>(Phase.Looking)
    private var pending: Phase.Waiting? = null
    val phase: StateFlow<Phase> get() = state

    suspend fun start() {
        if (state.value is Phase.Looking && Onboarding.MAIN in Onboarding.done(prefs)) {
            state.value = Phase.Done(hub(prefs), Onboarding.copied(prefs))
            return
        }
        when (val now = state.value) {
            is Phase.Ready, is Phase.Pick, is Phase.Done -> return
            is Phase.Waiting -> if (now.code.isBlank()) look() else poll(now.hub, now.code)
            is Phase.Looking -> if (pairing.peer() != null && !pairing.hasKey() && Onboarding.MAIN !in Onboarding.done(prefs)) look(tr("Pair again: the app was closed while waiting")) else look()
        }
    }

    suspend fun look(notice: String = "") {
        if (state.value is Phase.Done) return
        state.value = Phase.Looking
        state.value = Phase.Pick(pairing.discover(DISCOVER_MS).map { Hub(it.first, it.second) }, notice)
    }

    suspend fun pair(hub: Hub) {
        if (state.value is Phase.Waiting) return
        val again = pending?.takeIf { it.hub.address == hub.address && pairing.hasKey() && pairing.peer() != null }
        if (again != null) return poll(again.hub, again.code)
        state.value = Phase.Waiting(hub, "")
        val code = try {
            pairing.ask(hub.name, hub.address)
        } catch (c: CancellationException) {
            throw c
        } catch (c: DeviceSync.Conflict) {
            return fail(c.message.orEmpty())
        } catch (e: IllegalStateException) {
            return fail(e.message.orEmpty())
        } catch (e: Exception) {
            Log.w("kumapie", "pair: ${e.javaClass.simpleName}: ${e.message}")
            return fail(tr("Couldn't reach %s", hub.name.ifEmpty { hub.address }))
        }
        poll(hub.copy(name = hub.name.ifEmpty { pairing.peer()?.name.orEmpty() }), code)
    }

    private suspend fun poll(hub: Hub, code: String) {
        pending = Phase.Waiting(hub, code).also { state.value = it }
        var heard = clock()
        val end = heard + DEADLINE_MS
        while (clock() < end) {
            when (pairing.allowed()) {
                DeviceSync.Poll.ALLOWED -> return fetched(hub)
                DeviceSync.Poll.CONFLICT -> {
                    pending = null
                    return look(DeviceSync.conflictLine(pairing.me()))
                }
                DeviceSync.Poll.GONE -> return fail(tr("Pairing was lost: pair again."))
                DeviceSync.Poll.WAITING -> {
                    heard = clock()
                    pause(POLL_MS)
                }
                DeviceSync.Poll.UNREACHABLE -> {
                    if (clock() - heard > UNREACHABLE_MS) return fail(tr("Couldn't reach %s: pair again.", hub.name.ifEmpty { hub.address }))
                    pause(POLL_MS)
                }
            }
        }
        look(tr("%s didn't allow it in time: pair again.", hub.name))
    }

    private suspend fun fetched(hub: Hub) {
        pending = null
        when (val got = pairing.fetch()) {
            is SetupBundle.Got.Failed -> fail(got.message)
            else -> state.value = Phase.Ready(hub, got)
        }
    }

    private suspend fun fail(notice: String) {
        if (state.value is Phase.Done) return
        pending = null
        pairing.forget()
        look(notice)
    }

    fun finish(hub: Hub, got: SetupBundle.Got, ticked: Set<String>) {
        if (state.value !is Phase.Ready) return
        val ok = got as? SetupBundle.Got.Ok ?: SetupBundle.Got.Ok(emptyMap(), emptyMap())
        pairing.apply(ok, ticked)
        val keys = ok.parts["keys"]
        val steps = Onboarding.copied(prefs).mapNotNull {
            when (it) {
                "languages" -> Onboarding.LANGUAGE
                "keys" -> Onboarding.VIDEOS.takeIf { keys != null && keys.has("jimaku_key") && keys.has("rd_token") && keys.has("addons") }
                else -> it
            }
        }
        prefs.edit().putStringSet(Onboarding.COPIED, steps.toSet()).putString(HUB, hub.name).commit()
        Onboarding.markDone(prefs, Onboarding.MAIN)
        state.value = Phase.Done(hub.name, Onboarding.copied(prefs))
    }

    fun fresh() {
        if (state.value is Phase.Done || Onboarding.MAIN in Onboarding.done(prefs)) return
        pairing.forget()
        Onboarding.startFresh(prefs)
        state.value = Phase.Done("", emptySet())
    }

    companion object {
        const val HUB = Onboarding.HUB
        const val DISCOVER_MS = 8_000L
        const val POLL_MS = 2_000L
        const val DEADLINE_MS = 180_000L
        const val UNREACHABLE_MS = 15_000L
        val ROWS = listOf("anilist", "keys", "colouring", SetupBundle.SYNC, "languages")

        fun hub(prefs: SharedPreferences): String = prefs.getString(HUB, "").orEmpty()

        fun clear(prefs: SharedPreferences) = prefs.edit().remove(HUB).apply()
    }
}
