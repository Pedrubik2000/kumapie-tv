package io.github.pedrubik2000.kumapie.lang

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import io.github.pedrubik2000.kumapie.data.Settings
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicBoolean

object CrashCollector {
    private val installed = AtomicBoolean(false)
    private const val LAST = "crash_offered_until"
    private const val SAME_MS = 60_000L

    private fun file(context: Context) = File(context.noBackupFilesDir, "last-crash.txt")

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val dir = file(context.applicationContext)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { dir.writeText("${System.currentTimeMillis()}\n" + StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString().take(CrashReports.TRACE_MAX)) }
            previous?.uncaughtException(t, e)
        }
    }

    fun env(context: Context, app: String, version: String) = CrashEnv(Progress.of(context).device, "${Build.MANUFACTURER} ${Build.MODEL}",
        "${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})", Build.SUPPORTED_ABIS.firstOrNull().orEmpty(), app, version)

    private fun kindOf(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "java"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native"
        ApplicationExitInfo.REASON_ANR -> "anr"
        else -> null
    }

    private fun exits(context: Context): List<ApplicationExitInfo> =
        if (Build.VERSION.SDK_INT < 30) emptyList()
        else runCatching { context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 0) }
            .getOrDefault(emptyList()).filter { kindOf(it.reason) != null }

    private fun savedCrash(context: Context): Pair<Long, String>? = runCatching {
        val text = file(context).readText()
        text.substringBefore('\n').toLong() to text.substringAfter('\n')
    }.getOrNull()

    fun pending(context: Context, now: Long = System.currentTimeMillis()): CrashReport? {
        val ctx = context.applicationContext
        val infos = exits(ctx)
        val saved = savedCrash(ctx)
        val all = infos.mapNotNull { i -> kindOf(i.reason)?.let { CrashExit(i.timestamp, it) } } + listOfNotNull(saved?.let { CrashExit(it.first, "java") })
        val pick = CrashReports.newest(all, Settings(ctx).prefs.getLong(LAST, 0), now) ?: return null
        val info = infos.firstOrNull { it.timestamp == pick.time && kindOf(it.reason) == pick.kind }
        val own = saved?.takeIf { pick.kind == "java" && kotlin.math.abs(it.first - pick.time) < SAME_MS }?.second
        val raw = own ?: info?.let { runCatching { CrashReports.readable(it.traceInputStream?.use(::readCapped) ?: ByteArray(0)) }.getOrNull() }.orEmpty()
        return CrashReports.build(pick.kind, pick.time, info?.description.orEmpty(), raw)
    }

    fun mark(context: Context) {
        Settings(context).prefs.edit().putLong(LAST, System.currentTimeMillis()).apply()
        file(context.applicationContext).delete()
    }

    suspend fun send(context: Context, report: CrashReport, env: CrashEnv, qrCap: Int = 0): Delivery =
        ReportSender.deliver(context, "/api/crash", CrashReports.json(report, env), qrCap) { cap -> CrashReports.issueUrl(report, env, cap) }
}

private fun readCapped(s: java.io.InputStream, max: Int = 200_000): ByteArray {
    val buf = ByteArray(max)
    var n = 0
    while (n < max) {
        val r = s.read(buf, n, max - n)
        if (r < 0) break
        n += r
    }
    return buf.copyOf(n)
}
