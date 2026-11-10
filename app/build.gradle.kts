plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    // Kadb 2.1.5 requires Android 17 (API 37.0) at compile time.
    // Keep targetSdk unchanged; this does not opt the app into API 37 runtime behavior.
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    namespace = "ru.tvapk.installer"

    defaultConfig {
        applicationId = "ru.tvapk.installer"
        minSdk = 26
        targetSdk = 35
        versionCode = 181
        versionName = "0.9.130"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("SIGNING_STORE_FILE") ?: "signing.jks")
            storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: ""
            keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "tvapk"
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("release")
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
}

dependencies {
    implementation("dev.mobile:dadb:2.0.0")
    // TLS pairing + TLS ADB transport for Android 11+ Wireless Debugging.
    implementation("com.flyfishxu:kadb:2.1.5")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
