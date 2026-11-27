package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.R
import io.github.pedrubik2000.kumapie.data.Discovery
import io.github.pedrubik2000.kumapie.i18n.tr

internal val PAGE_TOP = 40.dp
internal val PAGE_BODY = PAGE_TOP + 32.dp

@Composable
internal fun PageHeader(overline: String, title: String? = null, meta: String? = null, modifier: Modifier = Modifier) =
    Column(modifier.fillMaxWidth().padding(end = 32.dp, top = PAGE_TOP)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(overline.uppercase(), color = Colors.dim, fontSize = 14.sp, letterSpacing = 3.sp, style = Arcade.label, modifier = Modifier.weight(1f))
            meta?.let { Text(it, color = Colors.dim, fontSize = 14.sp) }
        }
        title?.let { Title(it, fontSize = 34.sp, modifier = Modifier.padding(top = 8.dp)) }
    }

@Composable
internal fun EmptyState(icon: ImageVector, title: String, line: String? = null, button: String? = null, onButton: () -> Unit = {}, buttonModifier: Modifier = Modifier,
                        modifier: Modifier = Modifier) =
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(64.dp).background(Arcade.band, CircleShape).border(1.dp, Arcade.line, CircleShape), contentAlignment = Alignment.Center) {
                androidx.tv.material3.Icon(icon, null, Modifier.size(28.dp), tint = Colors.dim)
            }
            Text(title, color = Colors.dim, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            line?.let { Text(it, color = Colors.dim, fontSize = 15.sp) }
            if (button != null) Button(onClick = onButton, modifier = Modifier.padding(top = 4.dp).then(buttonModifier)) { Text(button) }
        }
    }

internal fun RailAt.label(): String = when (this) {
    RailAt.HOME -> tr("Home"); RailAt.LIBRARY -> tr("Library"); RailAt.DISCOVER -> tr("Discover")
    RailAt.REVIEW -> tr("Review"); RailAt.STATS -> tr("Stats"); RailAt.SETTINGS -> tr("Settings")
}

@Composable
internal fun TvPage(api: Discovery, at: RailAt, nav: RailNav, title: String? = null, meta: String? = null, lang: String? = "ja",
                    toContent: FocusRequester? = null, content: @Composable () -> Unit) {
    val rail = remember { mutableMapOf<String, FocusRequester>() }
    Box(Modifier.fillMaxSize().railGate()) {
        Column(Modifier.fillMaxSize().padding(start = RAIL).onBackKey { if (runCatching { rail[at.label()]?.requestFocus() }.getOrNull() != true) nav.onHome() }) {
            PageHeader(at.label(), title, meta)
            Spacer(Modifier.height(16.dp))
            PageBody(Modifier.weight(1f), content)
        }
        RailBackdrop()
        Image(painterResource(R.drawable.bear), "kumapie", Modifier.padding(start = 20.dp, top = 20.dp).size(64.dp))
        MainRail(api, lang, at, nav, rail, toContent)
        HintBar()
    }
}

private val FADE = 24.dp
private val SIDE = 24.dp
private val CLEAR = 72.dp

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private class PageScroll(val margin: Float) : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override val scrollAnimationSpec = TvScroll.scrollAnimationSpec
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val top = margin
        val bottom = containerSize - margin
        return when {
            size > bottom - top -> offset - top
            offset < top -> offset - top
            offset + size > bottom -> offset + size - bottom
            else -> 0f
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PageBody(modifier: Modifier, content: @Composable () -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val fade = with(density) { FADE.toPx() }
    val side = with(density) { SIDE.toPx() }
    val info = remember { PageFade() }
    val spec = remember(density) { PageScroll(with(density) { CLEAR.toPx() }) }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides spec, LocalPageFade provides info) {
        Box(modifier.fillMaxWidth().padding(bottom = rememberHintRoom()).drawWithContent {
            clipRect(-side, 0f, size.width + side, size.height) { this@drawWithContent.drawContent() }
            if (info.top) drawRect(Brush.verticalGradient(listOf(Arcade.background, Color.Transparent), 0f, fade), size = Size(size.width, fade))
            if (info.bottom) drawRect(Brush.verticalGradient(listOf(Color.Transparent, Arcade.background), size.height - fade, size.height), Offset(0f, size.height - fade), Size(size.width, fade))
        }) { content() }
    }
}

internal class PageFade {
    var top by androidx.compose.runtime.mutableStateOf(false)
    var bottom by androidx.compose.runtime.mutableStateOf(false)
}

internal val LocalPageFade = androidx.compose.runtime.staticCompositionLocalOf { PageFade() }

@Composable
internal fun ReportsScroll(canBack: Boolean, canForward: Boolean) {
    val info = LocalPageFade.current
    androidx.compose.runtime.SideEffect { info.top = canBack; info.bottom = canForward }
}
