plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

// kumapie on the PC: a desktop window over the PC's own server (dojo-feed's /api/tv and /api/anki), video by libVLC.
// It compiles :core's sources itself, with small stand-ins for the few Android APIs they touch (src/main/kotlin/android*),
// so the TV and phone builds don't change.
// ponytail: compiling another module's sources; make :core a KMP module (android + jvm) once the stand-ins grow.
kotlin {
    sourceSets.main {
        kotlin.srcDir("../core/src/main/java")
        kotlin.exclude("io/github/pedrubik2000/kumapie/update/**") // the APK self-updater: Android only
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.json:json:20260814") // Android's own on the phone
    implementation("uk.co.caprica:vlcj:4.12.1") // libVLC 3: VLC 3.x installed, or its folder in KUMAPIE_VLC
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
}

compose.desktop {
    application {
        mainClass = "io.github.pedrubik2000.kumapie.desktop.MainKt"
    }
}
