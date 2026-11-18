plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("com.chaquo.python") version "17.0.0" apply false
    // :desktop (kumapie on the PC): Kotlin/JVM + Compose Multiplatform
    id("org.jetbrains.kotlin.jvm") version "2.4.20" apply false
    id("org.jetbrains.compose") version "1.12.2" apply false
}

// APK size budget (idea from HolyArrs: measure every build, or it grows unnoticed). A release APK over its limit fails
// the build: CI's Build, the Release workflow and build_local.ps1. Sizes 2026-11-18: TV 10.2 MB (2.4 MB until v0.34,
// then hoshidicts for two ABIs), phone 77.4 MB. Raise a limit only on purpose, with the reason in the commit.
val apkBudgetMb = mapOf("app" to 11.0, "mobile" to 80.0)
subprojects {
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
