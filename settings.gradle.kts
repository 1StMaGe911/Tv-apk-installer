pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Kadb's ADB pairing implementation depends on SPAKE2 published via JitPack.
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "TV APK Installer"
include(":app")
