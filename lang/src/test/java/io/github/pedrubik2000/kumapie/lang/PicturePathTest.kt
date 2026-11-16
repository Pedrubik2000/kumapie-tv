package io.github.pedrubik2000.kumapie.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [YomitanDictionaries.picturePath]: a picture dictionary's picture, not its EPWING gaiji (新世紀ビジュアル大辞典's いなご). */
class PicturePathTest {
    @Test fun pictureNotGaiji() {
        val raw = """{"type":"structured-content","content":[{"tag":"span","content":["【稲子・",{"tag":"img","path":"media/res/font/wa54b.png.webp",""" +
            """"data":{"class":"epwing-font-image"},"width":1,"height":1,"sizeUnits":"em"},"蝗】"]},{"tag":"div","content":[""" +
            """{"tag":"img","path":"media/res/image/17311x494.jpeg.webp","data":{"class":"epwing-display-image"}}]}]}"""
        assertEquals("media/res/image/17311x494.jpeg.webp", YomitanDictionaries.picturePath(raw))
    }

    @Test fun noPicture() = assertNull(YomitanDictionaries.picturePath("""["a locust"]"""))
}
