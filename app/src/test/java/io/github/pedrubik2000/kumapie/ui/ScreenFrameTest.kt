package io.github.pedrubik2000.kumapie.ui

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** [flat]: an empty video surface (one colour) is no card picture; a real frame is (Chromecast, 2026-11-19). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenFrameTest {
    private fun bitmap(color: (Int, Int) -> Int) = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888).apply {
        for (x in 0 until width) for (y in 0 until height) setPixel(x, y, color(x, y))
    }

    @Test fun anEmptySurfaceIsFlat() = assertTrue(flat(bitmap { _, _ -> Color.rgb(0x11, 0x11, 0x11) }))

    @Test fun aFrameIsNot() = assertFalse(flat(bitmap { x, y -> Color.rgb(x, y * 2, 0x40) }))
}
