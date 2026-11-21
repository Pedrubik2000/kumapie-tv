plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("com.chaquo.python") version "17.0.0" apply false
    // :desktop (kumapie on the PC): Kotlin/JVM + Compose Multiplatform
    id("org.jetbrains.kotlin.jvm") version "2.4.20" apply false
    id("org.jetbrains.compose") version "1.12.2" apply false
    id("dev.detekt") version "2.0.0-alpha.6" apply false
}

// Lint (C:\dojo\code\plans\kumapie_slop_plan.md): detekt on the TV app and core, no type resolution, only the rules in
// detekt.yml. Old findings are in <module>/detekt-baseline.xml (`gradlew :app:detektBaseline` rewrites it, on purpose
// only). A finding fails the build with its fix, read from the rule's "# fix:" line in detekt.yml.
val detektFixes = file("detekt.yml").readLines().mapNotNull { Regex("""^\s+(\w+): # fix: (.+)""").find(it)?.destructured?.let { (r, f) -> r to f } }.toMap()
listOf(":app", ":core").forEach { path ->
    project(path) {
        apply(plugin = "dev.detekt")
        extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
            buildUponDefaultConfig.set(false)
            config.setFrom(rootProject.file("detekt.yml"))
            baseline.set(file("detekt-baseline.xml"))
            source.setFrom("src/main/java")
            ignoreFailures.set(true)
        }
        tasks.withType<dev.detekt.gradle.Detekt>().matching { it.name == "detekt" }.configureEach {
            val xml = reports.checkstyle.outputLocation
            doLast {
                val found = Regex("""source="detekt\.(\w+)"""").findAll(xml.get().asFile.takeIf { it.exists() }?.readText().orEmpty()).map { it.groupValues[1] }.toList()
                if (found.isNotEmpty()) throw GradleException("detekt: ${found.size} new finding(s) in $path, listed above.\n" +
                    found.distinct().joinToString("\n") { r -> "  $r: " + (detektFixes[r] ?: "see the reason above") })
            }
        }
    }
}

