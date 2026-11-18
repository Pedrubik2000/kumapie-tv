package io.github.pedrubik2000.kumapie

import io.github.pedrubik2000.kumapie.data.Addons
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Found
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.data.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Settings > "Use without the PC": the PC's Discover progress becomes the TV's own (kept titles, watched, position). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeepFromPcTest {
    @Test fun progressComesAlong() = runBlocking {
        val prefs = Settings(RuntimeEnvironment.getApplication()).prefs.also { it.edit().clear().commit() }
        fun going(title: String, ep: Int, resume: Double?): Pair<Show, Episode> {
            val e = Episode("$title:$ep", "Episode $ep", 1, ep, 1400.0, "", 0, 0, 0, resume)
            return Show(title, title, "stream", "", listOf(e), "ja") to e
        }
        val addons = Addons(emptyList(), prefs)
        addons.keepFromPc(listOf(Found("kitsu:1", "series", "Kept", "", ""), Found("kitsu:2", "series", "Dandadan", "", "")),
            listOf(going("kitsu:2", 3, 28.5), going("kitsu:3", 2, null)))
        assertEquals(listOf("kitsu:1", "kitsu:3", "kitsu:2"), addons.saved().map { it.id }) // newest last, as save() keeps them
        assertEquals(setOf("kitsu:2:1", "kitsu:2:2", "kitsu:3:1"), prefs.getStringSet("alone_seen", null))
        assertEquals(28.5f, prefs.getFloat("alone_pos_kitsu:2:3", 0f))
        assertEquals(0f, prefs.getFloat("alone_pos_kitsu:3:2", 0f))
    }
}
