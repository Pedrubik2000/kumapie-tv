package io.github.pedrubik2000.kumapie.lang

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.pedrubik2000.kumapie.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ColourJiten(private val known: KnownWords, private val scope: CoroutineScope, private val onImported: () -> Unit = {}) {
    private val last = known.jitenChoice

    var on by mutableStateOf(last != null)
        private set
    var top by mutableIntStateOf(last?.first ?: TOPS[2])
    val sets = mutableStateMapOf<String, Boolean>().apply { Jiten.SETS.keys.forEach { put(it, last?.second?.contains(it) ?: true) } }
    var busy by mutableStateOf(false)
        private set
    var imported by mutableIntStateOf(known.importedWords.size)
        private set
    var problem by mutableStateOf("")
        private set
    var confirming by mutableStateOf(false)
        private set

    val fromSync: Boolean get() = !on && imported > 0

    val subtitle: String
        get() = if (problem.isNotEmpty()) problem
        else if (busy) tr("Downloading from jiten.moe…")
        else if (on) tr("%d words imported as known.", imported)
        else if (fromSync) tr("%d words known from another device", imported)
        else tr("Public frequency list from jiten.moe · never imported yet")

    fun tick(): Boolean {
        if (!on) return true
        confirming = true
        return false
    }

    fun keep() {
        confirming = false
    }

    fun remove() {
        confirming = false
        scope.launch {
            withContext(Dispatchers.IO) { known.removeImported() }
            imported = 0
            on = false
            onImported()
        }
    }

    fun importNow() {
        if (busy) return
        busy = true
        problem = ""
        scope.launch {
            runCatching { known.importJiten(top, sets.filterValues { it }.keys.toSet()) }
                .onSuccess {
                    imported = known.importedWords.size
                    on = true
                    onImported()
                }
                .onFailure { problem = it.message ?: it.toString() }
            busy = false
        }
    }

    companion object {
        val TOPS = listOf(1000, 2000, 5000, 10000)
    }
}
