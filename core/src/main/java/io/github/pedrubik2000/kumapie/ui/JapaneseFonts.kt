package io.github.pedrubik2000.kumapie.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import io.github.pedrubik2000.kumapie.core.R

/**
 * Fonts for Japanese subtitles (user, 2026-10-16), from Google Fonts: Play services downloads one the first time it is
 * drawn and keeps it, so the APK stays small and it works offline after. Until it is there, the system font shows.
 */
object JapaneseFonts {
    /** "" = the system's font; then plain gothic, rounded, mincho, textbook handwriting, playful, pixel. */
    val NAMES = listOf(
        "", "Noto Sans JP", "Zen Kaku Gothic New", "M PLUS Rounded 1c", "Zen Maru Gothic", "Kosugi Maru",
        "Noto Serif JP", "Shippori Mincho", "Sawarabi Mincho", "Klee One", "Kiwi Maru", "Yomogi",
        "Hachi Maru Pop", "Dela Gothic One", "DotGothic16",
    )

    /** A line to see a font by. */
    const val SAMPLE = "日本語の字幕：がんばろう！"

    private val provider = GoogleFont.Provider(
        providerAuthority = "com.google.android.gms.fonts",
        providerPackage = "com.google.android.gms",
        certificates = R.array.com_google_android_gms_fonts_certs,
    )
    private val families = HashMap<String, FontFamily>()

    /** [name]'s family (regular and bold), null for the system's. */
    fun family(name: String): FontFamily? = name.takeIf { it.isNotEmpty() }?.let {
        families.getOrPut(it) {
            FontFamily(Font(GoogleFont(it), provider), Font(GoogleFont(it), provider, FontWeight.Bold))
        }
    }

    /** What Settings shows for [name]. */
    fun label(name: String): String = name.ifEmpty { io.github.pedrubik2000.kumapie.i18n.tr("System font") }
}
