package io.github.pedrubik2000.kumapie.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HintBarTest {
    @Test fun eachKindOfElementReportsItsOwnTrueHint() {
        assertEquals("OK Play", hintText(HintKind.PLAY))
        assertEquals("OK Play · Hold OK Mark watched", hintText(HintKind.EPISODE))
        assertEquals("OK Play · Hold OK Mark watched · ▼ Other files", hintText(HintKind.EPISODE_FILES))
        assertEquals("◀▶ Change · ▲▼ Leave", hintText(HintKind.VALUE))
        assertEquals("OK Select · ▶ Titles", hintText(HintKind.ROW))
        assertEquals("OK On / off", hintText(HintKind.CHECK))
        assertEquals("OK Open", hintText(HintKind.COVER))
        assertEquals("◀▶ Choose · OK Keep", hintText(HintKind.DUEL))
        assertEquals("OK Choose · ▲ Title", hintText(HintKind.PICK))
        assertEquals("OK Choose · ▼ Rows", hintText(HintKind.HERO))
    }

    @Test fun aRailItemShowsItsNameWithTheKeyAndTheCurrentOneWithoutIt() {
        val a = Any()
        Hints.focus(a, HintKind.RAIL, "Library")
        assertEquals("OK Library", Hints.line())
        Hints.refresh(a, HintKind.RAIL_HERE, "Home")
        assertEquals("Home", Hints.line())
        Hints.blur(a)
        assertEquals(null, Hints.line())
    }

    @Test fun noHintHasMoreThanThreeParts() {
        HintKind.entries.forEach { assert(hintOf(it).size <= 3) { "$it" } }
    }

    @Test fun aLateRefreshFromABlurredItemNeverTakesTheLineBack() {
        val a = Any()
        val b = Any()
        Hints.focus(a, HintKind.COVER)
        Hints.focus(b, HintKind.CHECK)
        Hints.refresh(a, HintKind.COVER)
        assertEquals("OK On / off", Hints.line())
        Hints.refresh(b, HintKind.PLAY)
        assertEquals("OK Play", Hints.line())
        Hints.blur(b)
    }

    @Test fun theBarFollowsTheFocusedElementAndEmptiesWhenItLeaves() {
        val a = Any()
        val b = Any()
        Hints.focus(a, HintKind.COVER)
        assertEquals("OK Open", Hints.line())
        Hints.focus(b, HintKind.CHECK)
        Hints.blur(a)
        assertEquals("OK On / off", Hints.line())
        Hints.blur(b)
        assertEquals(null, Hints.line())
    }
}
