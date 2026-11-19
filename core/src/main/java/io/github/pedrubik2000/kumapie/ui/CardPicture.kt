package io.github.pedrubik2000.kumapie.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

/** A card's picture ([uri]: a file URI, the 480 px WebP of an own card), nothing when there is none. The TV's and the phone's Review. */
@Composable
fun CardPicture(uri: String, modifier: Modifier = Modifier) {
    if (uri.isEmpty()) return
    val bitmap = remember(uri) { runCatching { BitmapFactory.decodeFile(Uri.parse(uri).path) }.getOrNull() } ?: return
    // Its own proportions: a full-width picture fills the width (it stayed small on the tablet).
    Image(bitmap.asImageBitmap(), null, modifier.aspectRatio(bitmap.width.toFloat() / bitmap.height), contentScale = ContentScale.Fit)
}
