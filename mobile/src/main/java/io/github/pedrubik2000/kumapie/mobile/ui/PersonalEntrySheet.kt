package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.PersonalDictionary
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.ui.Colors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A personal dictionary entry for the text selected in a subtitle: the same as a word that exists (可可阿 = カカオ) or a
 * word of its own, with its reading and meaning. Opened on an entry you already have, it edits or deletes it.
 */
@Composable
internal fun PersonalEntrySheet(library: Library, lang: Lang, selected: String, onDone: () -> Unit) {
    val personal = library.yomitan.personal
    val old = remember { personal.entry(lang.code, selected) }
    var text by remember { mutableStateOf(old?.text ?: selected) }
    var target by remember { mutableStateOf(old?.target.orEmpty()) }
    var reading by remember { mutableStateOf(old?.reading.orEmpty()) }
    var meaning by remember { mutableStateOf(old?.meaning.orEmpty()) }
    val same = target.trim()
    // What "same as" points to, so a typo shows before it is saved.
    val found by produceState<String?>(null, same) {
        value = if (same.isEmpty()) null else withContext(Dispatchers.IO) {
            library.yomitan.query(lang, same).firstOrNull()?.let { t ->
                t.expression + (t.reading.takeIf { it.isNotBlank() && it != t.expression }?.let { " [$it]" } ?: "") +
                    (t.glossaries.firstOrNull()?.senses?.firstOrNull()?.let { ": $it" } ?: "")
            } ?: tr("Not in your dictionaries.")
        }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding().imePadding().padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("Personal dictionary"), color = Colors.accent, fontSize = 18.sp)
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(tr("Word, as written")) })
        OutlinedTextField(target, { target = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(tr("Same as (an existing word)")) })
        found?.let { Text(it, color = Colors.dim, fontSize = 14.sp, maxLines = 2) }
        // Same as a word: it has its own reading and meanings.
        if (same.isEmpty()) {
            if (lang == Lang.JAPANESE) OutlinedTextField(reading, { reading = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(tr("Reading")) })
            OutlinedTextField(meaning, { meaning = it }, Modifier.fillMaxWidth(), label = { Text(tr("Meaning")) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = text.isNotBlank() && same != text.trim() && (same.isNotEmpty() || meaning.isNotBlank()), onClick = {
                val word = text.trim()
                if (old != null && old.text != word) personal.delete(old)
                personal.save(PersonalDictionary.Entry(lang.code, word, same, if (same.isEmpty()) reading.trim() else "", if (same.isEmpty()) meaning.trim() else ""))
                library.syncPersonal()
                onDone()
            }) { Text(tr("Save")) }
            if (old != null) TextButton(onClick = {
                personal.delete(old)
                library.syncPersonal()
                onDone()
            }) { Text(tr("Delete")) }
        }
    }
}
