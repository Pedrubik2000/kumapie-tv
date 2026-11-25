package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.pedrubik2000.kumapie.data.AniList
import io.github.pedrubik2000.kumapie.data.AniListSync
import io.github.pedrubik2000.kumapie.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AniListPillTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theCountIncludesTheEpisodeWhoseWriteIsStillWaiting() {
        val settings = Settings(RuntimeEnvironment.getApplication())
        settings.prefs.edit().putString("anilist_token", "t").commit()
        AniListSync.resetState()
        val api = AniListSync.Api(
            lookup = { 5 },
            entry = { _, _ -> AniList.Entry("CURRENT", 0.0, 0, 0, "2026-11-20", "", 3) },
            save = { _, _, _, _ -> throw IOException("offline") },
            format = { "POINT_10" },
        )
        val sync = AniListSync(settings.prefs, api = api, scope = CoroutineScope(Dispatchers.Unconfined))
        sync.finished("kitsu:5:1", 1)
        rule.setContent { AniListPill(settings, 5, "kitsu:5", sync) }
        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("AniList · Currently watching · 1/3") == 1 }
        rule.onNodeWithText("AniList · Currently watching · 1/3").assertExists()
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(t: String) =
    onAllNodes(androidx.compose.ui.test.hasText(t)).fetchSemanticsNodes().size
