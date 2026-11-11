plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    // Kadb 2.1.5 requires Android 17 (API 37) at compile time.
    // Keep targetSdk unchanged; this does not opt the app into API 37 runtime behavior.
    compileSdk = 37
    ndkVersion = "27.2.12479018"

    namespace = "ru.tvapk.installer"

    defaultConfig {
        applicationId = "ru.tvapk.installer"
        minSdk = 26
        targetSdk = 35
        versionCode = 181
        versionName = "0.9.130"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
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
            // Keep CI/test builds separate from the installed production app. Each GitHub
            // runner has its own debug signing key, so reusing the production applicationId
            // would cause INSTALL_FAILED_UPDATE_INCOMPATIBLE on upgrade.
            // PR validation has no persistent signing key, so use a separate package
            // to allow side-by-side installation without conflicting with production.
            // The release workflow supplies SIGNING_STORE_FILE and must keep the stable ID.
            if (System.getenv("SIGNING_STORE_FILE") == null) {
                applicationIdSuffix = ".preview"
            } else {
                signingConfig = signingConfigs.getByName("release")
            }
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

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Bouncy Castle 1.86 modules carry the same MIT license text at this path.
    // Keep one copy in the APK rather than failing on duplicate archive entries.
    packaging {
        resources {
            pickFirsts += "META-INF/LICENSE.md"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("dev.mobile:dadb:2.0.0")
    // TLS pairing + TLS ADB transport for Android 11+ Wireless Debugging.
    implementation("com.flyfishxu:kadb:2.1.5") {
        // Replace Kadb Android's GPL-3.0 SPAKE2 implementation with our JNI bridge to BoringSSL.
        exclude(group = "com.github.Flyfish233", module = "spake2-java")
    }
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
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
