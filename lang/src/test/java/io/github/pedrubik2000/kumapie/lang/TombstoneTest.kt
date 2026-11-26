package io.github.pedrubik2000.kumapie.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TombstoneTest {
    private val real = requireNotNull(javaClass.getResourceAsStream("/tombstone-sigabrt.pb")) { "fixture" }.use { it.readBytes() }

    private fun abort(msg: String) = byteArrayOf(0x72, msg.length.toByte()) + msg.toByteArray()

    @Test fun aRealTombstoneGivesTheSignalAndEveryThreadsFrames() {
        val t = requireNotNull(Tombstone.parse(real))
        assertTrue("signal 6 (SIGABRT), SI_USER" in t.all)
        assertTrue("tid 2766 0.kumapie.debug" in t.all)
        assertTrue("tid 2822 DefaultDispatch" in t.all)
        assertTrue("tid 2805 WM.task-2" in t.all)
        assertTrue(Regex("#00 pc [0-9a-f]{16}  /apex/com.android.runtime/lib64/bionic/libc.so \\(__epoll_pwait\\+10\\)").containsMatchIn(t.all))
        assertTrue("android::Looper::pollOnce(int, int*, int*, void**)+413" in t.all)
    }

    @Test fun thePublicTextHasOnlyTheCrashingThread() {
        val t = requireNotNull(Tombstone.parse(real))
        assertTrue("tid 2766 0.kumapie.debug" in t.crashing)
        assertTrue("signal 6 (SIGABRT)" in t.crashing)
        assertFalse("DefaultDispatch" in t.crashing || "WM.task-2" in t.crashing)
        assertTrue("__epoll_pwait" in t.crashing)
    }

    @Test fun registersMemoryLogsFdsAndTheCommandLineAreNeverDecoded() {
        val t = requireNotNull(Tombstone.parse(real))
        assertFalse("DECOY" in t.all || "DECOY" in t.crashing)
        val r = CrashReports.fromBytes("native", 1L, "", real)
        assertFalse("DECOY" in r.trace || "DECOY" in r.publicTrace)
    }

    @Test fun theAbortMessageComesFirstAndIsScrubbedInBothReports() {
        val r = CrashReports.fromBytes("native", 5L, "", abort("bad state, token=SECRETXYZ at https://h.example/p?k=1") + real)
        assertTrue(r.summary.startsWith("Abort message: 'bad state, token=***"))
        listOf(r.trace, r.publicTrace).forEach {
            assertTrue(it.startsWith("Abort message: 'bad state, token=***"))
            assertFalse("SECRETXYZ" in it || "/p?k=1" in it)
        }
        assertTrue("WM.task-2" in r.trace)
        assertFalse("WM.task-2" in r.publicTrace)
    }

    @Test fun theIssueUsesThePublicTraceAndThePcGetsTheFullOne() {
        val env = CrashEnv("d", "m", "36", "x86_64", "tv", "1")
        val r = CrashReports.fromBytes("native", 5L, "", real)
        assertTrue("WM.task-2" in CrashReports.json(r, env))
        val issue = java.net.URLDecoder.decode(CrashReports.issueUrl(r, env), "UTF-8")
        assertTrue("__epoll_pwait" in issue)
        assertFalse("WM.task-2" in issue)
    }

    @Test fun aTruncatedTombstoneStillGivesWhatCameBefore() {
        val t = requireNotNull(Tombstone.parse(real.copyOf(real.size - 300)))
        assertTrue("signal 6 (SIGABRT)" in t.all)
    }

    @Test(timeout = 5_000) fun aNegativeOrHugeLengthEndsTheWalkInsteadOfLoopingForever() {
        val minusOne = ByteArray(9) { 0xff.toByte() } + byteArrayOf(0x01)
        val huge = byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x7f)
        val t = requireNotNull(Tombstone.parse(real + byteArrayOf(0x7a) + minusOne))
        assertTrue("signal 6 (SIGABRT)" in t.all)
        assertTrue("tid 2766 0.kumapie.debug" in Tombstone.parse(real + byteArrayOf(0x7a) + huge + byteArrayOf(1, 2, 3))?.all.orEmpty())
        assertNull(Tombstone.parse(byteArrayOf(0x7a) + minusOne))
    }

    @Test fun anInstallPathInAFrameLosesItsRandomParts() {
        val s = CrashReports.scrub("#03 pc 0000000000012345  /data/app/~~Ab12Cd34==/io.github.pedrubik2000.kumapie-Xy9Z==/base.apk (offset 0x1000)")
        assertEquals("#03 pc 0000000000012345  /data/app/…/base.apk (offset 0x1000)", s)
    }

    @Test fun textThatIsNotAProtobufIsLeftToTheTextPath() {
        assertNull(Tombstone.parse("java.lang.Error: x\n\tat a.b(C.kt:1)".toByteArray()))
        assertEquals("native", CrashReports.fromBytes("native", 1L, "", "java.lang.Error: x".toByteArray()).summary)
    }
}
