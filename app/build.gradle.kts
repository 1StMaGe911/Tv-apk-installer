plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("cleanyourtv.serialization")
}

android {
    namespace = "ru.tvapk.installer"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.tvapk.installer"
        minSdk = 26
        targetSdk = 35
        versionCode = 188
        versionName = "0.9.137"
    }

    flavorDimensions += "store"
    productFlavors {
        create("full") { dimension = "store" }
    }

    val releaseSigningStore = System.getenv("SIGNING_STORE_FILE")?.let { file(it) }
    signingConfigs {
        create("release") {
            storeFile = releaseSigningStore ?: file("signing.jks")
            storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: ""
            keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "tvapk"
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: ""
        }
    }
    buildTypes {
        getByName("debug") {
            if (releaseSigningStore?.isFile == true) signingConfig = signingConfigs.getByName("release")
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDir("vendor/cleanyourtv-app/app/src/main/kotlin")
            res.srcDir("vendor/cleanyourtv-app/app/src/main/res")
            assets.srcDir("vendor/cleanyourtv-app/app/src/main/assets")
        }
        getByName("full") {
            java.srcDir("vendor/cleanyourtv-app/app/src/full/kotlin")
            res.srcDir("vendor/cleanyourtv-app/app/src/full/res")
        }
        getByName("debug") {
            manifest.srcFile("vendor/cleanyourtv-app/app/src/debug/AndroidManifest.xml")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Existing TV APK Installer tooling remains available in the same application.
    implementation("dev.mobile:dadb:2.0.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Clean Your TV application graph and complete feature set.
    implementation(project(":feature:connect"))
    implementation(project(":feature:setup"))
    implementation(project(":feature:manage"))
    implementation(project(":feature:launcher"))
    implementation(project(":feature:modules"))
    implementation(project(":feature:backup"))
    implementation(project(":ui:design"))
    implementation(project(":core:flows"))
    implementation(project(":android:work"))
    implementation(project(":android:platform"))
    implementation(project(":adb:session"))
    implementation(project(":adb:wire"))
    implementation(project(":core:ops"))
    implementation(project(":core:spec"))
    implementation(project(":core:probe"))
    implementation(project(":core:planner"))
    implementation(project(":core:engine"))
    implementation(project(":core:backup"))
    implementation(project(":core:trust"))
    implementation(project(":core:registry"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(project(":testing:fixtures"))
    testImplementation(project(":testing:fake-adbd"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
