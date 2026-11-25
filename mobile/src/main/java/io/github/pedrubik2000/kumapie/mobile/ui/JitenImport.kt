package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.ui.Colors
import kotlinx.coroutines.launch

/**
 * Japanese words imported as known from jiten.moe (kumapie_plan.md item 6): its top words by frequency and the
 * picked word sets. They count as known while the word has no Mined card; names and fillers are known anyway.
 */
@Composable
internal fun JitenImport(known: io.github.pedrubik2000.kumapie.lang.KnownWords, onImported: () -> Unit) {
    val jiten = io.github.pedrubik2000.kumapie.lang.Jiten
    val scope = rememberCoroutineScope()
    // What was imported last time, to change it (the words you marked yourself are kept apart: an import never touches them).
    val last = remember { known.jitenChoice }
    var top by remember { mutableStateOf((last?.first ?: 10000).toString()) }
    val sets = remember { androidx.compose.runtime.mutableStateMapOf(*jiten.SETS.keys.map { it to (last?.second?.contains(it) ?: true) }.toTypedArray()) }
    var busy by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf(known.importedWords.size.takeIf { it > 0 }?.let { tr("%d words imported as known.", it) } ?: "") }

    Text(tr("Known words from jiten.moe"), color = Colors.text, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
    OutlinedTextField(top, { top = it.filter(Char::isDigit) }, label = { Text(tr("Most frequent words")) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
    jiten.SETS.forEach { (slug, name) ->
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(sets[slug] == true, { sets[slug] = it })
            Text(tr(name), fontSize = 15.sp)
        }
    }
    androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            said = tr("Downloading from jiten.moe…")
            scope.launch {
                said = runCatching {
                    val n = known.importJiten(top.toIntOrNull() ?: 0, sets.filterValues { it }.keys.toSet()); onImported()
                    tr("%d words imported as known.", n)
                }.getOrElse { it.message ?: it.toString() }
                busy = false
            }
        }) { Text(tr("Import")) }
    }
    if (said.isNotEmpty()) Text(said, fontSize = 14.sp, color = Colors.dim)
    Text(tr("Known while the word has no card in Japanese Mined. Names and fillers (えっと) count as known anyway. Changing this keeps the words you marked yourself."),
        fontSize = 13.sp, color = Colors.dim)
}
