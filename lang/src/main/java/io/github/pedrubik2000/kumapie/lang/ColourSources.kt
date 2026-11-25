package io.github.pedrubik2000.kumapie.lang

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.pedrubik2000.kumapie.data.AnkiSource
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class ColourSources(context: Context, private val settings: Settings, val languages: List<Language>, private val scope: CoroutineScope) {
    private val lookups = AnkiLookups(context.applicationContext)
    private val known get() = languages.first().known
    private var pending = AnkiSource.NONE
    private var stamp by mutableIntStateOf(0)

    var choice by mutableStateOf(settings.ankiSource)
        private set
    var apps by mutableStateOf(emptyList<String>())
        private set
    var listing by mutableStateOf(AnkiLookups.Listing())
        private set
    var said by mutableStateOf("")
        private set
    var blocked by mutableStateOf(false)
        private set

    val app: String? get() = AnkiSource.appFor(choice, apps)
    val kuma3: String? get() = AnkiSource.appFor(AnkiSource.KUMA3, apps)
    val ankiDroid: String? get() = AnkiSource.appFor(AnkiSource.ANKIDROID, apps)
    val kuma3On get() = AnkiSource.kuma3On(choice) && kuma3 != null
    val ankiDroidOn get() = AnkiSource.droidOn(choice) && ankiDroid != null
    val bothInstalled get() = kuma3 != null && ankiDroid != null
    val permitted: Boolean get() = stamp >= 0 && app?.let(known::hasPermission) != false
    val loadKey: Pair<String?, Int> get() = app to stamp
    val states get() = languages.map { it.known.ankiState }

    suspend fun load() {
        apps = lookups.apps()
        listing = app?.let { lookups.list(it) } ?: AnkiLookups.Listing()
    }

    fun retry() {
        stamp++
    }

    fun resumed() {
        stamp++
        reread()
    }

    fun toggle(which: String): String? {
        val kuma3Now = kuma3On != (which == AnkiSource.KUMA3)
        val droidNow = ankiDroidOn != (which == AnkiSource.ANKIDROID)
        return select(AnkiSource.choice(kuma3Now, droidNow))
    }

    fun select(wanted: String): String? {
        val target = AnkiSource.appFor(wanted, apps)
        return when {
            wanted == AnkiSource.NONE -> apply(wanted)
            target == null -> {
                said = tr("That Anki is not on this device. The source stays as it was.")
                null
            }
            known.hasPermission(target) -> apply(wanted)
            else -> {
                pending = wanted
                known.permission(target)
            }
        }
    }

    fun answered(granted: Boolean, canAskAgain: (String) -> Boolean?) {
        if (pending == AnkiSource.NONE) return
        if (granted) {
            apply(pending)
            pending = AnkiSource.NONE
            return
        }
        said = tr("Reading that Anki is off: you did not allow it. The source stays as it was.")
        val target = AnkiSource.appFor(pending, apps)
        blocked = target != null && canAskAgain(known.permission(target)) == false
        pending = AnkiSource.NONE
    }

    fun reread() {
        scope.launch { languages.forEach { it.known.readAnkiSource() } }
    }

    private fun apply(wanted: String): String? {
        settings.ankiSource = wanted
        choice = wanted
        stamp++
        said = ""
        blocked = false
        reread()
        return null
    }
}
