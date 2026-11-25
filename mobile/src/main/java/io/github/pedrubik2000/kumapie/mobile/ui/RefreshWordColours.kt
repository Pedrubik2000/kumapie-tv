package io.github.pedrubik2000.kumapie.mobile.ui

import io.github.pedrubik2000.kumapie.mobile.offline.Library

internal suspend fun Library.refreshWordColours() {
    if (settings.plainWords) return
    languages.all.map { it.known }.filter { k -> k.modelReady && (k.ready || k.ankiApp()?.let(k::hasPermission) == true) }
        .forEach { it.refresh() }
}