// slopCheck: what detekt can't do, against slop.baseline (`gradlew slopCheck -PslopBaseline` rewrites it, on purpose only).
// :app/:core src/main: no comments (a file may only lose them); :lang/:mobile: the comment count may not grow; a new file
// is 400 lines at most and one already over may not grow (lang/jiten exempt: rule tables); no new file name ending in a
// digit; the forbidden text below in its modules, :app/:core unless it names others (detekt's ForbiddenImport misses a fully qualified name). Comments and
// strings are skipped when matching.
data class Forbidden(val text: String, val re: Regex, val allowedIn: List<String>, val why: String, val modules: List<String> = listOf("app", "core"))
val slopForbidden = listOf(
    Forbidden("!!", Regex("!!"), emptyList(), "?.let { }, ?: return, or requireNotNull(x) { \"why\" }: a !! is a TV crash nobody can read"),
    Forbidden("Locale.getDefault(", Regex("""Locale\.getDefault\("""), listOf("/data/Settings.kt", "/i18n/"),
        "the person's language is Settings (menuLanguage / speaks, ALONE_LANG without a PC), not the system's: the no-PC player showed English under a Spanish Home"),
    Forbidden("java.net.URI", Regex("""^(?!import ).*\bjava\.net\.URI\b"""), emptyList(), "URI() throws on a | or a space (Torrentio options crashed Settings): use Uri.parse or Addons.base"),
    Forbidden("android.speech.tts.TextToSpeech", Regex("""^(?!import ).*\bandroid\.speech\.tts\.TextToSpeech\b"""), emptyList(), "one TextToSpeech per stream leaked: speak through Languages.of(lang).voice"),
    Forbidden("android.os.Handler", Regex("""^(?!import ).*\bandroid\.os\.Handler\b"""), emptyList(), "post and postDelayed outlive the screen: use LaunchedEffect / delay in a coroutine"),
    Forbidden("kotlinx.coroutines.runBlocking", Regex("""^(?!import ).*\bkotlinx\.coroutines\.runBlocking\b"""), emptyList(), "it freezes the main thread: use a coroutine scope"),
    Forbidden("e.ready ->", Regex("""\be\.ready\s*->"""), emptyList(),
        "use route() (core data/ShowLevel.kt): a Home card's Player / Watch / Discover is decided there once (no-PC Continue cards opened \"No PC for /api/tv/watch\")", listOf("app", "mobile")),
    Forbidden("nextEpisode(", Regex("""\bnextEpisode\("""), emptyList(), "use nextUp() (core data/ShowLevel.kt): which episode is next is one rule", listOf("app", "mobile", "core")),
    Forbidden("duration * 0.95", Regex("""duration\s*\*\s*0\.9"""), emptyList(), "use partway() / nextUp() (core data/ShowLevel.kt): partway = over 20 s in and over 2 min left", listOf("app", "mobile")),
    Forbidden("- 120 / > 120 partway test", Regex("""(duration\s*-.*[<>]=?\s*120\b)|(\b120\s*[<>])"""), listOf("/data/ShowLevel.kt"),
        "use partway() / finished() (core data/ShowLevel.kt): \"under 2 min left = watched\" lives there once", listOf("app", "mobile", "core")),
    Forbidden("AloneStream.resume(", Regex("""AloneStream\.resume\("""), emptyList(),
        "use StreamStart (core data/Streaming.kt): Alone(url, resumeAt) or Pc(query) is decided there once", listOf("app", "mobile")),
    Forbidden("savePlace(", Regex("""\bsavePlace\("""), emptyList(),
        "use StreamStart: AloneStream.keepPlace() saves the place every 5 s and leave() on the way out, one saver for both players", listOf("app", "mobile")),
    Forbidden(".save(title, true)", Regex("""\.save\(\s*title\s*,\s*true\s*\)"""), emptyList(),
        "use StreamStart.picked(): a picked addon link puts its title on Home there", listOf("app", "mobile")),
    Forbidden(".ended( / nextId(", Regex("""\.ended\(|\bnextId\("""), emptyList(),
        "use nextQuery() / Addons.next(query) (core data/Streaming.kt): the next episode's query is built there once", listOf("app", "mobile")),
    Forbidden("fun japaneseWord( / tvHeadword", Regex("""\bfun japaneseWord\(|\btvHeadword\b"""), emptyList(),
        "use WordCard.japaneseHeadword() / WordCard.japaneseWord() (lang WordCard.kt): the TV and the phone made different cards of one word", listOf("app", "mobile")),
    Forbidden("language.lookup(", Regex("""\blanguage\.lookup\("""), listOf("/mobile/ui/YomitanPopup.kt"),
        "use WordCard.popup() (lang WordCard.kt): a subtitle word's popup shows the headword its card and ratings get (the phone showed フリー, Mine made フリーレン; YomitanPopup: words inside a definition)", listOf("app", "mobile")),
    Forbidden("known.rate( / known.undo( / miner.recontext(", Regex("""\bknown\.(rate|undo)\(|\bminer\.recontext\("""), listOf("/mobile/WordColours.kt"),
        "use RateFlow (lang RateFlow.kt): rate -> undo -> the due queue -> recontext is one path for both apps (WordColours: the readers' provider, no scene)", listOf("app", "mobile")),
    Forbidden("pos/saved/resume?.takeIf { > N }", Regex("""\b(pos|saved|resume)\w*\??\.takeIf \{ (\w+ -> )?\w+ > [1-9]"""), emptyList(),
        "use RESUME_AFTER_S / partway() (core data/ShowLevel.kt): a place resumes over 20 s, one number (no-PC streams resumed over 5 s, Home over 20 s)", listOf("app", "mobile", "core")),
    Forbidden(": Backend {", Regex(""":\s*(io\.github\.pedrubik2000\.kumapie\.data\.)?Backend\s*(\{|by\b)"""), listOf("/mobile/offline/Library.kt"),
        "use the :lang no-PC backend (lang NoPcBackend.kt): the TV marked words at once, the phone only once Anki was read (Library.kt's OfflineBackend is the with-PC part on top of it)", listOf("app", "mobile")),
    Forbidden("daysIn( / totals(", Regex("""\b(daysIn|totals)\("""), listOf("/mobile/offline/Library.kt"),
        "use Progress.homeTotal() (lang Progress.kt): Home's time and words known, one sum for the TV and the phone (the TV split the old time without its shows; Library.kt: sendWatch's days for the PC)", listOf("app", "mobile")),
    Forbidden("Updater.newer( / Updater.repo =", Regex("""\bUpdater\.(newer\(|(repo|version|asset)\s*=)"""), listOf("/ui/SettingsScreen.kt"),
        "use Startup.updates() / Startup.update(BuildConfig.DEBUG) (core data/Startup.kt): one update check at start, none in debug builds (Settings' button: its own error line)", listOf("app", "mobile")),
    Forbidden("server.isEmpty() && !standalone / !onboarded, ONBOARDED", Regex("""server\.isEmpty\(\)\s*&&\s*!(standalone|onboarded)\b|\b(TV_)?ONBOARDED\b"""), emptyList(),
        "use Startup.gate() / Startup.onboarded() (core data/Startup.kt): first run, onboarding or the app is one rule, one \"onboarded\" key", listOf("app", "mobile")),
    Forbidden("StreamStart.picked( / StreamStart.ready( / Playable.missing( / discoverStreams(title", Regex("""\bStreamStart\.(picked|ready)\(|\bPlayable\.missing\(|\bdiscoverStreams\(\s*title\b"""), emptyList(),
        "use TitlePage (core data/TitlePage.kt): ▶, play or the file list, the files' order, + Home and ✓ are one holder for both title pages", listOf("app", "mobile")),
)

