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

/** Where every top-level page's header sits: the title's top, and where the page's body starts below it. */
internal val PAGE_TOP = 40.dp
internal val PAGE_BODY = PAGE_TOP + 32.dp

/**
 * The one page header (Library, Discover, Review, Stats, Settings): a caps overline with the page's name at Home's content edge, [meta] on the
 * right of the same line, and [title] under it when the page has one (Stats: the week).
 */
@Composable
internal fun PageHeader(overline: String, title: String? = null, meta: String? = null, modifier: Modifier = Modifier) =
    Column(modifier.fillMaxWidth().padding(end = 32.dp, top = PAGE_TOP)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(overline.uppercase(), color = Colors.dim, fontSize = 14.sp, letterSpacing = 3.sp, style = Arcade.label, modifier = Modifier.weight(1f))
            meta?.let { Text(it, color = Colors.dim, fontSize = 14.sp) }
        }
        title?.let { Title(it, fontSize = 34.sp, modifier = Modifier.padding(top = 8.dp)) }
    }

/** The one empty state: a circle icon, a title, one line, and at most one outlined pill. */
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

/**
 * A top-level page that is not Library/Discover/Home: the side rail, the page header, and the body at Home's content edge.
 * Back in the body goes to the rail (on the rail, Back leaves for Home as everywhere); Left at the body's edge reaches it too ([railGate]).
 * [toContent]: where Right from the rail goes (the body's first item).
 */
@Composable
internal fun TvPage(api: Discovery, at: RailAt, nav: RailNav, title: String? = null, meta: String? = null, lang: String? = "ja",
                    toContent: FocusRequester? = null, content: @Composable () -> Unit) {
    val rail = remember { mutableMapOf<String, FocusRequester>() }
    Box(Modifier.fillMaxSize().railGate()) {
        Column(Modifier.fillMaxSize().padding(start = RAIL).onBackKey { if (runCatching { rail[at.label()]?.requestFocus() }.getOrNull() != true) nav.onHome() }) {
            PageHeader(at.label(), title, meta)
            Spacer(Modifier.height(16.dp)) // a clear gap between the header and the body
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

/** Keeps the focused item at least [margin] px inside the body's top and bottom, so it is clear of the fades and the preview line under it shows. */
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

/**
 * The page body under the header. The hint bar never covers it; its top and bottom fade into the ground rather than cut hard, its sides have
 * room for a grown button's outline, and focus scrolling keeps items clear of both fades.
 */
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

/** Whether the body is scrolled away from its top, and whether there is more below: the fades show only then (never over content at rest). */
internal class PageFade {
    var top by androidx.compose.runtime.mutableStateOf(false)
    var bottom by androidx.compose.runtime.mutableStateOf(false)
}

internal val LocalPageFade = androidx.compose.runtime.staticCompositionLocalOf { PageFade() }

/** A scrolling body reports its position to the page's fades. */
@Composable
internal fun ReportsScroll(canBack: Boolean, canForward: Boolean) {
    val info = LocalPageFade.current
    androidx.compose.runtime.SideEffect { info.top = canBack; info.bottom = canForward }
}
