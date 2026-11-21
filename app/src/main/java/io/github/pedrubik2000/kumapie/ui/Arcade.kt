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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
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
 * The TV's look: the phone's "Glass" (user 2026-11-17: "the phone style and look in the TV version"; tokens from
 * `mobile/ui/MobileTheme.kt` `Glass`): the #07090D page, white text, ice-blue accent, white-glass surfaces with a thin
 * white line, pill buttons (focused: filled white, black text), rounded pictures. The names stay from the old "Arcade"
 * look (yellow slabs, 2026-11-09) so the screens didn't change. A hint bar of PS5 glyphs or remote names along the bottom.
 */
object Arcade {
    val accent = Color(0xFF9CC9FF)   // ice blue: ticks, numbers, the selected choice
    val background = Color(0xFF07090D) // the page
    val band = Color(0xFF14171D)     // panels and dialogs: opaque glass over the page
    // Glass fill. The phone's is white 12 % over a blurred, dimmed picture; a TV has no blur and its backdrops are bright,
    // where white glass left white text unreadable (the title page, 2026-11-17): dark glass, the page at 55 %.
    val slab = Color(0xFF07090D).copy(alpha = 0.55f)
    val line = Color.White.copy(alpha = 0.18f) // glass edge
    val label = TextStyle(fontWeight = FontWeight.SemiBold)
}

/** Rounded corners of [radius] dp (the old slanted slabs' names, kept for the screens). */
fun slab(radius: Float) = RoundedCornerShape(radius.dp)
val Slab = RoundedCornerShape(24.dp)       // buttons: pills
val RowSlab = RoundedCornerShape(16.dp)    // list rows, tiles
val PosterSlab = RoundedCornerShape(14.dp) // posters
val EpisodeSlab = RoundedCornerShape(16.dp) // an episode's row

/** A menu or dialog over the screen: opaque glass with its thin white edge. */
fun Modifier.arcadePanel(): Modifier = background(Arcade.band, RoundedCornerShape(22.dp)).border(1.dp, Arcade.line, RoundedCornerShape(22.dp))

@Composable fun arcadeCardShape(shape: Shape) = CardDefaults.shape(shape)
/** Cards: a thin glass edge, white when focused. */
@Composable fun arcadeCardBorder(shape: Shape) = CardDefaults.border(
    border = Border(BorderStroke(1.dp, Arcade.line), shape = shape),
    focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = shape))

/** The word card: opaque glass, rounded as the phone's popup. */
fun Modifier.arcadeCard(): Modifier = clip(RoundedCornerShape(22.dp)).background(Arcade.band)
    .border(1.dp, Arcade.line, RoundedCornerShape(22.dp)).padding(start = 16.dp, end = 24.dp)

/**
 * How lists follow the focus on a TV (user 2026-11-18, "add the animation"): a 300 ms glide. The list stays put
 * while the focused item shows away from the edges; past them it glides so the item starts at 30 % (a pivot, as
 * Google TV). Compose's default moved them in ~2 frames on the Chromecast (64 frames for a Home run, Google TV's
 * home 720), which read as lag.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
val TvScroll = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> =
        androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)

    /** The item's start goes to 30 % of the list (a pivot), unless it already shows whole away from the edges. */
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val pivot = containerSize * 0.3f
        val visible = offset >= 0f && offset + size <= containerSize
        return when {
            visible && offset >= containerSize * 0.1f && offset + size <= containerSize * 0.9f -> 0f
            size > containerSize - pivot -> offset // too big for the pivot: its start at the list's start
            else -> offset - pivot
        }
    }
}

/** A list's top edge fades out: rows scrolled up under a fixed header showed cut flat in half. */
fun Modifier.fadeTop(until: Float = 0.08f): Modifier = graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(0f to Color.Transparent, until to Color.Black),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
    }

val glowCovered = androidx.compose.runtime.mutableStateOf(false)

@androidx.compose.runtime.Composable
fun CoverPicture(image: String, modifier: Modifier) {
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { glowCovered.value = false } }
    coil3.compose.AsyncImage(image, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, alignment = androidx.compose.ui.Alignment.TopEnd,
        modifier = modifier, onLoading = { glowCovered.value = false }, onSuccess = { glowCovered.value = true }, onError = { glowCovered.value = false })
}

/** Behind the menus: the page with a faint light from the top left (the old diagonal band is gone). */
fun Modifier.arcadeBackdrop(): Modifier =
    // The glow painted once at a quarter size and stretched: a radial gradient over the whole screen on every frame
    // cost the Chromecast's GPU ~5 ms a frame while lists moved (2026-11-18, Home glide: p50 29-30 ms -> 24-25).
    // Opaque, the page colour past the glow: the one full-screen paint (the window draws none, themes.xml).
    drawWithCache {
        val w = (size.width / 4).toInt().coerceAtLeast(1); val h = (size.height / 4).toInt().coerceAtLeast(1)
        val glow = androidx.compose.ui.graphics.ImageBitmap(w, h)
        androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(this, layoutDirection, androidx.compose.ui.graphics.Canvas(glow),
            androidx.compose.ui.geometry.Size(w.toFloat(), h.toFloat())) {
            drawRect(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color(0xFF1A2333), Colors.background),
                center = Offset(w * 0.15f, 0f), radius = w * 0.7f))
        }
        onDrawBehind {
            if (!glowCovered.value) drawImage(glow, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                filterQuality = androidx.compose.ui.graphics.FilterQuality.Low)
        }
    }

