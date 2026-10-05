plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

// What the TV app and the phone/tablet app share: the server API, the scene logic, the word picker, the
// subtitles and meaning card (drawn with plain Compose text, no Material), and the self-updater.
android {
    namespace = "io.github.pedrubik2000.kumapie.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    api(composeBom)
    api("androidx.compose.foundation:foundation")
    api("androidx.media3:media3-common:1.11.1")
}
