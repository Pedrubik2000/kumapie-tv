package io.github.pedrubik2000.kumapie.lang

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.pedrubik2000.kumapie.data.Onboarding
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.Startup
import io.github.pedrubik2000.kumapie.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface Pairing {
    suspend fun discover(ms: Long): List<Pair<String, String>>
    suspend fun discoverPcs(ms: Long): List<MainDeviceFlow.Pc> = emptyList()
    suspend fun usePc(url: String): String
    suspend fun ask(name: String, address: String): DeviceSync.Asked
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
    override suspend fun discoverPcs(ms: Long) = NsdScan.scan(context, ms, DeviceSync.PC_SERVICE).map { MainDeviceFlow.Pc(it.address, it.url) }
    override suspend fun usePc(url: String): String = PcProbe.check(url).let { err ->
        if (err.isEmpty()) Settings(context).server = url
        if (err.isEmpty()) "" else tr("No answer from %1\$s: %2\$s", url, err)
    }
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
    data class Pc(val address: String, val url: String = "")

    sealed interface Phase {
        data object Looking : Phase
        data class Pick(val hubs: List<Hub>, val notice: String = "", val pcs: List<Pc> = emptyList()) : Phase {
            val auto: String? get() = hubs.singleOrNull()?.address ?: pcs.singleOrNull()?.address.takeIf { hubs.isEmpty() }
        }
        data class Waiting(val hub: Hub, val code: String, val old: Boolean = false) : Phase
        data class Ready(val hub: Hub, val got: SetupBundle.Got) : Phase
        data class Done(val hub: String, val copied: Set<String>) : Phase
        data class PcDone(val address: String) : Phase
        data class Connecting(val address: String) : Phase
    }

    private val state = MutableStateFlow<Phase>(Phase.Looking)
    private var pending: Phase.Waiting? = null
    @Volatile private var connecting: kotlinx.coroutines.Job? = null
    val phase: StateFlow<Phase> get() = state

    suspend fun start() {
        if (state.value is Phase.Looking && Onboarding.MAIN in Onboarding.done(prefs)) {
            state.value = Phase.Done(hub(prefs), Onboarding.copied(prefs))
            return
        }
        when (val now = state.value) {
            is Phase.Ready, is Phase.Pick, is Phase.Done, is Phase.PcDone -> return
            is Phase.Connecting -> look()
            is Phase.Waiting -> if (now.code.isBlank()) look() else poll(now.hub, now.code, now.old)
            is Phase.Looking -> if (pairing.peer() != null && !pairing.hasKey() && Onboarding.MAIN !in Onboarding.done(prefs)) look(tr("Pair again: the app was closed while waiting")) else look()
        }
    }

    suspend fun look(notice: String = "") {
        if (state.value is Phase.Done || state.value is Phase.PcDone) return
        state.value = Phase.Looking
        val (hubs, pcs) = coroutineScope {
            val devices = async { pairing.discover(DISCOVER_MS) }
            val found = async { pairing.discoverPcs(DISCOVER_MS) }
            devices.await() to found.await()
        }
        state.value = Phase.Pick(hubs.map { Hub(it.first, it.second) }, notice, pcs)
    }

    private suspend fun connect(url: String, address: String): String {
        val err = pairing.usePc(url)
        if (err.isEmpty()) {
            Startup.onboard(prefs)
            state.value = Phase.PcDone(address)
        }
        return err
    }

    suspend fun usePc(pc: Pc, wan: Boolean) {
        val before = state.value
        state.value = Phase.Connecting(pc.address)
        connecting = currentCoroutineContext()[kotlinx.coroutines.Job]
        val err = try {
            if (wan && pc.url.isNotEmpty() && connect(pc.url, pc.address).isEmpty()) "" else connect(pcUrl(pc.address), pc.address)
        } catch (t: Throwable) {
            if (state.value is Phase.Connecting) state.value = before
            throw t
        } finally {
            connecting = null
        }
        if (err.isNotEmpty()) state.value = ((before as? Phase.Pick) ?: Phase.Pick(emptyList())).copy(notice = err)
    }

    suspend fun pair(typed: Hub) {
        if (state.value is Phase.Waiting || state.value is Phase.Connecting) return
        if (typed.name.isEmpty() && typed.address.isNotBlank()) {
            val before = state.value
            state.value = Phase.Connecting(typed.address)
            connecting = currentCoroutineContext()[kotlinx.coroutines.Job]
            val err = try {
                connect(pcUrl(typed.address), typed.address)
            } catch (t: Throwable) {
                if (state.value is Phase.Connecting) state.value = before
                throw t
            } finally {
                connecting = null
            }
            if (err.isEmpty()) return
            if (typed.address.trim().startsWith("http", ignoreCase = true)) {
                state.value = ((before as? Phase.Pick) ?: Phase.Pick(emptyList())).copy(notice = err)
                return
            }
        }
        val hub = typed.copy(address = DeviceSync.withPort(typed.address))
        val again = pending?.takeIf { it.hub.address == hub.address && pairing.hasKey() && pairing.peer() != null }
        if (again != null) return poll(again.hub, again.code, again.old)
        state.value = Phase.Waiting(hub, "")
        val asked = try {
            pairing.ask(hub.name, hub.address)
        } catch (c: CancellationException) {
            throw c
        } catch (c: DeviceSync.Conflict) {
            return fail(c.message.orEmpty())
        } catch (e: IllegalStateException) {
            return fail(e.message.orEmpty())
        } catch (e: Exception) {
            Log.w("kumapie", "pair: ${e.javaClass.simpleName}: ${e.message}")
            return fail(if (typed.name.isEmpty()) tr("Nothing answered at %s: not a kumapie PC, not a kumapie device.", hub.address) else tr("Couldn't reach %s", hub.name))
        }
        poll(hub.copy(name = hub.name.ifEmpty { pairing.peer()?.name.orEmpty() }), asked.code, asked.old)
    }

    private suspend fun poll(hub: Hub, code: String, old: Boolean) {
        pending = Phase.Waiting(hub, code, old).also { state.value = it }
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
        connecting?.cancel()
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
        const val PC_PORT = 8769

        fun pcUrl(address: String): String = address.trim().let {
            when {
                it.startsWith("http://", true) || it.startsWith("https://", true) -> it
                ':' in it -> "http://$it"
                else -> "http://$it:$PC_PORT"
            }
        }
        val ROWS = listOf("anilist", "keys", "colouring", SetupBundle.SYNC, "languages")

        fun hub(prefs: SharedPreferences): String = prefs.getString(HUB, "").orEmpty()

        fun clear(prefs: SharedPreferences) = prefs.edit().remove(HUB).apply()
    }
}
