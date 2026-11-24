package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import io.github.pedrubik2000.kumapie.data.Mine
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A long Japanese title once wrapped to two lines and pushed the hero's last line onto the row heading below (Chromecast, 2026-11-24). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television-xhdpi")
class HeroOverlapTest {
    @get:Rule val rule = createComposeRule()

    private fun bottomOf(title: String): Pair<Float, Float> {
        val m = Mine(1, "1", title, title, "", "", "TV", 13, "CURRENT", 2, 7.0, 76, listOf("Action"), 2010)
        rule.setContent { Box(Modifier.fillMaxWidth().height(HERO_BUDGET)) { InfoText(ListTarget(m), "13 episodes · 2010", listOf("On AniList: Watching", "Ep 2 of 13"), null) } }
        val bottom = rule.onNodeWithText("Open the title page").fetchSemanticsNode().boundsInRoot.bottom
        return bottom to rule.density.run { HERO_BUDGET.toPx() }
    }

    @Test fun aVeryLongTitleKeepsTheHeroAboveTheRows() {
        val (long, budget) = bottomOf("パンティ&ストッキングwithガーターベルト パンティ&ストッキングwithガーターベルト")
        assertTrue("hero bottom $long over budget $budget", long <= budget)
    }


    private companion object { val HERO_BUDGET = 390.dp }
}
