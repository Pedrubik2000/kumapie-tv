package io.github.pedrubik2000.kumapie.lang

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.pedrubik2000.kumapie.data.Settings
import java.io.File

/**
 * The pictures and line audio of kumapie's own cards (Pedro, 2026-11-19): a frame of the line as WebP 480 px wide and
 * the line's audio as Opus 16 kbps mono, about 25 KB a card; no video. Named by the line (show, start-end), so cards
 * from one line share them; a card moved to another line ([Miner.recontext]) has its old files deleted ([sweep]) when unused.
 * Kept on this device, or on a USB drive ([Settings] "card_media_usb": the Chromecast's; the bedroom TV has none),
 * in the app's own folder there: no permission needed. Only new and moved cards get them; the PC's copies are text.
 */
object CardMedia {
    const val USB = "card_media_usb"
    const val WIDTH = 480
    const val AUDIO_BITRATE = 16_000

    /** Where card media go: the USB drive when chosen and plugged in, else this device. */
    fun dir(context: Context): File {
        val usb = Settings(context).prefs.getBoolean(USB, false)
        val base = if (usb) usbDir(context) ?: context.filesDir else context.filesDir
        return File(base, "card-media").apply { mkdirs() }
    }

    /** Card media to the USB drive ([on]) or back to this device, the files already there moved along. */
    fun setUsb(context: Context, on: Boolean) {
        wantUsb = on
        synchronized(usbLock) { move(context, wantUsb) } // one move at a time, and each one reads the latest wish: the last choice wins whatever order the jobs get the lock
    }

    @Volatile private var wantUsb = false

    private fun move(context: Context, on: Boolean) {
        val from = dir(context)
        Settings(context).prefs.edit().putBoolean(USB, on).apply()
        val to = dir(context)
        if (from == to) return
        from.listFiles()?.forEach { f -> runCatching { f.copyTo(File(to, f.name), overwrite = true); f.delete() } }
    }

    private val usbLock = Any()

    /** The app's folder on a removable drive (a USB stick), null when none is plugged in. */
    fun usbDir(context: Context): File? = context.getExternalFilesDirs(null).drop(1)
        .firstOrNull { it != null && android.os.Environment.isExternalStorageRemovable(it) && it.canWrite() }

    /** [file] kept as [name] (its extension added); answers the name a card field uses. */
    fun put(context: Context, file: File, name: String): String {
        val n = name.replace(Regex("[^\\w.-]+"), "_") + "." + file.extension
        file.copyTo(File(dir(context), n), overwrite = true)
        return n
    }

    /** A card field's media file, null when it isn't here (made on another device, or deleted). */
    fun file(context: Context, name: String): File? = File(dir(context), name).takeIf { name.isNotEmpty() && it.exists() }

    /** [picture] at [WIDTH] px wide, WebP: what a card keeps (a TV screen grab is 1920 px). Null when it can't be read. */
    fun small(picture: File, out: File): File? = runCatching {
        val full = BitmapFactory.decodeFile(picture.path) ?: return null
        val scaled = if (full.width <= WIDTH) full else Bitmap.createScaledBitmap(full, WIDTH, full.height * WIDTH / full.width, true)
        @Suppress("DEPRECATION")
        val webp = if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        out.outputStream().use { scaled.compress(webp, 60, it) }
        out
    }.getOrNull()

    /** The audio and picture names in a card's fields ("[audio:x.ogg]", `<img src="x.webp">`). */
    fun names(fields: Collection<String>): Pair<String?, String?> {
        val all = fields.joinToString(" ")
        return AUDIO.find(all)?.groupValues?.get(1) to IMG.find(all)?.groupValues?.get(1)
    }

    /** Of [old] (a moved card's former files), deletes the ones no card names any more ([used]); nothing else. */
    fun sweep(context: Context, old: Set<String>, used: Set<String>) {
        for (n in old - used) File(dir(context), n).delete()
    }

    val AUDIO = Regex("""\[audio:([^\]]+)\]""")
    val IMG = Regex("""<img src="([^"]+)">""")
}