/** [src] with comments removed and string contents blanked (newlines kept), and its number of comments. */
fun kotlinCode(src: String): Pair<String, Int> {
    val out = StringBuilder(src.length)
    var comments = 0
    var i = 0
    val modes = ArrayDeque(listOf(0)) // 0 code, 1 "string", 2 """raw""", 3 ${template}
    val braces = ArrayDeque<Int>()
    fun at(p: String) = src.startsWith(p, i)
    fun blank(n: Int) { repeat(n) { if (i < src.length) out.append(if (src[i] == '\n') '\n' else ' '); i++ } }
    while (i < src.length) {
        val c = src[i]
        when (modes.last()) {
            0, 3 -> when {
                at("//") -> { comments++; while (i < src.length && src[i] != '\n') blank(1) }
                at("/*") -> { comments++; var d = 0
                    do { if (at("/*")) { d++; blank(2) } else if (at("*/")) { d--; blank(2) } else blank(1) } while (d > 0 && i < src.length) }
                at("\"\"\"") -> { modes.addLast(2); out.append("\"\"\""); i += 3 }
                c == '"' -> { modes.addLast(1); out.append(c); i++ }
                c == '\'' -> { out.append(c); i++; while (i < src.length && src[i] != '\'') blank(if (src[i] == '\\') 2 else 1); out.append('\''); i++ }
                c == '{' && modes.last() == 3 -> { braces.addLast(braces.removeLast() + 1); out.append(c); i++ }
                c == '}' && modes.last() == 3 -> { val d = braces.removeLast(); if (d == 0) modes.removeLast() else braces.addLast(d - 1); out.append(c); i++ }
                else -> { out.append(c); i++ }
            }
            1 -> when {
                c == '\\' -> blank(2)
                c == '"' -> { modes.removeLast(); out.append(c); i++ }
                at("\${") -> { modes.addLast(3); braces.addLast(0); out.append("\${"); i += 2 }
                else -> blank(1)
            }
            else -> when {
                at("\"\"\"") && !src.startsWith("\"\"\"\"", i) -> { modes.removeLast(); out.append("\"\"\""); i += 3 }
                at("\${") -> { modes.addLast(3); braces.addLast(0); out.append("\${"); i += 2 }
                else -> blank(1)
            }
        }
    }
    return out.toString() to comments
}

