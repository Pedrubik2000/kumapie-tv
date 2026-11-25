package io.github.pedrubik2000.kumapie

import io.github.pedrubik2000.kumapie.data.Onboarding
import io.github.pedrubik2000.kumapie.data.Onboarding.State
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.Startup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnboardingTest {
    private val prefs = Settings(RuntimeEnvironment.getApplication()).prefs.also { Onboarding.reset(it) }

    @Test fun nextFollowsTheOrder() {
        assertEquals("language", Onboarding.next(emptySet(), emptySet(), true))
        assertEquals("main", Onboarding.next(setOf("language"), emptySet(), true))
        assertEquals("downloads", Onboarding.next(Onboarding.STEPS.toSet() - "downloads", emptySet(), true))
        assertNull(Onboarding.next(Onboarding.STEPS.toSet(), emptySet(), true))
    }

    @Test fun nextSkipsCopied() {
        val copied = setOf("anilist", "videos", "colouring")
        assertEquals("downloads", Onboarding.next(setOf("language", "main"), copied, true))
        assertNull(Onboarding.next(setOf("language", "main", "downloads"), copied, true))
    }

    @Test fun notLearningJapaneseSkipsColouring() {
        val done = setOf("language", "main", "anilist", "videos")
        assertEquals("colouring", Onboarding.next(done, emptySet(), true))
        assertEquals("downloads", Onboarding.next(done, emptySet(), false))
        assertFalse(Onboarding.list(done, emptySet(), false).any { it.step == "colouring" })
    }

    @Test fun listStates() {
        val l = Onboarding.list(setOf("language", "main"), setOf("anilist"), true, mapOf("language" to "English", "main" to "Tab S7+"))
            .associateBy { it.step }
        assertEquals(State.DONE to "English", l.getValue("language").let { it.state to it.sub })
        assertEquals("Tab S7+", l.getValue("main").sub)
        assertEquals(State.COPIED to "copied", l.getValue("anilist").let { it.state to it.sub })
        assertEquals(State.NEEDED to "Needed", l.getValue("videos").let { it.state to it.sub })
        assertEquals(State.OPTIONAL to "Optional", l.getValue("colouring").let { it.state to it.sub })
        assertEquals(State.NEEDED to "Automatic", l.getValue("downloads").let { it.state to it.sub })
        assertEquals(Onboarding.STEPS, Onboarding.list(emptySet(), emptySet(), true).map { it.step })
    }

    @Test fun copiedStepStaysInTheList() {
        // A copied step is skipped by next() but still listed, so it can be opened.
        assertEquals(true, Onboarding.list(emptySet(), setOf("anilist"), true).any { it.step == "anilist" && it.state == State.COPIED })
    }

    @Test fun startFreshMarksMainAndCopiesNothing() {
        Onboarding.markCopied(prefs, listOf("anilist"))
        Onboarding.startFresh(prefs)
        assertEquals(setOf("main"), Onboarding.done(prefs))
        assertEquals(emptySet<String>(), Onboarding.copied(prefs))
    }

    @Test fun marksPersistAndAccumulate() {
        Onboarding.markDone(prefs, "language")
        Onboarding.markDone(prefs, "main")
        Onboarding.markCopied(prefs, listOf("anilist", "videos"))
        Onboarding.markCopied(prefs, listOf("colouring"))
        assertEquals(setOf("language", "main"), Onboarding.done(prefs))
        assertEquals(setOf("anilist", "videos", "colouring"), Onboarding.copied(prefs))
    }

    @Test fun resetAndOnboardClearThem() {
        Onboarding.markDone(prefs, "language")
        Onboarding.markCopied(prefs, listOf("anilist"))
        Onboarding.reset(prefs)
        assertEquals(emptySet<String>(), Onboarding.done(prefs) + Onboarding.copied(prefs))
        Onboarding.markDone(prefs, "language")
        Onboarding.markCopied(prefs, listOf("anilist"))
        Startup.onboard(prefs)
        assertEquals(emptySet<String>(), Onboarding.done(prefs) + Onboarding.copied(prefs))
        assertEquals(true, Startup.onboarded(prefs.all))
    }
}
