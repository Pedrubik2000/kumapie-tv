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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
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

// ponytail: translucent fill + border, no backdrop blur (Compose's blur can't see behind; needs Haze or RenderEffect, API 31+).
fun Modifier.glass(shape: Shape = RoundedCornerShape(18.dp)) = clip(shape).background(Glass.fill).border(1.dp, Glass.line, shape)

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
        Text(text, color = ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
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
fun minutes(seconds: Double): String {
    val m = (seconds / 60).toInt()
    return when {
        m < 60 -> "$m min"
        m % 60 == 0 -> "${m / 60} h"
        else -> "${m / 60} h ${m % 60} min"
    }
}
