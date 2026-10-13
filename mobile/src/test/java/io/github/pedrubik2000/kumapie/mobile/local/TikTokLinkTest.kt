package io.github.pedrubik2000.kumapie.mobile.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TikTokLinkTest {
    @Test fun tiktokIsAReelKeyedByItsVideoId() {
        val link = "https://www.tiktok.com/@tagesschau/video/7695816255625874710"
        assertTrue(ProcessWorker.viaYtDlp(link) && !ProcessWorker.isYouTube(link))
        assertEquals(ProcessWorker.idFor(link), ProcessWorker.idFor("$link?is_from_webapp=1&sender_device=pc"))
        assertEquals("reels", LocalEpisodes.category(link, "de", null))
        assertEquals("youtube", LocalEpisodes.category("https://www.youtube.com/watch?v=jNQXAC9IVRw", "de", null))
    }
}
