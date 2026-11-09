package io.github.pedrubik2000.kumapie.ui

import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonColors
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ButtonScale
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.player.Binding
import io.github.pedrubik2000.kumapie.player.keyLabel
import kotlin.math.tan

/**
 * The TV's look (user, 2026-11-09: mockup B1 "Arcade", yellow): slanted slabs, bold italic capitals, a hint bar of
 * PS5 glyphs or remote names along the bottom. The colours are set in [KumapieTheme]; the subtitle word colours stay.
 */
object Arcade {
    val accent = Color(0xFFFFD23F)
    val background = Color(0xFF0B0A10)
    val band = Color(0xFF14121C)
    val slab = Color(0xFF1D1A28)
    val label = TextStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
}

/** A parallelogram leaning right by [degrees]. */
fun slab(degrees: Float) = GenericShape { size, _ ->
    val d = size.height * tan(Math.toRadians(degrees.toDouble())).toFloat()
    moveTo(d, 0f); lineTo(size.width, 0f); lineTo(size.width - d, size.height); lineTo(0f, size.height); close()
}
val Slab = slab(12f)      // buttons, hints
val RowSlab = slab(8f)    // list rows, tiles
val PosterSlab = slab(4f) // posters: tall, so a small lean
val EpisodeSlab = slab(3f) // an episode's row: its picture sits 16 dp from the edge

/** A menu or dialog over the screen: the band colour under an accent line. */
fun Modifier.arcadePanel(): Modifier = background(Arcade.band).drawBehind { drawRect(Arcade.accent, size = Size(size.width, 3.dp.toPx())) }

@Composable fun arcadeCardShape(shape: Shape) = CardDefaults.shape(shape)
@Composable fun arcadeCardBorder(shape: Shape) = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Arcade.accent), shape = shape))

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

@Composable
fun arcadeButtonColors(selected: Boolean = false): ButtonColors = ButtonDefaults.colors(
    containerColor = if (selected) Arcade.accent else Arcade.slab, contentColor = if (selected) Arcade.background else Colors.text,
    focusedContainerColor = if (selected) Color.White else Arcade.accent, focusedContentColor = Arcade.background)

/** The TV's button: a slab, accent with dark bold italic text when focused. Screens use it in place of tv-material's. */
@Composable
fun Button(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
           scale: ButtonScale = ButtonDefaults.scale(focusedScale = 1.06f), colors: ButtonColors = arcadeButtonColors(),
           content: @Composable RowScope.() -> Unit) =
    androidx.tv.material3.Button(onClick = onClick, modifier = modifier, enabled = enabled, scale = scale, colors = colors,
        shape = ButtonDefaults.shape(Slab), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)) {
        ProvideTextStyle(Arcade.label) { content() }
    }

/** A screen's title in two tones: KUMA + PIE. */
@Composable
fun Title(white: String, yellow: String = "", fontSize: TextUnit = 34.sp, modifier: Modifier = Modifier) =
    Text(buildAnnotatedString { append(white.uppercase()); withStyle(SpanStyle(Arcade.accent)) { append(yellow.uppercase()) } },
        color = Colors.text, fontSize = fontSize, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, modifier = modifier)

/** A section heading: a dash, then accent capitals. */
@Composable
fun Heading(text: String, modifier: Modifier = Modifier) = Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.width(20.dp).height(4.dp).background(Arcade.accent))
    Text(text.uppercase(), color = Arcade.accent, fontSize = 18.sp, style = Arcade.label, modifier = Modifier.padding(start = 9.dp))
}

// ------------------------------------------------------------ remote or gamepad: the last one used

/** The last input used: hints show PS5 glyphs after a gamepad press, the remote's names after a remote press. */
object Pad {
    var on by mutableStateOf(false)
        private set

    /** Every key press passes here (the activities' dispatchKeyEvent). */
    fun seen(e: KeyEvent) {
        if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount > 0) return
        // The pad's D-pad and B arrive as arrows and Back: its device says it is a gamepad.
        on = KeyEvent.isGamepadButton(e.keyCode) || e.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
            e.device?.let { it.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD } == true
    }
}

private val ARROWS = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)

/** The buttons of [bindings] on the input in use: the pad's own (else its D-pad), or the remote's. Short ones first. */
fun forInput(bindings: Collection<Binding>): List<Binding> {
    val all = bindings.sortedBy { it.long }.distinctBy { it.label }
    if (!Pad.on) return all.filter { !KeyEvent.isGamepadButton(it.code) }
    return all.filter { KeyEvent.isGamepadButton(it.code) }.ifEmpty { all.filter { it.code in ARROWS } }
}

/** The whole D-pad, for [Glyph]. */
const val DPAD = -1

