package io.github.pedrubik2000.kumapie.update

import io.github.pedrubik2000.kumapie.i18n.tr
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from the GitHub repo's releases: every tag vX.Y.Z has the signed APKs (TV and phone/tablet)
 * built by .github/workflows/release.yml; each app takes the one whose name starts with its [asset] prefix.
 * Android always asks before installing; the first time it also asks to allow this app to install apps
 * ("unknown sources").
 */
object Updater {
    /** Set by each app when it starts: "owner/repo", the app's version name and its APK's name prefix. */
    var repo = ""
    var version = ""
    var asset = ""

    data class Release(val version: String, val apkUrl: String, val notes: String)

    /** The latest release if it is newer than this app, else null. */
    suspend fun newer(): Release? {
        val latest = latest() ?: return null
        return if (isNewer(latest.version, version)) latest else null
    }

    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val conn = URL("https://api.github.com/repos/$repo/releases/latest")
            .openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 8_000
        conn.readTimeout = 15_000
        try {
            if (conn.responseCode == 404) return@withContext null // no release yet
            if (conn.responseCode != 200) throw IOException(tr("GitHub answered %d", conn.responseCode))
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").let { n -> n.startsWith(asset) && n.endsWith(".apk") } } ?: return@withContext null
            Release(json.getString("tag_name").removePrefix("v"), apk.getString("browser_download_url"),
                json.optString("body"))
        } finally {
            conn.disconnect()
        }
    }

    /** "1.10.0" > "1.9.3"; a "-debug" suffix is ignored. */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val r = parts(remote)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** Downloads the APK into the cache, reporting progress 0..1. */
    suspend fun download(context: Context, release: Release, progress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "kumapie-${release.version}.apk")
            var url = URL(release.apkUrl)
            var conn: HttpURLConnection
            while (true) { // GitHub redirects to its file storage
                conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 10_000
                conn.readTimeout = 30_000
                if (conn.responseCode in 300..399) {
                    url = URL(url, conn.getHeaderField("Location"))
                    conn.disconnect()
                } else break
            }
            try {
                if (conn.responseCode != 200) throw IOException(tr("download failed: HTTP %d", conn.responseCode))
                val total = conn.contentLengthLong.takeIf { it > 0 }
                conn.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total != null) progress(done.toFloat() / total)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            out
        }

    /** True when the app may install APKs; otherwise opens the system page to allow it and returns false. */
    fun canInstall(context: Context): Boolean {
        if (context.packageManager.canRequestPackageInstalls()) return true
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
        return false
    }

    /** Hands the APK to the system installer; InstallReceiver shows Android's confirmation. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("kumapie.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallReceiver::class.java)
            val pending = PendingIntent.getBroadcast(context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
    }
}