/**
 * A cover picture with [NeutralPoster] only until it loads: drawing both cost the Chromecast's GPU a layer per card
 * while lists moved (2026-11-18).
 */
@Composable
internal fun Cover(model: Any?, title: String, modifier: Modifier, showTitle: Boolean = true,
                   fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
                   alignment: Alignment = Alignment.Center, description: String? = title) {
    var loaded by androidx.compose.runtime.remember(model) { mutableStateOf(false) }
    Box(modifier) {
        if (!loaded) {
            if (fontSize == androidx.compose.ui.unit.TextUnit.Unspecified) NeutralPoster(title, Modifier.matchParentSize(), showTitle = showTitle)
            else NeutralPoster(title, Modifier.matchParentSize(), fontSize = fontSize, showTitle = showTitle)
        }
        coil3.compose.AsyncImage(model = model, contentDescription = description, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            alignment = alignment, modifier = Modifier.matchParentSize(), onSuccess = { loaded = true }, onError = { loaded = false })
    }
}

/** A card's fill: none, the picture covers it (one GPU layer less per card). */
@Composable fun arcadeCardColors() = CardDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent,
    pressedContainerColor = Color.Transparent)

@Composable
fun arcadeButtonColors(selected: Boolean = false): ButtonColors = ButtonDefaults.colors(
    // Glass; the selected choice in ice blue; focused = filled white with black text (the phone's current menu button).
    containerColor = Arcade.slab, contentColor = if (selected) Arcade.accent else Colors.text,
    focusedContainerColor = Color.White, focusedContentColor = Color.Black)

/** The TV's button: a glass pill with a thin edge; filled white with black text when focused. Screens use it in place of tv-material's. */
@Composable
fun Button(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
           scale: ButtonScale = ButtonDefaults.scale(focusedScale = 1.05f), colors: ButtonColors = arcadeButtonColors(),
           padding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
           content: @Composable RowScope.() -> Unit) =
    androidx.tv.material3.Button(onClick = onClick, modifier = modifier, enabled = enabled, scale = scale, colors = colors,
        shape = ButtonDefaults.shape(Slab), contentPadding = padding,
        border = ButtonDefaults.border(border = Border(BorderStroke(1.dp, Arcade.line), shape = Slab),
            focusedBorder = Border(BorderStroke(1.dp, Color.White), shape = Slab))) {
        ProvideTextStyle(Arcade.label) { content() }
    }

/** A screen's title in two tones: kumapie's name white, a second part ice blue. */
@Composable
fun Title(white: String, yellow: String = "", fontSize: TextUnit = 34.sp, modifier: Modifier = Modifier) =
    Text(buildAnnotatedString { append(white); withStyle(SpanStyle(Arcade.accent)) { append(yellow) } },
        color = Colors.text, fontSize = fontSize, fontWeight = FontWeight.Bold, modifier = modifier)

/** A section heading: white, semibold (the phone's row titles). */
@Composable
fun Heading(text: String, modifier: Modifier = Modifier) =
    Text(text, color = Colors.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)

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

private val SIX_BUTTONS = ARROWS + setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)

/** The buttons of [bindings] on the input in use: the pad's own (else its D-pad), or the remote's. A six-button remote's first (← → ↑ ↓ OK), then short ones first. */
fun forInput(bindings: Collection<Binding>): List<Binding> {
    val all = bindings.sortedWith(compareBy({ it.code !in SIX_BUTTONS }, { it.long })).distinctBy { it.label }
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

/** The D-pad with one arm lit in [color]: [arm] 0 left, 1 down, 2 right, 3 up. The same arrows on the remote. */
@Composable
fun DpadArm(arm: Int, color: Color, dp: Dp = 26.dp) = Canvas(Modifier.size(dp)) {
    scale(size.width / 48f, pivot = Offset.Zero) {
        drawPath(PathParser().parsePathString("M18 5h12v13h13v12H30v13H18V30H5V18h13Z").toPath(), Color(0xFF3A3648))
        val (x, y) = listOf(5f to 18f, 18f to 30f, 30f to 18f, 18f to 5f)[arm]
        drawRect(color, Offset(x, y), if (arm % 2 == 0) Size(13f, 12f) else Size(12f, 13f))
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
                Text(h.label, color = if (h.on) Arcade.background else Colors.text, fontSize = 13.sp, style = Arcade.label, maxLines = 1)
            }
        }
    }
}
