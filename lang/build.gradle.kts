plugins {
    id("com.android.library")
}

// The language layer (Language: Spaced for German/English, Japanese) and what it needs: dictionaries, known words
// from kuma3 Anki, word audio, mining. Shared by the phone app and the apps split from it (kumapie_languages_plan.md).
// No Python here: Chaquopy works in one module per app, so the app passes spaCy in (Languages' spacy).
android {
    namespace = "io.github.pedrubik2000.kumapie.lang"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    api(project(":core"))
    implementation("androidx.core:core:1.18.0") // FileProvider (mined cards' media)
    implementation("androidx.media3:media3-transformer:1.11.1") // scene clips for mined cards
    implementation("androidx.media3:media3-effect:1.11.1")
    implementation("androidx.media3:media3-muxer:1.11.1") // WebM clips (WebmMuxer.kt)
    implementation("com.worksap.nlp:sudachi:0.8.2") // Japanese words (JapaneseModel.kt); dictionary downloaded
    implementation("androidx.work:work-runtime-ktx:2.11.0")
}
