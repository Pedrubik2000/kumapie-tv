package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Route
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.data.route
import io.github.pedrubik2000.kumapie.data.streamFound
import io.github.pedrubik2000.kumapie.data.streamQuery
import org.junit.Assert.assertEquals
import org.junit.Test

/** [route]: a Home card opens the player (library), the PC's ready file (Watch) or the title page to pick one (Discover). */
class RouteTest {
    private fun ep(ready: Boolean = false) = Episode("kitsu:46474:6", "Episode 6", 1, 6, 1440.0, "", 0, 0, 0, null, ready = ready)
    private fun stream(e: Episode) = Show("kitsu:46474", "Sousou no Frieren", "stream", "", listOf(e), "ja")

    @Test fun noPcStreamCardGoesToDiscover() {
        // Addons.going makes its cards without ready: the default must not send them to the PC (2026-11-20: "No PC for /api/tv/watch").
        val e = ep()
        assertEquals(Route.Discover(streamFound(stream(e), e)), route(stream(e), e, hasPc = false))
    }

    @Test fun noPcIgnoresReady() {
        val e = ep(ready = true)
        assertEquals(Route.Discover(streamFound(stream(e), e)), route(stream(e), e, hasPc = false))
    }

    @Test fun pcReadyGoesToWatch() {
        val e = ep(ready = true)
        assertEquals(Route.Watch(streamQuery(stream(e), e)), route(stream(e), e, hasPc = true))
    }

    @Test fun pcNotReadyGoesToDiscover() {
        val e = ep()
        assertEquals(Route.Discover(streamFound(stream(e), e)), route(stream(e), e, hasPc = true))
    }

    @Test fun libraryShowGoesToPlayer() {
        val e = ep(ready = true)
        val show = Show("show-1", "Dark", "shows", "", listOf(e), "ja")
        assertEquals(Route.Player, route(show, e, hasPc = true))
        assertEquals(Route.Player, route(show, e, hasPc = false))
    }
}
