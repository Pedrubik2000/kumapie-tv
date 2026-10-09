plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

// The phone/tablet app. Same version scheme and release key as the TV app (see app/build.gradle.kts);
// its own app id, so both can be installed on one device.
val appVersion = (findProperty("versionName") as String?) ?: "0.0.0"
val appVersionCode = appVersion.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    .let { (it.getOrElse(0) { 0 } * 10000 + it.getOrElse(1) { 0 } * 100 + it.getOrElse(2) { 0 }).coerceAtLeast(1) }

android {
    namespace = "io.github.pedrubik2000.kumapie.mobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.pedrubik2000.kumapie.mobile"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersion
        buildConfigField("String", "UPDATE_REPO", "\"Pedrubik2000/kumapie-tv\"")
        // Python (spaCy) is built for 64-bit ARM only: every phone and tablet it runs on.
        ndk { abiFilters += "arm64-v8a" }
    }

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

    // libqjs.so (QuickJS, tools/quickjs/build.sh) is a program, run from the native library folder: it must be
    // unpacked there at install.
    packaging { jniLibs { useLegacyPackaging = true } }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

// Python inside the app for German parsing: spaCy finds each word's dictionary form and joins split verbs, exactly
// as morphs does on the PC (src/main/python). spaCy's Android wheels are for Python 3.10. The build needs a
// Python 3.10 too: `chaquopy.buildPython=<path>` in local.properties, else `python3.10` on the PATH (CI).
val localBuildPython: String? = rootProject.file("local.properties").takeIf { it.exists() }
    ?.readLines()?.firstOrNull { it.startsWith("chaquopy.buildPython=") }?.substringAfter("=")?.trim()
chaquopy {
    defaultConfig {
        version = "3.10"
        localBuildPython?.let { buildPython(it) }
        pip {
            install("spacy==3.8.7")
            install("click") // spaCy imports it; the typer it pulls in no longer does
            install("yt-dlp") // YouTube downloads (python/youtube.py), with QuickJS (jniLibs) for the JS challenges
            install("yt-dlp-ejs")
        }
    }
}

// sherpa-onnx (on-device speech recognition: Parakeet, local/Parakeet.kt) is published as an AAR on its GitHub
// releases, not on Maven: fetched once into build/ (48 MB, kept out of the repo).
val sherpaVersion = "1.13.8"
val sherpaAar = layout.buildDirectory.file("libs/sherpa-onnx-$sherpaVersion.aar").get().asFile
if (!sherpaAar.exists()) {
    sherpaAar.parentFile.mkdirs()
    val url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar").toURL()
    sherpaAar.writeBytes(url.readBytes())
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(project(":core"))
    implementation(project(":lang")) // the language layer (Languages)
    implementation(files(sherpaAar))
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1") // condensed listening in the background (listen/Condensed.kt)
    implementation("androidx.media3:media3-transformer:1.11.1") // audio cut for new episodes (local/ProcessWorker.kt)
    implementation("com.google.mlkit:translate:17.0.3") // German -> English on the device (local/ProcessWorker.kt)
    implementation("androidx.work:work-runtime-ktx:2.11.0")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
}
