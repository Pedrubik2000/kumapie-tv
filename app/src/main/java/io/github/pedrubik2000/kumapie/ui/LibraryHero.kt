package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.Discovery
import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.i18n.tr

internal val HERO_BOTTOM = 212.dp

@Stable
internal class LibFocus {
    var id by mutableStateOf<String?>(null)
}

@Composable
internal fun LibraryHero(items: List<Mine>, focus: LibFocus, api: Discovery, levels: Levels?) {
    val m = items.firstOrNull { it.kitsuId == focus.id } ?: items.firstOrNull()
    val target = remember(m) { m?.let(::ListTarget) }
    Box(Modifier.fillMaxWidth().height(300.dp)) {
        HeroBackdrop(heroPicture(target, api), { FADE_SHORT }, Modifier.matchParentSize())
        if (m != null) Column(Modifier.padding(start = CONTENT_START, top = 64.dp, end = 28.dp).fillMaxWidth()) {
            Eyebrow(libraryStatus(m))
            Text(m.shown(), color = Color.White, fontSize = 36.sp, lineHeight = 42.sp, style = Arcade.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(factLine(ListTarget(m).facts(), m.average.takeIf { it > 0 }?.let { tr("AniList %d", it) }), color = WHITE_DIM, fontSize = 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            levels?.of(m.kitsuId)?.let { Box(Modifier.padding(top = 8.dp)) { DifficultyLine(it) } }
        }
    }
}
