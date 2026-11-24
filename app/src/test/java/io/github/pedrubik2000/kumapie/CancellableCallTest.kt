package io.github.pedrubik2000.kumapie

import io.github.pedrubik2000.kumapie.data.AniList
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class CancellableCallTest {
    @Test fun cancellingDisconnectsABlockedRequestAtOnce() = runBlocking {
        ServerSocket(0).use { server ->
            val ended = AtomicBoolean(false)
            try {
                withTimeout(300) {
                    AniList.cancellable { open ->
                        try {
                            val c = URL("http://127.0.0.1:${server.localPort}/").openConnection() as HttpURLConnection
                            c.readTimeout = 30_000
                            open(c)
                            c.inputStream.readBytes().size
                        } finally {
                            ended.set(true)
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                val t = System.nanoTime()
                while (!ended.get() && (System.nanoTime() - t) / 1_000_000 < 3_000) Thread.sleep(20)
                assertTrue(ended.get())
                return@runBlocking
            }
            throw AssertionError("expected a timeout")
        }
    }
}
