#!/usr/bin/env python3
"""Apply the small, reproducible product-identity patch to the pinned upstream source."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
upstream = root / "vendor" / "cleanyourtv-app"
app_gradle = upstream / "app" / "build.gradle.kts"
convention = upstream / "build-logic" / "convention" / "src" / "main" / "kotlin" / "AndroidConventionPlugins.kt"
jvm_convention = upstream / "build-logic" / "convention" / "src" / "main" / "kotlin" / "JvmConventionPlugin.kt"

def replace_once(path: Path, old: str, new: str) -> None:
    data = path.read_text(encoding="utf-8")
    if new in data:
        return
    count = data.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one occurrence in {path}: {old!r}; found {count}")
    path.write_text(data.replace(old, new), encoding="utf-8")

replace_once(app_gradle, "versionCode = 30", "versionCode = 189")
replace_once(app_gradle, 'versionName = "0.4.8"', 'versionName = "0.9.138"')
store_source = upstream / "app" / "src" / "full" / "kotlin" / "tv" / "cleanyour" / "app" / "Store.kt"
replace_once(store_source, "https://api.github.com/repos/udhaykumarbala/cleanyourtv-app/releases/latest", "https://api.github.com/repos/1StMaGe911/Tv-apk-installer/releases/latest")
replace_once(store_source, "https://github.com/udhaykumarbala/cleanyourtv-app/releases.atom", "https://github.com/1StMaGe911/Tv-apk-installer/releases.atom")
replace_once(store_source, "https://github.com/udhaykumarbala/cleanyourtv-app/releases", "https://github.com/1StMaGe911/Tv-apk-installer/releases")

replace_once(convention, 'applicationId = "tv.cleanyour.app"', 'applicationId = "ru.tvapk.installer"')
replace_once(convention, "buildConfig = false\n            }\n            // Reproducibility", "buildConfig = true\n            }\n            // Reproducibility")
replace_once(convention, 'systemProperty(repoRootProperty, rootDir.absolutePath)', 'systemProperty(repoRootProperty, rootDir.resolve("vendor/cleanyourtv-app").absolutePath)')
replace_once(jvm_convention, 'systemProperty(repoRootProperty, rootDir.absolutePath)', 'systemProperty(repoRootProperty, rootDir.resolve("vendor/cleanyourtv-app").absolutePath)')
replace_once(convention, 'rootProject.file("benchmark/app-benchmark/AndroidManifest.xml")', 'rootProject.file("vendor/cleanyourtv-app/benchmark/app-benchmark/AndroidManifest.xml")')

spec_gradle = upstream / "core" / "spec" / "build.gradle.kts"
trust_gradle = upstream / "core" / "trust" / "build.gradle.kts"
replace_once(spec_gradle, 'rootProject.layout.projectDirectory.dir("third_party/cleanyourtv")', 'rootProject.layout.projectDirectory.dir("vendor/cleanyourtv-app/third_party/cleanyourtv")')
replace_once(trust_gradle, 'rootProject.layout.projectDirectory.file("android/platform/src/main/assets/bundled/$asset")', 'rootProject.layout.projectDirectory.file("vendor/cleanyourtv-app/android/platform/src/main/assets/bundled/$asset")')

# Preserve the legacy installer dependencies and explicitly authorize them in the upstream graph policy.
module_rules = upstream / "build-logic" / "convention" / "src" / "main" / "kotlin" / "tv" / "cleanyour" / "buildlogic" / "ModuleRules.kt"
replace_once(
    module_rules,
    '":app" to androidCommon,',
    '":app" to androidCommon + "dev.mobile:dadb" + "org.bouncycastle:bcprov-jdk18on",',
)

# Sign the debug variant with the existing TV APK Installer key when CI supplies it.
source = convention.read_text(encoding="utf-8")
insert = '''                getByName("debug") {
                    if (releaseConfig != null) signingConfig = releaseConfig
                }
'''
if insert not in source:
    app_plugin = source.index("class AndroidAppConventionPlugin")
    build_types = source.index("            buildTypes {", app_plugin)
    insert_at = build_types + len("            buildTypes {\n")
    source = source[:insert_at] + insert + source[insert_at:]
    convention.write_text(source, encoding="utf-8")

# Mount the existing TV APK Installer as a real Clean Your TV navigation destination.
# Keep the legacy screen in the same Activity so its ADB/file-picker state and permissions
# continue to use the host Activity; do not launch a second app or change applicationId.
app_root = upstream / "app" / "src" / "main" / "kotlin" / "tv" / "cleanyour" / "app" / "AppRoot.kt"
manage_screens = upstream / "feature" / "manage" / "src" / "main" / "kotlin" / "tv" / "cleanyour" / "feature" / "manage" / "ManageScreens.kt"

replace_once(
    app_root,
    "import tv.cleanyour.feature.setup.SetupFlow",
    "import tv.cleanyour.feature.setup.SetupFlow\nimport ru.tvapk.installer.TvInstallerApp",
)
replace_once(
    app_root,
    "    @Serializable data object AppSettings : Dest",
    "    @Serializable data object AppSettings : Dest\n    @Serializable data object LegacyInstaller : Dest",
)
replace_once(
    app_root,
    '        "appsettings" -> Dest.AppSettings',
    '        "appsettings" -> Dest.AppSettings\n        "legacyinstaller" -> Dest.LegacyInstaller',
)
replace_once(
    app_root,
    "                entry<Dest.Setup> { key ->",
    "                entry<Dest.LegacyInstaller> { TvInstallerApp() }\n                entry<Dest.Setup> { key ->",
)
replace_once(
    app_root,
    "                    ManageScreen(state, m::dispatch)",
    "                    ManageScreen(state, m::dispatch, onLegacyInstaller = { go(\"legacyinstaller\") })",
)
replace_once(
    manage_screens,
    "fun ManageScreen(state: ManageState, onIntent: (ManageIntent) -> Unit, modifier: Modifier = Modifier) {",
    "fun ManageScreen(state: ManageState, onIntent: (ManageIntent) -> Unit, modifier: Modifier = Modifier, onLegacyInstaller: () -> Unit = {}) {",
)
replace_once(
    manage_screens,
    "                ListRow(stringResource(R.string.manage_row_tvsettings), onClick = { onIntent(ManageIntent.Open(ManageSection.TV_SETTINGS)) })",
    "                ListRow(stringResource(R.string.manage_row_tvsettings), onClick = { onIntent(ManageIntent.Open(ManageSection.TV_SETTINGS)) })\n                ListDivider()\n                ListRow(\"TV APK Installer — дополнительные инструменты\", subtitle = \"ADB, очистка, резервные копии и управление лаунчером\", onClick = onLegacyInstaller)",
)

# Keep the original installer usable if the new Clean Your TV dependency graph fails at startup.
# This is a guarded fallback, not a silent success: the failure is retained and logged.
cyt_application = upstream / "app" / "src" / "main" / "kotlin" / "tv" / "cleanyour" / "app" / "CytApplication.kt"
cyt_activity = upstream / "app" / "src" / "main" / "kotlin" / "tv" / "cleanyour" / "app" / "MainActivity.kt"
replace_once(
    cyt_application,
    "    lateinit var graph: AppGraph\n        private set\n",
    "    lateinit var graph: AppGraph\n        private set\n    var startupFailure: Exception? = null\n        private set\n",
)
replace_once(
    cyt_application,
    """        CytLog.init(File(noBackupFilesDir, "logs"))
        graph = AppGraph(this)
        OperationService.Bus.channelName = getString(R.string.app_channel_setup_name)
        watchRuns()
        graph.sessionEnd.watch(graph.scope)
