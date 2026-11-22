package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import io.github.pedrubik2000.kumapie.data.KeyService
import io.github.pedrubik2000.kumapie.data.checkKey
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.ui.Colors
import kotlinx.coroutines.launch

/**
 * A key or token: saved as typed (spaces and line breaks from a paste trimmed), hidden unless shown, and checked
 * against its service when the field is left, so a wrong key shows up here and not as a failed episode later.
 */
@Composable
internal fun KeyField(value: String, onChange: (String) -> Unit, label: String, service: KeyService) {
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) } // null: not checked; "checking"; checkKey's answers
    var checked by remember { mutableStateOf<String?>(null) } // the key the result is for
    fun check() {
        if (value.isBlank() || value == checked) return
        checked = value; result = "checking"
        scope.launch { result = checkKey(service, value) }
    }
    OutlinedTextField(value, { v -> onChange(v.trim()); if (v.trim() != checked) result = null },
        label = { Text(label) }, singleLine = true, isError = result == "rejected",
        visualTransformation = if (shown) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
        trailingIcon = { IconButton(onClick = { shown = !shown }) {
            Icon(if (shown) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (shown) tr("Hide") else tr("Show"))
        } },
        supportingText = result?.let { r -> { Text(when {
            r == "checking" -> tr("Checking with %s…", service.label)
            r == "ok" -> tr("✓ %s accepted it.", service.label)
            r == "rejected" -> tr("%1\$s didn't accept this key. Copy it again from %2\$s.", service.label, service.where)
            r == "offline" -> tr("Couldn't reach %s to check it. It's saved and will be tried when you make an episode.", service.label)
            else -> tr("%1\$s answered %2\$s. It's saved; try again later.", service.label, r.removePrefix("http "))
        }, color = if (r == "rejected") Colors.unknown else Colors.dim) } },
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) check() })
}

@Composable
internal fun RdMissingLine(settings: io.github.pedrubik2000.kumapie.data.Settings, modifier: Modifier, onSettings: () -> Unit) {
    if (!io.github.pedrubik2000.kumapie.data.Startup.rdMissing(settings)) return
    androidx.compose.material3.Button(onClick = onSettings, modifier = modifier,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Colors.unknown)) {
        Text(tr("Real-Debrid key missing: no video will play"))
    }
}
