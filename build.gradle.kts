plugins {
    id("com.android.application") version "9.3.1" apply false
    // AGP 9.3.1 supplies built-in Kotlin; only the Compose compiler plugin remains external.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
