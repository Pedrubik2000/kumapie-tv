package io.github.pedrubik2000.kumapie.data

import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.update.Updater

enum class Gate { FIRST_RUN, ONBOARDING, APP }

enum class RdGate(val mayFinish: Boolean) { NOT_ASKED(true), OK(true), UNCHECKED(true), MISSING(false), REJECTED(false), UNREACHABLE(false) }

object Startup {
    const val ONBOARDED = "onboarded"
    const val PROOF_ADDON = "http://10.0.2.2:8795/"
    const val RD_TRIES = 3

    fun gate(server: String, standalone: Boolean, onboarded: Boolean): Gate = when {
        server.isNotEmpty() -> Gate.APP
        !standalone -> Gate.FIRST_RUN
        !onboarded -> Gate.ONBOARDING
        else -> Gate.APP
    }

    fun onboarded(prefs: Map<String, *>): Boolean = prefs[ONBOARDED] == true

    fun onboard(prefs: android.content.SharedPreferences) =
        prefs.edit().putBoolean(ONBOARDED, true).remove(Onboarding.DONE).remove(Onboarding.COPIED).remove(Onboarding.HUB).remove("onb_code").apply()

    fun rdKey(token: String, addons: List<String>): String = token.ifEmpty {
        addons.firstNotNullOfOrNull { Regex("realdebrid=([^/|%]+)").find(it)?.groupValues?.get(1) }.orEmpty()
    }

    fun rdGate(pc: Boolean, proof: Boolean, key: String, check: String?, failed: Int): RdGate = when {
        pc || proof -> RdGate.NOT_ASKED
        key.isBlank() -> RdGate.MISSING
        check == null -> RdGate.UNREACHABLE
        check == "ok" -> RdGate.OK
        check == "rejected" -> RdGate.REJECTED
        failed + 1 >= RD_TRIES -> RdGate.UNCHECKED
        else -> RdGate.UNREACHABLE
    }

    fun proof(addons: List<String>) = addons.any { it.startsWith(PROOF_ADDON) }

    fun rdMissing(settings: Settings): Boolean = rdGate(settings.server.isNotEmpty(), proof(settings.addons),
        rdKey(settings.rdToken, settings.addons), null, 0) == RdGate.MISSING

    suspend fun rdCheck(settings: Settings, failed: Int, check: suspend (String) -> String = { checkKey(KeyService.REAL_DEBRID, it) }): RdGate {
        val pc = settings.server.isNotEmpty()
        val proof = proof(settings.addons)
        val key = rdKey(settings.rdToken, settings.addons)
        if (pc || proof || key.isBlank()) return rdGate(pc, proof, key, null, failed)
        return rdGate(false, false, key, check(key), failed)
    }

    fun rdSay(g: RdGate): String = when (g) {
        RdGate.MISSING -> tr("Real-Debrid key missing: no video will play. Get yours at real-debrid.com/apitoken.")
        RdGate.REJECTED -> tr("%1\$s didn't accept this key. Copy it again from %2\$s.", KeyService.REAL_DEBRID.label, KeyService.REAL_DEBRID.where)
        RdGate.UNREACHABLE -> tr("Couldn't reach Real-Debrid to check the key: is this device online? Next tries again; the 3rd time it goes on.")
        else -> ""
    }

    fun updates(repo: String, version: String, asset: String) {
        Updater.repo = repo
        Updater.version = version
        Updater.asset = asset
    }

    suspend fun update(debug: Boolean, newer: suspend () -> Updater.Release? = { Updater.newer() }): Updater.Release? =
        if (debug) null else runCatching { newer() }.getOrNull()
}
