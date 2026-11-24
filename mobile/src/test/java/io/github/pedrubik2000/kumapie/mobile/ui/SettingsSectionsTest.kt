package io.github.pedrubik2000.kumapie.mobile.ui

import io.github.pedrubik2000.kumapie.data.AniListAccount
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSectionsTest {
    @Test fun aniListIsTheFirstSection() = assertEquals("anilist" to "AniList", SECTIONS.first())

    @Test fun theStateLine() {
        assertEquals("Logged in as pedro", AniListAccount.summary("t", "pedro", false))
        assertEquals("Not logged in", AniListAccount.summary("", "pedro", false))
        assertEquals("Login expired: log in again", AniListAccount.summary("", "pedro", true))
    }
}
