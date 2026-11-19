package io.github.pedrubik2000.kumapie.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap

/** A card's picture ([uri]: a file URI, the 480 px WebP of an own card), nothing when there is none. The TV's and the phone's Review. */
@Composable
fun CardPicture(uri: String, modifier: Modifier = Modifier) {
    if (uri.isEmpty()) return
    val bitmap = remember(uri) { runCatching { BitmapFactory.decodeFile(Uri.parse(uri).path) }.getOrNull() } ?: return
    Image(bitmap.asImageBitmap(), null, modifier)
}
