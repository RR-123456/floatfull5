plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pragon.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pragon.mobile"
        minSdk = 31
        targetSdk = 34
        versionCode = 83
        versionName = "8.3"
        ndk { abiFilters += "arm64-v8a" }   // v12: phones only (one native set = smallest APK)
    }
    // v8.1: no locale filter, so every language's strings are kept again.
    buildTypes {
        release { isMinifyEnabled = false }
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "/META-INF/NOTICE*")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // v18: video editor export (trim, rotate, mirror, mute), Media3 Transformer
    implementation("androidx.media3:media3-transformer:1.4.1")
    implementation("androidx.media3:media3-effect:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")   // on-device LLMs
    // v12: speech comes from sherpa-onnx. Its native libraries (app/src/main/jniLibs) and Kotlin API (app/src/main/java/com/k2fsa)
    // are fetched by tools/fetch_sherpa.sh, so they are not in this folder. Vosk is gone.
    // v7.3: on-phone vision (English only): OCR + image labels (models bundled), subject cut-out (model via Google Play services)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.mlkit:object-detection:17.0.2")
    // v7.6: BlindCam live camera preview + frame analysis
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
    implementation("com.google.android.gms:play-services-base:18.5.0")      // v7.7: ModuleInstall, to pre-download the cut-out model
    implementation("org.apache.commons:commons-compress:1.27.1")   // unpacks the .tar.bz2 speech model
}
