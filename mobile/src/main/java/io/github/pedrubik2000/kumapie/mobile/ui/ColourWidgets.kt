package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.ui.Colors

@Composable
internal fun ColourCaption(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), color = Glass.dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, modifier = modifier)

@Composable
internal fun ColourCheck(checked: Boolean, description: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = description }
        .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(7.dp)
        Box(Modifier.size(24.dp).then(if (checked) Modifier.background(Color.White.copy(alpha = if (enabled) 1f else 0.6f), shape) else Modifier.border(2.dp, Color.White.copy(alpha = 0.4f), shape)),
            contentAlignment = Alignment.Center) {
            if (checked) Icon(Icons.Filled.Check, null, tint = Color.Black, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun ColourRadioCard(selected: Boolean, title: String, subtitle: String, onSelect: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(Modifier.fillMaxWidth().then(if (selected) Modifier.background(Glass.solid, shape).border(1.5.dp, Color.White, shape) else Modifier.glass(shape))
        .selectable(selected, role = Role.RadioButton, onClick = onSelect).padding(horizontal = 16.dp, vertical = 12.dp).sizeIn(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(20.dp).border(2.dp, if (selected) Color.White else Color.White.copy(alpha = 0.4f), androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(10.dp).background(Color.White, androidx.compose.foundation.shape.CircleShape))
        }
        Column {
            Text(title, color = Colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Glass.dim, fontSize = 13.sp)
        }
    }
}

@Composable
internal fun DashedNote(title: String, lines: List<String>) {
    Column(Modifier.fillMaxWidth().drawBehind {
        drawRoundRect(Glass.line, cornerRadius = CornerRadius(16.dp.toPx()), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))))
    }.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = Colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        lines.forEach { Text(it, color = Glass.dim, fontSize = 14.sp) }
    }
}

@Composable
internal fun ColourSourceRow(logo: String, logoColour: Color, title: String, subtitle: String, checked: Boolean, expanded: Boolean,
                             count: String? = null, dimCount: Boolean = false, canTick: Boolean = true, onTick: (Boolean) -> Unit, onExpand: () -> Unit,
                             content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(onClickLabel = if (expanded) tr("Collapse") else tr("Expand"), onClick = onExpand).padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ColourCheck(checked, tr("Count %s as known words", title), canTick, onTick)
            Box(Modifier.size(34.dp).background(logoColour, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Text(logo, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = Colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Glass.dim, fontSize = 12.5.sp)
            }
            count?.let { Text(it, color = if (dimCount) Glass.dim else Colors.due, fontSize = 13.sp) }
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (expanded) tr("Collapse") else tr("Expand"), tint = Glass.dim)
        }
        if (expanded) Column(Modifier.fillMaxWidth().padding(start = 64.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}
