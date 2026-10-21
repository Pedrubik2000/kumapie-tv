package androidx.compose.ui.text.googlefonts

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/** Stand-in for Android's Google Fonts (core's JapaneseFonts): the PC keeps the system font, so it is never asked. */
class GoogleFont(val name: String) {
    class Provider(val providerAuthority: String, val providerPackage: String, val certificates: Int)
}

fun Font(googleFont: GoogleFont, fontProvider: GoogleFont.Provider, weight: FontWeight = FontWeight.Normal, style: FontStyle = FontStyle.Normal): Font =
    error("Google Fonts (${googleFont.name}) are not on the PC")
