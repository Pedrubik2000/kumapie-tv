package io.github.pedrubik2000.kumapie

import org.robolectric.RuntimeEnvironment
import com.sun.net.httpserver.HttpServer
import io.github.pedrubik2000.kumapie.data.SetupCheck
import io.github.pedrubik2000.kumapie.data.SetupCheck.Verdict
import io.github.pedrubik2000.kumapie.data.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress

/** Settings > Check setup without the internet: no keys set, so only the PC (a local server) and the addon lines run. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SetupCheckTest {
    private fun settings() = Settings(RuntimeEnvironment.getApplication()).also { it.prefs.edit().clear().commit() }
    private fun verdicts(s: Settings) = runBlocking { SetupCheck.run(s) }.map { it.what to it.verdict }

    @Test fun aPcThatAnswersIsOk() {
        val pc = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/tv") { x -> val b = """{"api":3,"episodes":12}""".toByteArray(); x.sendResponseHeaders(200, b.size.toLong()); x.responseBody.use { it.write(b) } }
            start()
        }
        try {
            val s = settings().apply { prefs.edit().putString("server", "http://127.0.0.1:${pc.address.port}").commit() }
            assertEquals(listOf("The PC" to Verdict.OK), verdicts(s)) // with a PC, no Jimaku / addon lines
        } finally { pc.stop(0) }
    }

    @Test fun aPcThatDoesntAnswerFails() {
        val s = settings().apply { prefs.edit().putString("server", "http://127.0.0.1:9").commit() } // nothing listens on 9
        assertEquals(listOf("The PC" to Verdict.FAIL), verdicts(s))
    }

    @Test fun noPcAndNothingSetSaysWhatToAdd() {
        val s = settings().apply { standalone = true }
        assertEquals(listOf("The PC" to Verdict.OK, "Jimaku" to Verdict.WARN, "Stremio addons" to Verdict.FAIL), verdicts(s))
    }
}
