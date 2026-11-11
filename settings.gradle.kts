pluginManagement {
    includeBuild("vendor/cleanyourtv-app/build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("vendor/cleanyourtv-app/gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "TV APK Installer"

val cyt = file("vendor/cleanyourtv-app")
val modules = listOf(
    "core:model", "core:spec", "core:ops", "core:probe", "core:planner",
    "core:engine", "core:trust", "core:registry", "core:backup", "core:flows",
    "adb:wire", "adb:pairing", "adb:session",
    "testing:fixtures", "testing:fake-adbd", "e2e",
    "android:platform", "android:work", "ui:design",
    "feature:connect", "feature:setup", "feature:manage", "feature:launcher",
    "feature:modules", "feature:backup", "benchmark", "tv:curtain"
)
val parentModules = listOf("core", "adb", "testing", "android", "ui", "feature", "tv")
parentModules.forEach { path ->
    include(":$path")
    project(":$path").projectDir = cyt.resolve(path)
}
modules.forEach { path ->
    include(":$path")
    project(":$path").projectDir = cyt.resolve(path.replace(':', '/'))
}

// Keep :app mapped to this repository's TV APK Installer application.
// The upstream Clean Your TV :app is deliberately not included as the installable app.
