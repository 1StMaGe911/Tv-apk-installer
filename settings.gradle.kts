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
    "feature:modules", "feature:backup", "app", "benchmark", "tv:curtain"
)
modules.forEach { path ->
    include(":$path")
    project(":$path").projectDir = file(cyt.resolve(path.replace(':', '/')).path)
}
