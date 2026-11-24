package io.github.pedrubik2000.kumapie.data

import io.github.pedrubik2000.kumapie.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object AniListAccount {
    fun summary(token: String, user: String, expired: Boolean): String = when {
        token.isNotEmpty() -> if (user.isEmpty()) tr("Logged in") else tr("Logged in as %s", user)
        expired -> tr("Login expired: log in again")
        else -> tr("Not logged in")
    }

    fun summary(settings: Settings): String = summary(settings.anilistToken, settings.anilist, settings.anilistLoggedOut)

    fun why(): String = tr("Home, Library and Discover come from your AniList lists, and what you watch goes back to it.")

    class Last(val waiting: Int, val expired: Boolean)

    suspend fun sendLast(sync: AniListSync, timeoutMs: Long = 5000): Last = withContext(Dispatchers.IO) {
        val before = sync.waiting()
        val left = withTimeoutOrNull(timeoutMs) { sync.flushNow() } ?: sync.waiting()
        val expired = sync.expired()
        Last(if (expired) before else left, expired)
    }

    fun confirmText(last: Last?): String = when {
        last == null -> tr("Sending your last changes to AniList…")
        last.expired && last.waiting == 0 -> tr("Your AniList login had expired.")
        last.expired && last.waiting == 1 -> tr("Your AniList login had expired: 1 change couldn't be sent")
        last.expired -> tr("Your AniList login had expired: %d changes couldn't be sent", last.waiting)
        last.waiting == 0 -> tr("This only signs kumapie out. Your AniList stays as it is.")
        last.waiting == 1 -> tr("1 change hasn't reached AniList yet: it will be lost")
        else -> tr("%d changes haven't reached AniList yet: they'll be lost", last.waiting)
    }
}