/** A PS5 button on a 48 x 48 grid, [dp] tall: ✕ ○ □ △, L1/R1/L2/R2, Options, Create, the D-pad. */
@Composable
fun Glyph(code: Int, dp: Dp = 26.dp) {
    val pill = when (code) {
        KeyEvent.KEYCODE_BUTTON_L1 -> "L1"; KeyEvent.KEYCODE_BUTTON_R1 -> "R1"
        KeyEvent.KEYCODE_BUTTON_L2 -> "L2"; KeyEvent.KEYCODE_BUTTON_R2 -> "R2"
        KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"; KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
        else -> null
    }
    if (pill != null) {
        Box(Modifier.size(dp * 1.4f, dp * 0.66f).background(Color.Black, RoundedCornerShape(35)), contentAlignment = Alignment.Center) {
            Text(pill, color = Color.White, fontSize = (dp.value * 0.4f).sp, lineHeight = (dp.value * 0.4f).sp, fontWeight = FontWeight.Bold)
        }
        return
    }
    Canvas(Modifier.size(dp)) {
        scale(size.width / 48f, pivot = Offset.Zero) {
            val pen = Stroke(3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(c: Color, x1: Float, y1: Float, x2: Float, y2: Float, w: Float = 3.2f) =
                drawLine(c, Offset(x1, y1), Offset(x2, y2), w, StrokeCap.Round)
            fun disc() = drawCircle(Color.Black, 21f, Offset(24f, 24f))
            val edge = Color.White.copy(alpha = 0.85f) // black on the dark slabs needs one
            when (code) {
                KeyEvent.KEYCODE_BUTTON_A -> { disc(); val c = Color(0xFF6FA8FF); line(c, 15f, 15f, 33f, 33f); line(c, 33f, 15f, 15f, 33f) }
                KeyEvent.KEYCODE_BUTTON_B -> { disc(); drawCircle(Color(0xFFFF5A6E), 10f, Offset(24f, 24f), style = pen) }
                KeyEvent.KEYCODE_BUTTON_X -> { disc(); drawRect(Color(0xFFF08BD0), Offset(14.5f, 14.5f), Size(19f, 19f), style = pen) }
                KeyEvent.KEYCODE_BUTTON_Y -> { disc(); drawPath(PathParser().parsePathString("M24 12 36 33H12Z").toPath(), Color(0xFF39D5A0), style = pen) }
                KeyEvent.KEYCODE_BUTTON_START -> { // Options
                    drawRoundRect(Color.Black, Offset(15f, 8f), Size(18f, 32f), CornerRadius(9f))
                    drawRoundRect(edge, Offset(15f, 8f), Size(18f, 32f), CornerRadius(9f), style = Stroke(1.6f))
                    for (y in listOf(19f, 24f, 29f)) line(Color.White, 20f, y, 28f, y, 2.2f)
                }
                KeyEvent.KEYCODE_BUTTON_SELECT -> { // Create
                    drawRoundRect(Color.Black, Offset(15f, 14f), Size(18f, 28f), CornerRadius(9f))
                    drawRoundRect(edge, Offset(15f, 14f), Size(18f, 28f), CornerRadius(9f), style = Stroke(1.6f))
                    line(Color.White, 24f, 4f, 24f, 10f, 2.2f); line(Color.White, 14f, 6f, 17f, 11f, 2.2f); line(Color.White, 34f, 6f, 31f, 11f, 2.2f)
                }
                else -> {
                    val cross = PathParser().parsePathString("M18 5h12v13h13v12H30v13H18V30H5V18h13Z").toPath()
                    drawPath(cross, Color.Black)
                    drawPath(cross, edge, style = Stroke(1.6f, join = StrokeJoin.Round))
                }
            }
        }
    }
}

/** A remote button's name in a key cap. */
@Composable
fun KeyCap(name: String, color: Color = Colors.text) = Text(name, color = color, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1,
    modifier = Modifier.border(1.5.dp, color.copy(alpha = 0.6f), RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 1.dp))

/** One button: its PS5 glyph, or its name on the remote; a small dim "hold" before a long press. */
@Composable
fun KeyHint(b: Binding, color: Color = Colors.text) = Row(verticalAlignment = Alignment.CenterVertically) {
    if (b.long) Text(tr("hold"), color = color.copy(alpha = 0.65f), fontSize = 11.sp, modifier = Modifier.padding(end = 5.dp))
    if (KeyEvent.isGamepadButton(b.code)) Glyph(b.code) else KeyCap(keyLabel(b.code), color)
}

/** An action's buttons on the input in use (all of them when it has none there), "—" without any; [color]: on a focused row, its text's. */
@Composable
fun KeyHints(bindings: Collection<Binding>, modifier: Modifier = Modifier, color: Color = Colors.text) =
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val shown = forInput(bindings).ifEmpty { bindings.sortedBy { it.long }.distinctBy { it.label } }
        if (shown.isEmpty()) Text("—", color = color.copy(alpha = 0.6f)) else shown.forEach { KeyHint(it, color) }
    }

/** One slab of the [HintBar]: a button ([key]) and what it does; [on] = the chosen one (accent). */
class Hint(val label: String, val on: Boolean = false, val key: (@Composable () -> Unit)? = null)

/** What the buttons do now, along the bottom of the screen. */
@Composable
fun HintBar(hints: List<Hint>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(42.dp).background(Arcade.band)
        .drawBehind { drawRect(Arcade.accent, size = Size(size.width, 2.5.dp.toPx())) }.padding(start = 40.dp, top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        hints.forEach { h ->
            Row(Modifier.height(30.dp).background(if (h.on) Arcade.accent else Arcade.slab, Slab).padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                h.key?.invoke()
                Text(h.label.uppercase(), color = if (h.on) Arcade.background else Colors.text, fontSize = 13.sp, style = Arcade.label, maxLines = 1)
            }
        }
    }
}
