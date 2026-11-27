package io.github.pedrubik2000.kumapie.ui

import io.github.pedrubik2000.kumapie.data.Addons
import io.github.pedrubik2000.kumapie.data.Settings

/** What the page shell (rail + header) needs, for tests that open a top-level screen on its own. */
internal val NO_NAV = RailNav({}, {}, {}, {}, {}, {})

internal fun testApi(s: Settings) = Addons(s.addons, s.prefs)
