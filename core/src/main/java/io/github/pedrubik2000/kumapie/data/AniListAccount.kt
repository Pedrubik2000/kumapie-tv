package io.github.pedrubik2000.kumapie.data

import io.github.pedrubik2000.kumapie.i18n.tr

object AniListAccount {
    fun summary(token: String, user: String, expired: Boolean): String = when {
        token.isNotEmpty() -> if (user.isEmpty()) tr("Logged in") else tr("Logged in as %s", user)
        expired -> tr("Login expired: log in again")
        else -> tr("Not logged in")
    }

    fun summary(settings: Settings): String = summary(settings.anilistToken, settings.anilist, settings.anilistLoggedOut)

    fun why(): String = tr("Home, Library and Discover come from your AniList lists, and what you watch goes back to it.")
}
