package io.github.pedrubik2000.kumapie.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.sun.net.httpserver.HttpServer
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress

/**
 * The TV Home with the remote's keys, on the PC: each test is a focus bug once found by hand on the Chromecast.
 * A tiny local server plays the PC (only /api/tv/shows; everything else 404s, which Home takes as "none").
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television-xhdpi") // the Chromecast: 1920x1080 at 2x
class TvHomeFocusTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var server: HttpServer

    @Before fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { x ->
            val body = if (x.requestURI.path == "/api/tv/shows") SHOWS.toByteArray() else ByteArray(0)
            x.sendResponseHeaders(if (body.isEmpty()) 404 else 200, if (body.isEmpty()) -1 else body.size.toLong())
            x.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @After fun stopServer() = server.stop(0)

    private var home by mutableStateOf(true)

    /** Home as MainActivity shows it; Stats only hides it (as going to the Stats screen and back). */
    private fun openHome() {
        rule.setContent {
            val settings = Settings(LocalContext.current)
            if (home) HomeScreen(Api("http://127.0.0.1:${server.address.port}"), settings, "p", "Pedro",
                onShow = {}, onPlay = { _, _ -> }, onSettings = {}, onStats = { home = false }, onProfiles = {})
        }
        rule.waitUntil(5_000) { focused() == RESUME }
    }

    /** What the focused element says: its text, else its icon's name. */
    private fun focused(): String? = rule.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()?.config?.let { c ->
        c.getOrNull(SemanticsProperties.Text)?.joinToString(" ")?.takeIf { it.isNotBlank() }
            ?: c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
    }

    private fun press(vararg keys: Long) = keys.map(::Key).forEach { k -> rule.onRoot().performKeyInput { pressKey(k) }; rule.waitForIdle() }

    @Test fun resumeIsFocusedWhenHomeOpens() = openHome() // openHome waits for it

    @Test fun downFromResumeGoesToTheRowUnderIt() {
        openHome()
        press(DOWN)
        assertEquals("Beta", focused()) // "Continue watching", not a far card the beam search picked
    }

    @Test fun backInTheRowsGoesUpToResume() {
        openHome()
        press(DOWN, DOWN, RIGHT)
        assert(focused() != RESUME)
        press(BACK) // it closed the app (2026-11-17)
        rule.waitUntil(2_000) { focused() == RESUME }
    }

    @Test fun rightFromARoundButtonGoesToThePage() {
        openHome()
        rule.onNodeWithContentDescription("Stats").requestFocus()
        rule.waitForIdle()
        assertEquals("Stats", focused())
        press(RIGHT) // the rows scroll under the round buttons, so the beam search found nothing
        assertEquals(RESUME, focused())
    }

    @Test fun backFromStatsFocusesItsRoundButton() {
        openHome()
        rule.onNodeWithContentDescription("Stats").requestFocus()
        press(OK) // the remote's OK, as on the TV
        assertEquals(false, home)
        home = true
        runCatching { rule.waitUntil(5_000) { focused() == "Stats" } } // it was always Resume
        assertEquals("Stats", focused())
    }

    private companion object {
        const val RESUME = "▶  Resume"
        // Key is a value class (no vararg of it): its key codes.
        val DOWN = Key.DirectionDown.keyCode; val RIGHT = Key.DirectionRight.keyCode; val BACK = Key.Back.keyCode; val OK = Key.DirectionCenter.keyCode

        fun episode(id: String, resume: Double?, at: Long) =
            """{"id":"$id","title":"Episode 1","season":1,"episode":1,"duration":1400,"thumb":"/t/$id.jpg","scenes":10,
               "seen":2,"easy":80,"resume":${resume ?: "null"},"resumed_at":$at,"watched":false}"""

        fun show(id: String, title: String, kind: String, vararg episodes: String) =
            """{"id":"$id","title":"$title","kind":"$kind","poster":"/p/$id.jpg","lang":"ja","episodes":[${episodes.joinToString(",")}]}"""

        /** Alpha left last (the picture at the top), Beta before it ("Continue watching"), Gamma not started. */
        val SHOWS = """{"shows":[${listOf(
            show("a", "Alpha", "anime", episode("a1", 300.0, 200)),
            show("b", "Beta", "anime", episode("b1", 500.0, 100)),
            show("c", "Gamma", "shows", episode("c1", null, 0)),
        ).joinToString(",")}]}"""
    }
}