tasks.register("slopCheck") {
    group = "verification"
    description = "Comment ban and ratchets, file size cap, no Foo2.kt, forbidden text (against slop.baseline)"
    doLast {
        val baseFile = file("slop.baseline")
        val base = baseFile.takeIf { it.exists() }?.readLines().orEmpty().filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { l -> if (l.startsWith("digit ")) l to 0 else l.substringBeforeLast(' ') to l.substringAfterLast(' ').toInt() }
        val now = sortedMapOf<String, Int>()
        val errors = mutableListOf<String>()
        fun over(key: String, n: Int, cap: Int = 0, error: (Int) -> String) {
            if (n > cap) now[key] = n
            val was = base[key] ?: cap
            if (n > was) errors += error(was)
        }
        val moduleComments = mutableMapOf<String, Int>()
        listOf("app", "core", "lang", "mobile").forEach { m ->
            file("$m/src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                val rel = f.relativeTo(rootDir).invariantSeparatorsPath
                val src = f.readText()
                val (code, comments) = kotlinCode(src)
                if (m == "app" || m == "core") {
                    over("comments $rel", comments) { was -> "$rel: $comments comment(s), was $was. No comments in :app/:core src/main: the why goes in the commit message or kumapie_plan.md \"Decisions in force\", a rule the code must follow in detekt.yml or slopCheck." }
                } else moduleComments.merge(":$m", comments, Int::plus)
                val hits = code.lines().withIndex().flatMap { (n, line) ->
                    slopForbidden.filter { m in it.modules && it.re.containsMatchIn(line) && it.allowedIn.none { a -> a in rel } }.map { "$rel:${n + 1}: `${it.text}`: ${it.why}" }
                }
                over("forbid $rel", hits.size) { was -> "forbidden text ($was allowed in this file by the baseline):\n    " + hits.joinToString("\n    ") }
                if ("/lang/jiten/" !in rel) {
                    val lines = src.trimEnd().lines().size
                    over("lines $rel", lines, 400) { was ->
                        if (was == 400) "$rel: $lines lines, over the 400 cap for a file: split it by what it does (a screen, a helper), not into a Foo2.kt."
                        else "$rel: $lines lines, was $was (already over the 400 cap, so it may not grow): move code out before adding."
                    }
                }
                if (f.nameWithoutExtension.last().isDigit()) {
                    now["digit $rel"] = 0
                    if ("digit $rel" !in base) errors += "$rel: a file name ending in a digit is a split by size, not by meaning: name it for what it holds."
                }
            }
        }
        moduleComments.forEach { (m, n) ->
            over("comments $m", n) { was -> "$m: $n comments in src/main, was $was: the count may not grow (the why goes in the commit message)." }
        }
        if (project.hasProperty("slopBaseline")) {
            baseFile.writeText("# slopCheck's baseline (build.gradle.kts): shrinks, never grows. Rewritten by `gradlew slopCheck -PslopBaseline`.\n" +
                now.entries.joinToString("") { (k, v) -> if (k.startsWith("digit ")) "$k\n" else "$k $v\n" })
            println("slop.baseline rewritten: ${now.size} entries")
            return@doLast
        }
        val lower = base.count { (k, v) -> !k.startsWith("digit ") && (now[k] ?: 0) < v }
        if (lower > 0) println("slopCheck: $lower baseline entries went down: `gradlew slopCheck -PslopBaseline` locks that in.")
        if (errors.isNotEmpty()) throw GradleException("slopCheck: ${errors.size} problem(s)\n" + errors.joinToString("\n") { "  $it" })
        println("slopCheck: ok")
    }
}

// APK size budget (idea from HolyArrs: measure every build, or it grows unnoticed). A release APK over its limit fails
// the build: CI's Build, the Release workflow and build_local.ps1. Sizes 2026-11-18: TV 10.2 MB (2.4 MB until v0.34,
// then hoshidicts for two ABIs), phone 77.4 MB. Raise a limit only on purpose, with the reason in the commit.
val apkBudgetMb = mapOf("app" to 11.0, "mobile" to 80.0)
subprojects {
    if (rootProject.hasProperty("debuggable")) return@subprojects // a heap-dump build (app/build.gradle.kts), never shipped
    val budget = apkBudgetMb[name] ?: return@subprojects
    val apks = layout.buildDirectory.dir("outputs/apk/release")
    tasks.matching { it.name == "assembleRelease" }.configureEach {
        doLast {
            apks.get().asFile.listFiles { f -> f.extension == "apk" }.orEmpty().forEach { apk ->
                val mb = apk.length() / 1e6
                check(mb <= budget) { "${apk.name} is %.1f MB, over its %.1f MB budget (build.gradle.kts, apkBudgetMb)".format(mb, budget) }
                println("APK size: ${apk.name} %.1f MB of %.1f MB".format(mb, budget))
            }
        }
    }
}
