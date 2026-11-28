package io.github.pedrubik2000.kumapie.data

data class StreamFacts(val instant: Boolean?, val quality: String, val title: String, val tags: List<String>, val size: String,
                       val seeders: Int?, val site: String)

private val QUALITY = Regex("""\b(2160p|4K|1080p|720p|576p|480p)\b""", RegexOption.IGNORE_CASE)
private val GROUP = Regex("""^\[([^\]]{2,30})\]""")
private val SIZE = Regex("""💾\s*([\d.,]+\s*[KMGT]i?B)""")
private val SEEDS = Regex("""👤\s*(\d+)""")
private val SITE = Regex("""⚙️?\s*([^\n👤💾⚙]+)""")
private val CYRILLIC = Regex("""\p{IsCyrillic}""")
private val TAGS = listOf(
    Regex("""\bBD\s?Remux\b|\bRemux\b""", RegexOption.IGNORE_CASE) to "BD Remux",
    Regex("""\bBD\s?Rip\b|\bBlu-?Ray\b|\bBD\b""", RegexOption.IGNORE_CASE) to "BD",
    Regex("""\bWEB-?DL\b|\bWEB-?Rip\b|\bWEB\b""", RegexOption.IGNORE_CASE) to "WEB",
    Regex("""\bHEVC\b|\bx265\b|\bH\.?265\b""", RegexOption.IGNORE_CASE) to "HEVC",
    Regex("""\bAVC\b|\bx264\b|\bH\.?264\b""", RegexOption.IGNORE_CASE) to "AVC",
    Regex("""\bDual[ .-]?Audio\b""", RegexOption.IGNORE_CASE) to "Dual audio",
    Regex("""\bJAP\+Sub\b|\bRAW\b""", RegexOption.IGNORE_CASE) to "JP",
)

fun streamFacts(label: String, about: String): StreamFacts {
    val all = "$label\n$about"
    val instant = when {
        "RD+" in label -> true
        "RD download" in label || "RD⬇" in label -> false
        else -> null
    }
    val first = about.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
    val site = SITE.find(about)?.groupValues?.get(1)?.trim().orEmpty()
    val title = GROUP.find(first)?.groupValues?.get(1)?.takeUnless { it.startsWith("RD") }
        ?: site.takeIf { CYRILLIC.containsMatchIn(first) && it.isNotEmpty() }
        ?: first.ifEmpty { label }
    val tags = TAGS.mapNotNull { (re, tag) -> tag.takeIf { re.containsMatchIn(all) } }
        .let { t -> if ("BD Remux" in t) t - "BD" else t }
    return StreamFacts(instant, QUALITY.find(all)?.value?.lowercase()?.replace("4k", "2160p").orEmpty(), title, tags,
        SIZE.find(about)?.groupValues?.get(1).orEmpty(), SEEDS.find(about)?.groupValues?.get(1)?.toIntOrNull(),
        site.takeUnless { it == title }.orEmpty())
}
