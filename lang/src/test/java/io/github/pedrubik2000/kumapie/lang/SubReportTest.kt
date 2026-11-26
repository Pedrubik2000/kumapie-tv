package io.github.pedrubik2000.kumapie.lang

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class SubReportTest {
    private val env = CrashEnv("Pixel-abc", "Google Pixel 8a", "35 (15)", "arm64-v8a", "mobile", "1.2.3")
    private val report = SubReport("Frieren", "kitsu:46474", 154587, 5, "jimaku", SubReports.fileName("C:\\Users\\pedro\\Downloads\\Frieren.S01E05.srt"),
        -2.5, 0.5, "alone_off_kitsu:46474:5|f", 71.3, ReportCue(70.0, 72.5, "こんにちは"),
        listOf(ReportCue(73.0, 75.0, "次"), ReportCue(76.0, 77.0, "次の次")),
        SubReports.mediaName("https://host.example/dl/Frieren%20E05.mkv?token=SECRET123"))

    @Test fun theJsonHasEveryFieldAndNoSecret() {
        val text = SubReports.json(report, env)
        val j = JSONObject(text)
        assertEquals(setOf("title", "title_id", "anilist", "episode", "source", "video_file", "shift", "nudge", "saved_key",
            "position", "cue_now", "cues_next", "media", "device", "model", "app", "version"), j.keys().asSequence().toSet())
        assertEquals(154587, j.getInt("anilist"))
        assertEquals(0.5, j.getDouble("nudge"), 0.0)
        assertEquals(2, j.getJSONArray("cues_next").length())
        assertEquals(70.0, j.getJSONObject("cue_now").getDouble("start"), 0.0)
        assertEquals("Frieren.S01E05.srt", j.getString("video_file"))
        assertEquals("Frieren%20E05.mkv", j.getString("media"))
        assertFalse(text.contains("SECRET123"))
        assertFalse(text.contains("Users"))
    }

    @Test fun aMediaUrlWithoutAFileNameIsLeftOut() {
        assertEquals("", SubReports.mediaName("https://host.example/stream/SECRETTOKEN?x=1"))
        assertEquals("a.mkv", SubReports.mediaName("https://host.example/a.mkv"))
    }

    @Test fun longJapaneseNamesStillFitTheQr() {
        val jp = "葬送のフリーレン".repeat(40)
        val r = report.copy(title = jp, videoFile = jp, media = jp, savedKey = jp, now = ReportCue(1.0, 2.0, jp), next = listOf(ReportCue(3.0, 4.0, jp)))
        for (cap in listOf(CrashReports.QR_CAP, 600)) {
            val u = SubReports.issueUrl(r, env, cap)
            assertTrue("$cap: ${u.length}", u.length <= cap)
            assertTrue(u.startsWith(CrashReports.ISSUES))
        }
    }

    @Test fun theIssueIsTitledAndFitsTheQr() {
        val u = SubReports.issueUrl(report, env, CrashReports.QR_CAP)
        assertTrue(u.length <= CrashReports.QR_CAP)
        assertEquals("Subtitle timing: Frieren ep 5", URLDecoder.decode(u.substringAfter("title=").substringBefore('&'), "UTF-8"))
    }
}
