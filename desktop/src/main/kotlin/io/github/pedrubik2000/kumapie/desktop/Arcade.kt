package io.github.pedrubik2000.kumapie.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.ui.Colors
import io.github.pedrubik2000.kumapie.ui.Text
import kotlin.math.tan

/**
 * The TV's look on the PC (app/…/ui/Arcade.kt, mockup B1 "Arcade", yellow): slanted slabs, bold italic capitals, a hint
 * bar along the bottom. The mouse's hover plays the remote's focus: a hovered button turns yellow, a card gets the ring.
 */
object Arcade {
    val accent = Color(0xFFFFD23F)
    val background = Color(0xFF0B0A10)
    val band = Color(0xFF14121C)
    val slab = Color(0xFF1D1A28)
    val label = TextStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)

    /** Core's shared colours set to the TV's (its KumapieTheme does the same). */
    fun apply() {
        Colors.accent = accent
        Colors.background = background
        Colors.surface = slab
        Colors.picked = accent
    }
}

/** A parallelogram leaning right by [degrees]. */
fun slab(degrees: Float) = GenericShape { size, _ ->
    val d = size.height * tan(Math.toRadians(degrees.toDouble())).toFloat()
    moveTo(d, 0f); lineTo(size.width, 0f); lineTo(size.width - d, size.height); lineTo(0f, size.height); close()
}
val Slab = slab(12f)       // buttons, hints
val RowSlab = slab(8f)     // list rows, tiles
val PosterSlab = slab(4f)  // posters: tall, so a small lean
val EpisodeSlab = slab(3f) // an episode's row

/** A menu or dialog over the screen: the band colour under an accent line. */
fun Modifier.arcadePanel(): Modifier = background(Arcade.band).drawBehind { drawRect(Arcade.accent, size = Size(size.width, 3.dp.toPx())) }

/** The word card: dark, an accent bar on the left, the right edge slanted. */
fun Modifier.arcadeCard(): Modifier = clip(GenericShape { size, _ ->
    moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width - size.height * 0.09f, size.height); lineTo(0f, size.height); close()
}).background(Color(0xF20B0A10)).drawBehind { drawRect(Arcade.accent, size = Size(7.dp.toPx(), size.height)) }
    .padding(start = 7.dp, end = 30.dp)

/** The diagonal band and accent stripe behind the menus. */
fun Modifier.arcadeBackdrop(): Modifier = drawBehind {
    val w = size.width; val h = size.height
    drawPath(Path().apply { moveTo(w * 0.78f, 0f); lineTo(w, 0f); lineTo(w, h); lineTo(w * 0.60f, h); close() }, Arcade.band)
    drawPath(Path().apply { moveTo(w * 0.965f, 0f); lineTo(w * 0.985f, 0f); lineTo(w * 0.745f, h); lineTo(w * 0.725f, h); close() }, Arcade.accent)
}

/**
 * The TV's button: a slab with bold italic capitals; yellow with dark text under the mouse. [selected] = the chosen one
 * (yellow; white under the mouse). [key]: the keyboard key that does the same, in a key cap before the label.
 */
