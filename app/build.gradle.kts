import org.gradle.api.tasks.testing.Test

plugins {
    // Use upstream's tested Android application convention (toolchain, Compose, signing and APK checks).
    id("cleanyourtv.android.app")
    id("cleanyourtv.serialization")
}

android {
    defaultConfig {
        versionCode = 188
        versionName = "0.9.137"
    }

    flavorDimensions += "store"
    productFlavors {
        create("full") {
            dimension = "store"
            isDefault = true
        }
        create("play") {
            dimension = "store"
        }
    }

    testOptions { unitTests { isIncludeAndroidResources = true } }
    lint { textReport = true }

    sourceSets {
        getByName("main") {
            java.srcDir("../vendor/cleanyourtv-app/app/src/main/kotlin")
            res.srcDir("../vendor/cleanyourtv-app/app/src/main/res")
            assets.srcDir("../vendor/cleanyourtv-app/app/src/main/assets")
        }
        getByName("full") {
            java.srcDir("../vendor/cleanyourtv-app/app/src/full/kotlin")
            res.srcDir("../vendor/cleanyourtv-app/app/src/full/res")
        }
        getByName("play") {
            java.srcDir("../vendor/cleanyourtv-app/app/src/play/kotlin")
            res.srcDir("../vendor/cleanyourtv-app/app/src/play/res")
        }
        getByName("debug") {
            manifest.srcFile("../vendor/cleanyourtv-app/app/src/debug/AndroidManifest.xml")
        }
    }
}

dependencies {
    // Keep the legacy TV APK Installer activity compilable while it remains an internal fallback.
    implementation("dev.mobile:dadb:2.0.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.compose.material:material-icons-extended")
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

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":testing:fixtures"))
    testImplementation(project(":testing:fake-adbd"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

androidComponents {
    beforeVariants(selector().withBuildType("benchmark")) { variant ->
        if (variant.productFlavors.contains("store" to "play")) variant.enable = false
        variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
    }
}

tasks.named("check") { dependsOn("lintPlayDebug") }
tasks.withType<Test>().configureEach {
    maxParallelForks = 1
    maxHeapSize = "1280m"
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
