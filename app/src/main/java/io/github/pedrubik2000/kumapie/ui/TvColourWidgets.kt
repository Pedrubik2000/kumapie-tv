package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.i18n.tr

@Composable
internal fun Modifier.tvRing(shape: Shape, source: MutableInteractionSource): Modifier {
    var focused by remember { mutableStateOf(false) }
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(focused) { if (focused) requester.bringIntoView() }
    return onFocusChanged { focused = it.isFocused }.bringIntoViewRequester(requester).drawWithContent {
        drawContent()
        if (focused) drawOutline(shape.createOutline(size, layoutDirection, this), Color.White, style = Stroke(3.dp.toPx()))
    }
}

@Composable
internal fun TvCaption(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), color = Colors.dim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, modifier = modifier)

@Composable
internal fun TvCheck(checked: Boolean, description: String, enabled: Boolean = true, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(5.dp)
    val source = remember { MutableInteractionSource() }
    Box(modifier.size(24.dp).hint(HintKind.TICK).tvRing(shape, source).semantics { contentDescription = description }
        .toggleable(value = checked, interactionSource = source, indication = null, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
        .then(if (checked) Modifier.background(Color.White.copy(alpha = if (enabled) 1f else 0.6f), shape) else Modifier.border(2.dp, Color.White.copy(alpha = 0.4f), shape)),
        contentAlignment = Alignment.Center) {
        if (checked) Icon(Icons.Filled.Check, null, tint = Color.Black, modifier = Modifier.size(16.dp))
    }
}

@Composable
internal fun TvSwitch(on: Boolean, description: String, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(50)
    val source = remember { MutableInteractionSource() }
    Box(modifier.size(width = 44.dp, height = 26.dp).hint(HintKind.CHECK).tvRing(shape, source).semantics { contentDescription = description }
        .toggleable(value = on, interactionSource = source, indication = null, role = Role.Switch, onValueChange = onChange)
        .background(if (on) Color.White else Arcade.line, shape).padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.size(20.dp).background(if (on) Color.Black else Color.White, CircleShape))
    }
}

@Composable
internal fun TvChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val source = remember { MutableInteractionSource() }
    Text(label, color = if (selected) Color.Black else Colors.text, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
        modifier = modifier.hint(HintKind.CHOOSE).tvRing(shape, source).then(if (selected) Modifier.background(Color.White, shape) else Modifier.background(Arcade.band, shape).border(1.dp, Arcade.line, shape))
            .selectable(selected, interactionSource = source, indication = null, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp))
}

@Composable
internal fun TvRadioCard(selected: Boolean, title: String, subtitle: String, modifier: Modifier = Modifier, onSelect: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val source = remember { MutableInteractionSource() }
    Row(modifier.fillMaxWidth().hint(HintKind.CHOOSE).tvRing(shape, source)
        .then(if (selected) Modifier.background(Arcade.band, shape).border(1.5.dp, Color.White, shape) else Modifier.background(Arcade.slab, shape).border(1.dp, Arcade.line, shape))
        .selectable(selected, interactionSource = source, indication = null, role = Role.RadioButton, onClick = onSelect).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(16.dp).border(2.dp, if (selected) Color.White else Color.White.copy(alpha = 0.4f), CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(7.dp).background(Color.White, CircleShape))
        }
        Column {
            Text(title, color = Colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Colors.dim, fontSize = 14.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
internal fun TvSourceRow(logo: String, logoColour: Color, title: String, subtitle: String, checked: Boolean, expanded: Boolean, count: String? = null,
                         dimCount: Boolean = false, canTick: Boolean = true, checkModifier: Modifier = Modifier, onTick: (Boolean) -> Unit, onExpand: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvCheck(checked, tr("Count %s as known words", title), canTick, checkModifier, onTick)
            val shape = RoundedCornerShape(10.dp)
            val source = remember { MutableInteractionSource() }
            Row(Modifier.weight(1f).hint(HintKind.CHOOSE).tvRing(shape, source)
                .clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = if (expanded) tr("Collapse") else tr("Expand"), onClick = onExpand).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(32.dp).background(logoColour, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                    Text(logo, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                }
                Column(Modifier.weight(1f)) {
                    Text(title, color = Colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(subtitle, color = Colors.dim, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 2)
                }
                count?.let { Text(it, color = if (dimCount) Colors.dim else Colors.due, fontSize = 14.sp, maxLines = 1) }
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (expanded) tr("Collapse") else tr("Expand"), tint = Colors.dim, modifier = Modifier.size(18.dp))
            }
        }
        if (expanded) Column(Modifier.fillMaxWidth().padding(start = 54.dp, end = 12.dp, bottom = 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}
