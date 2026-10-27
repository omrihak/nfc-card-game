plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.omrihak.nfccards.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.omrihak.nfccards.companion"
        minSdk = 26
        targetSdk = 35
        versionCode = rootProject.extra["tapVersionCode"] as Int
        versionName = rootProject.extra["tapVersionName"] as String
    }

    // Same checked-in key as the player: Google sign-in is tied to this key's SHA-1 (see README,
    // "Companion app"), so builds from any computer must be signed with it.
    signingConfigs {
        getByName("debug") {
            storeFile = file("../app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // One release key for both apps, from env (see README, "Releases"). Without it release builds are unsigned.
        System.getenv("TAP_KEYSTORE_FILE")?.let { path ->
            create("release") {
                storeFile = rootProject.file(path)
                storePassword = System.getenv("TAP_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TAP_KEY_ALIAS")
                keyPassword = System.getenv("TAP_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // Google sign-in for Drive (AuthorizationClient).
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is stubbed in JVM unit tests; use the real implementation there.
    testImplementation("org.json:json:20240303")
}