@Composable
fun ArcadeButton(label: String, modifier: Modifier = Modifier, selected: Boolean = false, enabled: Boolean = true,
                 key: String? = null, fontSize: TextUnit = 14.sp, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val lit = enabled && hovered
    val bg = when { !enabled -> Arcade.slab.copy(alpha = 0.5f); selected -> if (lit) Color.White else Arcade.accent; lit -> Arcade.accent; else -> Arcade.slab }
    val fg = when { !enabled -> Colors.dim.copy(alpha = 0.5f); selected || lit -> Arcade.background; else -> Colors.text }
    Row(modifier.scale(if (lit) 1.06f else 1f).background(bg, Slab)
        .then(if (enabled) Modifier.hoverable(hover).pointerHoverIcon(PointerIcon.Hand).clickable(hover, null, onClick = onClick) else Modifier)
        .padding(horizontal = 22.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        if (key != null) KeyCap(key, fg)
        Text(label.uppercase(), color = fg, fontSize = fontSize, style = Arcade.label)
    }
}

/** A clickable card: the TV's focus ring (yellow, 3 dp) and 6% growth under the mouse. */
@Composable
fun Modifier.arcadeClickable(shape: Shape, onClick: () -> Unit): Modifier {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    return this.scale(if (hovered) 1.06f else 1f).clip(shape)
        .border(3.dp, if (hovered) Arcade.accent else Color.Transparent, shape)
        .hoverable(hover).pointerHoverIcon(PointerIcon.Hand).clickable(hover, null, onClick = onClick)
}

/** A screen's title in two tones: KUMA + PIE. */
@Composable
fun Title(white: String, yellow: String = "", fontSize: TextUnit = 34.sp, modifier: Modifier = Modifier) =
    Text(buildAnnotatedString { append(white.uppercase()); withStyle(SpanStyle(Arcade.accent)) { append(yellow.uppercase()) } },
        modifier, color = Colors.text, fontSize = fontSize, style = TextStyle(fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic))

/** A section heading: a dash, then accent capitals. */
@Composable
fun Heading(text: String, modifier: Modifier = Modifier) = Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.width(20.dp).height(4.dp).background(Arcade.accent))
    Text(text.uppercase(), color = Arcade.accent, fontSize = 18.sp, style = Arcade.label, modifier = Modifier.padding(start = 9.dp))
}

/** A thin bar: how much of an episode was watched. */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(4.dp).background(Arcade.slab, RoundedCornerShape(2.dp))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(Arcade.accent, RoundedCornerShape(2.dp)))
    }
}

/** A keyboard key's name in a key cap (the TV shows the remote's button names this way). */
@Composable
fun KeyCap(name: String, color: Color = Colors.text) = Text(name, color = color, fontSize = 12.sp,
    style = TextStyle(fontWeight = FontWeight.Bold),
    modifier = Modifier.border(1.5.dp, color.copy(alpha = 0.6f), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 1.dp))

/** One slab of the [HintBar]: a key and what it does; [on] = the chosen one (accent). */
class Hint(val key: String, val label: String, val on: Boolean = false)

/** What the keys do now, along the bottom of the screen. */
@Composable
fun HintBar(hints: List<Hint>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(42.dp).background(Arcade.band)
        .drawBehind { drawRect(Arcade.accent, size = Size(size.width, 2.5.dp.toPx())) }.padding(start = 40.dp, top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        hints.forEach { h ->
            Row(Modifier.height(30.dp).background(if (h.on) Arcade.accent else Arcade.slab, Slab).padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                KeyCap(h.key, if (h.on) Arcade.background else Colors.text)
                Text(h.label.uppercase(), color = if (h.on) Arcade.background else Colors.text, fontSize = 13.sp, style = Arcade.label)
            }
        }
    }
}

/** The TV's level badge: i+0 green, i+1 yellow, more red, ♪ without words. */
@Composable
fun LevelBadge(level: Int?, modifier: Modifier = Modifier) {
    val (text, color) = when {
        level == null -> "♪" to Colors.dim
        level == 0 -> "i+0" to Colors.levelZero
        level == 1 -> "i+1" to Colors.levelOne
        else -> "i+$level" to Colors.unknown
    }
    Text(text, color = Color.Black, fontSize = 16.sp,
        modifier = modifier.background(color, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 2.dp))
}


/** One line cut with "…" (core's Text has no maxLines): titles under posters and cards. */
@Composable
fun OneLine(text: String, color: Color = Colors.text, fontSize: TextUnit = TextUnit.Unspecified, style: TextStyle = TextStyle(),
            modifier: Modifier = Modifier) =
    androidx.compose.foundation.text.BasicText(text, modifier, style.merge(color = color, fontSize = fontSize), maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
