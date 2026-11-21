package io.github.pedrubik2000.kumapie.data

import io.github.pedrubik2000.kumapie.update.Updater

enum class Gate { FIRST_RUN, ONBOARDING, APP }

object Startup {
    const val ONBOARDED = "onboarded"

    fun gate(server: String, standalone: Boolean, onboarded: Boolean): Gate = when {
        server.isNotEmpty() -> Gate.APP
        !standalone -> Gate.FIRST_RUN
        !onboarded -> Gate.ONBOARDING
        else -> Gate.APP
    }

    fun onboarded(prefs: Map<String, *>): Boolean = prefs[ONBOARDED] == true

    fun onboard(prefs: android.content.SharedPreferences) = prefs.edit().putBoolean(ONBOARDED, true).apply()

    fun updates(repo: String, version: String, asset: String) {
        Updater.repo = repo
        Updater.version = version
        Updater.asset = asset
    }

    suspend fun update(debug: Boolean, newer: suspend () -> Updater.Release? = { Updater.newer() }): Updater.Release? =
        if (debug) null else runCatching { newer() }.getOrNull()
}
