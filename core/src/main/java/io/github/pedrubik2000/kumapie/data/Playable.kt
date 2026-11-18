package io.github.pedrubik2000.kumapie.data

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList

/**
 * Which stream files this device can't decode, from the file's name (a debrid link says nothing else before it plays):
 * `x265 10bit`, `AV1`, `Hi10`, `2160p`… against the device's own video decoders ([MediaCodecList]). The emulator and
 * many TVs have no HEVC Main 10 or AV1 decoder; most anime releases are one of those.
 * ponytail: the video only; audio (AC-3, DTS, TrueHD on the Chromecast) and HDR-on-an-SDR-screen are not checked.
 */
object Playable {
    /** What a file's name says it needs: "hevc10", "av1", "avc10", "4k". */
    fun needs(name: String): Set<String> {
        val n = name.lowercase()
        val out = HashSet<String>()
        val tenBit = Regex("""10[ ._-]?bits?|main ?10|\bma10p?\b|\bhdr(10)?\b|dolby ?vision|\bdv\b""").containsMatchIn(n)
        when {
            Regex("""\bav1\b""").containsMatchIn(n) -> out += "av1"
            // HEVC without "10bit" in the name is 10 bit too in anime, nearly always ([DiabloTripleA] … S01E01.mkv: hvc1.2).
            Regex("""\bx265\b|\bhevc\b|\bh\.?265\b""").containsMatchIn(n) -> if (!Regex("""\b8[ ._-]?bits?\b""").containsMatchIn(n)) out += "hevc10"
            Regex("""\bhi10p?\b""").containsMatchIn(n) -> out += "avc10"
            Regex("""\bx264\b|\bavc\b|\bh\.?264\b""").containsMatchIn(n) -> if (tenBit) out += "avc10"
            tenBit -> out += "hevc10" // 10 bit or HDR with no codec named: HEVC in practice (10-bit H.264 says Hi10)
        }
        if (Regex("""\b2160p\b|\b4k\b|\buhd\b""").containsMatchIn(n)) out += "4k"
        return out
    }

    /** What this device decodes, from the same words as [needs] ("avc" always: every Android device has it). */
    val device: Set<String> by lazy {
        val out = hashSetOf("avc")
        val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        fun caps(mime: String) = decoders.filter { d -> d.supportedTypes.any { it.equals(mime, true) } }.map { it.getCapabilitiesForType(mime) }
        val hevc = caps("video/hevc")
        // Main 10 up to level 4.1 (1080p): the emulator refused 1080p Main 10 at 4.0 and 5.1 (hvc1.2.4.L120, L153).
        if (hevc.any { c -> c.profileLevels.any { it.profile == CodecProfileLevel.HEVCProfileMain10 && it.level >= CodecProfileLevel.HEVCMainTierLevel41 } }) out += "hevc10"
        // AV1 by hardware only: the software one (c2.android.av1-dav1d) showed a 10-bit 1080p release garbled on the
        // emulator, and a TV's CPU is too slow for it.
        if (decoders.any { d -> d.supportedTypes.any { it.equals("video/av01", true) } && hardware(d) }) out += "av1"
        if (caps("video/avc").any { c -> c.profileLevels.any { it.profile == CodecProfileLevel.AVCProfileHigh10 } }) out += "avc10"
        if ((caps("video/hevc") + caps("video/avc")).any { it.videoCapabilities?.isSizeSupported(3840, 2160) == true }) out += "4k"
        android.util.Log.i("Playable", "decodes $out")
        out
    }

    private fun hardware(d: android.media.MediaCodecInfo) =
        if (android.os.Build.VERSION.SDK_INT >= 29) d.isHardwareAccelerated else !d.name.lowercase().let { it.startsWith("omx.google.") || it.startsWith("c2.android.") }

    /** What [name] needs that [has] lacks, for the file list ("HEVC 10 bit"); empty = plays here, as far as the name says. */
    fun missing(name: String, has: Set<String> = device): List<String> = (needs(name) - has).map { LABELS.getValue(it) }

    private val LABELS = mapOf("hevc10" to "HEVC 10 bit", "av1" to "AV1", "avc10" to "H.264 10 bit", "4k" to "4K")
}
