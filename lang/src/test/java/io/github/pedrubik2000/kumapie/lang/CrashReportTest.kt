package io.github.pedrubik2000.kumapie.lang

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class CrashReportTest {
    private val env = CrashEnv("Pixel 8a-abc123", "Google Pixel 8a", "35 (15)", "arm64-v8a", "mobile", "1.2.3")
    private val javaTrace = "java.lang.IllegalStateException: boom\n" + (1..200).joinToString("\n") { "\tat pkg.Class$it.run(File.kt:$it)" }

    @Test fun theJsonHasEveryFieldTheServerExpects() {
        val r = CrashReports.build("java", 1234L, "", javaTrace)
        val j = JSONObject(CrashReports.json(r, env))
        assertEquals(setOf("device", "model", "android", "abi", "app", "version", "kind", "time", "summary", "trace"), j.keys().asSequence().toSet())
        assertEquals("Pixel 8a-abc123", j.getString("device"))
        assertEquals("Google Pixel 8a", j.getString("model"))
        assertEquals("mobile", j.getString("app"))
        assertEquals("java", j.getString("kind"))
        assertEquals(1234L, j.getLong("time"))
        assertEquals("java.lang.IllegalStateException: boom", j.getString("summary"))
    }

    @Test fun summariesByKind() {
        assertEquals("Abort message: 'bad'", CrashReports.summary("native", "backtrace:\nAbort message: 'bad'\nsignal 6", ""))
        assertEquals("signal 11 (SIGSEGV)", CrashReports.summary("native", "x\nsignal 11 (SIGSEGV)", ""))
        assertEquals("Input dispatching timed out", CrashReports.summary("anr", "----- pid 1 -----", "Input dispatching timed out"))
        assertEquals("anr", CrashReports.summary("anr", "", ""))
    }

    @Test fun theTraceIsCappedAndKeysAndUrlsAreScrubbed() {
        val dirty = "java.io.IOException: GET https://api.real-debrid.com/rest/1.0/x?auth_token=SECRET1 failed\n" +
            "addon https://torrentio.strem.fun/realdebrid=ABCDEF123/manifest.json\nrealdebrid=ZZZ999 apikey: KEY777 Authorization: Bearer tok.en-1\n" + "x".repeat(300_000)
        val r = CrashReports.build("java", 1L, "", dirty)
        assertTrue(r.trace.length in 99_000..CrashReports.TRACE_MAX)
        listOf("SECRET1", "ABCDEF123", "ZZZ999", "KEY777", "tok.en-1", "manifest.json").forEach { assertFalse(it, it in r.trace) }
        assertTrue("https://api.real-debrid.com/…" in r.trace)
        assertTrue("realdebrid=***" in r.trace)
        assertFalse("SECRET1" in r.summary)
    }

    @Test fun escapedJsonAndCookiesAreScrubbed() {
        val s = CrashReports.scrub("""body={\"token\":\"ESC1\",\"session\":\"ESC2\"} Cookie: ESC3 auth=ESC4 sessionId=ESC5""")
        listOf("ESC1", "ESC2", "ESC3", "ESC4", "ESC5").forEach { assertFalse(it, it in s) }
    }

    @Test fun userinfoInAUrlIsDropped() {
        val s = CrashReports.scrub("GET https://user:p4ss@host.example:8443/a/b and ftp://tok3n@files.example/x")
        listOf("user", "p4ss", "tok3n", "/a/b").forEach { assertFalse(it, it in s) }
        assertTrue("https://host.example:8443/…" in s && "ftp://files.example/…" in s)
    }

    @Test fun everyKeyShapeIsScrubbed() {
        val s = CrashReports.scrub("""{"access_token":"AAA1","jimaku_key":"BBB2"} rd_token=CCC3 accessToken: DDD4 api-key=EEE5 PASSWORD=FFF6 client_secret = GGG7 Authorization: Bearer HHH8""")
        listOf("AAA1", "BBB2", "CCC3", "DDD4", "EEE5", "FFF6", "GGG7", "HHH8").forEach { assertFalse(it, it in s) }
        assertTrue("access_token" in s)
    }

    @Test fun everySchemeIsCutToItsHost() {
        val s = CrashReports.scrub("a magnet:?xt=urn:btih:SECRETHASH&dn=Show file:///sdcard/Movies/Private.mkv content://media/external/1234 ftp://h.example/x/y?k=1 mailto:me@x.com")
        listOf("SECRETHASH", "Private.mkv", "1234", "x/y", "me@x.com").forEach { assertFalse(it, it in s) }
        assertTrue("ftp://h.example/…" in s && "magnet:…" in s)
    }

    @Test fun stackFramesAndPlainMessagesSurviveTheScrub() {
        val t = "java.lang.IllegalStateException: boom: x\n\tat pkg.Class.run(File.kt:12)"
        assertEquals(t, CrashReports.scrub(t))
    }

    @Test fun aLongRunOfOneCharacterIsScrubbedFast() {
        val t0 = System.nanoTime()
        CrashReports.scrub("x".repeat(CrashReports.TRACE_MAX))
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
    }

    @Test fun aNativeReportKeepsOnlyTheAbortSignalAndFrames() {
        val raw = "Abort message: 'bad thing'\nsignal 11 (SIGSEGV), code 1\n    #00 pc 0000000000012345  /system/lib64/libc.so (abort+168)\n" +
            "memory near x0:\n0000007ff0a1b2c0 7365637265743d70 6173737764313233\nsecretpassword1234567890\nopen files:\n    fd 5: /data/user/0/x/secret.db"
        val r = CrashReports.build("native", 1L, "", raw)
        assertEquals("Abort message: 'bad thing'\nsignal 11 (SIGSEGV), code 1\n#00 pc 0000000000012345  /system/lib64/libc.so (abort+168)", r.trace)
        assertEquals("Abort message: 'bad thing'", r.summary)
    }

    @Test fun anEscapeIsNeverCutInTwo() {
        val r = CrashReport("java", 1L, "é".repeat(300), "")
        for (cap in 500..600) {
            val u = CrashReports.issueUrl(r, env, cap)
            assertTrue(Regex("%(?![0-9A-F]{2})").find(u) == null)
            assertTrue(u.length <= cap)
        }
    }

    @Test fun aNativeStreamOfBytesBecomesItsReadableStrings() {
        val bytes = byteArrayOf(0, 1, 2, 8) + "Abort message: 'oops'".toByteArray() + byteArrayOf(0, 3) + "libfoo.so".toByteArray() + byteArrayOf(0, 0, 5)
        val text = CrashReports.readable(bytes)
        assertTrue("Abort message: 'oops'" in text)
        assertTrue("libfoo.so" in text)
        assertEquals("plain text\nmore", CrashReports.readable("plain text\nmore".toByteArray()))
    }

    @Test fun theIssueUrlStaysUnderTheCapAndKeepsHeadAndTail() {
        val r = CrashReports.build("java", 1L, "", javaTrace)
        val u = CrashReports.issueUrl(r, env)
        assertTrue(u.length <= CrashReports.ISSUE_CAP)
        assertTrue(u.startsWith("https://github.com/Pedrubik2000/kumapie-tv/issues/new?title="))
        val body = URLDecoder.decode(u.substringAfter("&body="), "UTF-8")
        assertTrue("Class1.run" in body)
        assertTrue("Class200.run" in body)
        assertFalse("Class100.run" in body)
        assertTrue("Google Pixel 8a" in body && "1.2.3" in body)
    }

    @Test fun aSmallerCapTrimsHarderAndEncodesSpecialCharacters() {
        val r = CrashReports.build("java", 1L, "", "java.lang.Error: a b&c=d #1 é\n" + javaTrace)
        val u = CrashReports.issueUrl(r, env, CrashReports.QR_CAP)
        assertTrue(u.length <= CrashReports.QR_CAP)
        assertEquals(1, u.count { it == '?' })
        assertFalse(" " in u || "&c=d" in u || "é" in u || "#" in u)
        assertEquals("Crash: java.lang.Error: a b&c=d #1 é", URLDecoder.decode(u.substringAfter("title=").substringBefore("&body="), "UTF-8"))
    }

    @Test fun aHugeSummaryStillFits() {
        val r = CrashReport("native", 1L, "é".repeat(300), "")
        assertTrue(CrashReports.issueUrl(r, env, CrashReports.QR_CAP).length <= CrashReports.QR_CAP)
    }

    @Test fun eachCrashIsOfferedOnceAndOnlyTheNewestRecent() {
        val now = 10_000_000_000L
        val a = CrashExit(now - 5_000, "java")
        val b = CrashExit(now - 1_000, "native")
        assertEquals(b, CrashReports.newest(listOf(a, b), 0, now))
        assertEquals(b, CrashReports.newest(listOf(a, b), now - 5_000, now))
        assertNull("offered already", CrashReports.newest(listOf(a, b), now - 1_000, now))
        assertNull("none", CrashReports.newest(emptyList(), 0, now))
        assertNull("a week old", CrashReports.newest(listOf(CrashExit(now - CrashReports.MAX_AGE_MS - 1, "anr")), 0, now))
    }
}
