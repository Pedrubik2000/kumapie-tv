package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.composed
import dev.chrisbanes.haze.blur.hazeBlur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.ui.Colors

/**
 * The "Glass" look (N9, user 2026-11-17): a near-black page, translucent white surfaces with a thin white border,
 * white pills, ice blue only for small accents (today, sense numbers, ticks). The TV keeps its own (`Colors` are vars
 * it sets itself); subtitle word colours are not touched.
 */
object Glass {
    val page = Color(0xFF07090D)
    val fill = Color.White.copy(alpha = 0.12f)
    val line = Color.White.copy(alpha = 0.18f)
    val accent = Color(0xFF9CC9FF)
    val dim = Color(0xFFA8A8A8)
    /** Opaque stand-in for [fill] over [page], for Material surfaces (sheets, dialogs, menus). */
    val solid = Color(0xFF1B1D22)
}

/**
 * The picture glass blurs (Haze, user 2026-11-17): a screen marks its picture `Modifier.hazeSource(state)` and provides
 * the state here around the glass drawn over it. None provided = plain translucent fill. Android 11 and older (the
 * Boox) get Haze's tinted fallback without blur. A video (SurfaceView) can't be captured: the player's glass stays plain.
 */
val LocalHaze = androidx.compose.runtime.staticCompositionLocalOf<dev.chrisbanes.haze.HazeState?> { null }

private val GlassBlur = dev.chrisbanes.haze.blur.HazeBlurStyle {
    blurRadius(24.dp)
    noiseFactor(0f)
    backgroundColor(Glass.page)
    colorEffects(listOf(dev.chrisbanes.haze.blur.HazeColorEffect.tint(Glass.fill)))
}

fun Modifier.glass(shape: Shape = RoundedCornerShape(18.dp)) = composed {
    val haze = LocalHaze.current
    clip(shape).then(if (haze == null) Modifier.background(Glass.fill)
        else Modifier.hazeBlur(dev.chrisbanes.haze.HazeInput.Sources(haze), GlassBlur))
        .border(1.dp, Glass.line, shape)
}

@Composable
fun MobileTheme(content: @Composable () -> Unit) {
    Colors.background = Glass.page
    Colors.surface = Glass.solid
    Colors.accent = Glass.accent
    Colors.pickedSoft = true
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color.White,
            onPrimary = Color.Black,
            primaryContainer = Color.White,
            onPrimaryContainer = Color.Black,
            secondary = Glass.accent,
            background = Glass.page,
            onBackground = Colors.text,
            surface = Glass.page,
            onSurface = Colors.text,
            surfaceContainer = Glass.solid,
            surfaceContainerHigh = Glass.solid,
            surfaceContainerHighest = Glass.solid,
            surfaceContainerLow = Glass.solid,
            onSurfaceVariant = Glass.dim,
            // Every stock chip, outlined button, text field and card in the Glass vocabulary: a picked chip is white
            // with black text, outlines are the thin white glass line.
            secondaryContainer = Color.White,
            onSecondaryContainer = Color.Black,
            outline = Glass.line,
            outlineVariant = Glass.line,
            surfaceVariant = Glass.solid,
        ),
        // Chips radius 18, cards and sheets 16-22 (Material's 8 and 12 read as boxes next to the pills).
        shapes = androidx.compose.material3.Shapes(
            extraSmall = RoundedCornerShape(12.dp),
            small = RoundedCornerShape(18.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(22.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}

/** A pill button: white with black text for the main action, glass for the rest. */
@Composable
fun Pill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, icon: ImageVector? = null) {
    val shape = RoundedCornerShape(24.dp)
    val ink = if (primary) Color.Black else Color.White
    Row(modifier.height(48.dp).then(if (primary) Modifier.clip(shape).background(Color.White) else Modifier.glass(shape))
        .clickable(onClick = onClick).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        icon?.let { Icon(it, null, tint = ink, modifier = Modifier.size(24.dp)) }
        Text(text, color = ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false))
    }
}

/** A small selectable chip (radius 18): white when picked. */
@Composable
fun GlassChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Text(text, color = if (selected) Color.Black else Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.then(if (selected) Modifier.clip(shape).background(Color.White) else Modifier.glass(shape))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp))
}

/** A big number with its label on glass (Home's stats). */
@Composable
fun StatChip(value: String, label: String, accent: Boolean = false, onClick: () -> Unit) =
    Column(Modifier.glass().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp)) {
        Text(value, color = if (accent) Glass.accent else Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Glass.dim, fontSize = 13.sp)
    }

/** The menu: a round 54 dp glass button, icon only; the current screen's is filled white with a black icon. */
@Composable
fun RoundButton(icon: ImageVector, label: String, selected: Boolean = false, badge: Int = 0, onClick: () -> Unit) {
    Box(Modifier.size(54.dp).then(if (selected) Modifier.clip(CircleShape).background(Color.White) else Modifier.glass(CircleShape))
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        BadgedBox(badge = { if (badge > 0) Badge { Text("$badge") } }) {
            Icon(icon, label, tint = if (selected) Color.Black else Color.White, modifier = Modifier.size(26.dp))
        }
    }
}

/** A thin progress bar (episode watched, show progress): white on a faint track. */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(4.dp).background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(2.dp))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(Color.White, RoundedCornerShape(2.dp)))
    }
}

/** "24 min", "1 h 5 min". */
fun minutes(seconds: Double): String = io.github.pedrubik2000.kumapie.data.hoursMinutes(seconds) // shared with the TV

/** Every bottom sheet: dark system bars under it (Material's sheet followed the system's light theme: a white bar). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
internal val DarkSheet = androidx.compose.material3.ModalBottomSheetProperties(isAppearanceLightStatusBars = false, isAppearanceLightNavigationBars = false)