""",
    """        try {
            CytLog.init(File(noBackupFilesDir, "logs"))
            graph = AppGraph(this)
            OperationService.Bus.channelName = getString(R.string.app_channel_setup_name)
            watchRuns()
            graph.sessionEnd.watch(graph.scope)
        } catch (failure: Exception) {
            startupFailure = failure
            runCatching { CytLog.w("Clean Your TV startup failed; legacy installer remains available", failure) }
        }
""",
)
replace_once(
    cyt_activity,
    "import tv.cleanyour.ui.design.theme.CytTheme",
    "import tv.cleanyour.ui.design.theme.CytTheme\nimport ru.tvapk.installer.TvInstallerApp",
)
replace_once(
    cyt_activity,
    "        super.onCreate(savedInstanceState)\n        graph.credentialGate.attach(this)",
    "        super.onCreate(savedInstanceState)\n        if (application is CytApplication && (application as CytApplication).startupFailure != null) {\n            setContent { TvInstallerApp() }\n            return\n        }\n        graph.credentialGate.attach(this)",
)
replace_once(
    cyt_activity,
    "    override fun onStart() { super.onStart(); graph.foreground.set(true) }\n    override fun onStop() { graph.foreground.set(false); super.onStop() }",
    "    override fun onStart() { super.onStart(); if ((application as CytApplication).startupFailure == null) graph.foreground.set(true) }\n    override fun onStop() { if ((application as CytApplication).startupFailure == null) graph.foreground.set(false); super.onStop() }",
)
replace_once(
    cyt_activity,
    "        graph.credentialGate.attach(null)\n        super.onDestroy()",
    "        if ((application as CytApplication).startupFailure == null) graph.credentialGate.attach(null)\n        super.onDestroy()",
)
replace_once(
    cyt_activity,
    "        if (requestCode == AndroidCredentialGate.REQUEST_CONFIRM) graph.credentialGate.onConfirmCredentialResult(resultCode == RESULT_OK)",
    "        if (requestCode == AndroidCredentialGate.REQUEST_CONFIRM && (application as CytApplication).startupFailure == null) graph.credentialGate.onConfirmCredentialResult(resultCode == RESULT_OK)",
)

print("Clean Your TV integration patched: applicationId ru.tvapk.installer, version 0.9.138 (189).")
