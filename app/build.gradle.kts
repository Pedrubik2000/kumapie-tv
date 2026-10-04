plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The version comes from the release tag: CI runs `gradlew assembleRelease -PversionName=1.2.3` for tag v1.2.3.
// versionCode = 1.2.3 -> 10203, so every release installs over the one before.
val appVersion = (findProperty("versionName") as String?) ?: "0.0.0"
val appVersionCode = appVersion.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    .let { (it.getOrElse(0) { 0 } * 10000 + it.getOrElse(1) { 0 } * 100 + it.getOrElse(2) { 0 }).coerceAtLeast(1) }

android {
    namespace = "io.github.pedrubik2000.kumapie"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.pedrubik2000.kumapie"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersion
        // Where the app looks for new releases (Settings > Check for updates, and on start).
        buildConfigField("String", "UPDATE_REPO", "\"Pedrubik2000/kumapie-tv\"")
    }

    // Release key: never in the repo. CI gets it from the repository secrets; a local release build
    // reads the same four environment variables (see README, "Releases").
    val keystore = System.getenv("KUMAPIE_KEYSTORE")
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("KUMAPIE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KUMAPIE_KEY_ALIAS")
                keyPassword = System.getenv("KUMAPIE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Debug builds install next to the release app instead of clashing with its signature.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // No analytics/metadata blobs in the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui-compose:1.11.1")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
}
