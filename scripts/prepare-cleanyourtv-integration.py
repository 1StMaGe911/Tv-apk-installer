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

replace_once(app_gradle, "versionCode = 32", "versionCode = 193")
replace_once(app_gradle, 'versionName = "0.5.0-beta1"', 'versionName = "0.9.3"')
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
    '":app" to androidCommon + "dev.mobile:dadb" + "org.bouncycastle:bcprov-jdk18on" + "com.flyfishxu:kadb",',
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

# The Settings update action downloads our own full APK and launches Android's installer.
replace_once(
    app_root,
    "import ru.tvapk.installer.TvInstallerApp",
    "import ru.tvapk.installer.TvInstallerApp\nimport ru.tvapk.installer.CleanYourTvUpdater",
)
replace_once(
    app_root,
    'onOpenRelease = { graph.store.releasePage?.let { url -> scope.launch { onManageEffect(ManageEffect.OpenUrl(url)) } } },',
    'onOpenRelease = { CleanYourTvUpdater.download(context) },',
)

# Replace the single-tap release link with an explicit check / download / install flow.
replace_once(
    app_root,
    'onOpenRelease = { CleanYourTvUpdater.download(context) },',
    'onDownloadUpdate = { CleanYourTvUpdater.download(context) },\n                        onInstallUpdate = { CleanYourTvUpdater.installLatest(context) },',
)

# Progress polling is added once below, next to the settings state collection.
replace_once(
    manage_screens,
    'onOpenRelease: () -> Unit, modifier: Modifier = Modifier,',
    'onDownloadUpdate: () -> Unit, onInstallUpdate: () -> Unit, modifier: Modifier = Modifier,',
)
replace_once(
    manage_screens,
    'details: DetailsUi = DetailsUi(), onDetails: (share: Boolean) -> Unit = {}) {',
    'details: DetailsUi = DetailsUi(), onDetails: (share: Boolean) -> Unit = {}, updateDownloadProgress: Int? = null) {',
)
replace_once(
    manage_screens,
    'onClick = { if (update is UpdateCheck.Available) onOpenRelease() else onIntent(AppSettingsIntent.CheckUpdates) })',
    'onClick = { onIntent(AppSettingsIntent.CheckUpdates) })\n                if (update is UpdateCheck.Available) {\n                    PrimaryButton(when { updateDownloadProgress == null -> "Скачать обновление"; updateDownloadProgress >= 100 -> "Обновление загружено — 100%"; else -> "Скачивание обновления — $updateDownloadProgress%" }, onDownloadUpdate, Modifier.padding(top = 8.dp))\n                    if (updateDownloadProgress != null) ProgressBar((updateDownloadProgress.coerceIn(0, 100)) / 100f, Modifier.padding(top = 8.dp))\n                    TertiaryButton("Установить обновление", onInstallUpdate, Modifier.padding(top = 4.dp))\n                }',
)
manifest = upstream / "app" / "src" / "main" / "AndroidManifest.xml"
replace_once(
    manifest,
    '        <receiver\n            android:name="androidx.profileinstaller.ProfileInstallReceiver"',
    '        <receiver\n            android:name="ru.tvapk.installer.CleanYourTvUpdateReceiver"\n            android:exported="false">\n            <intent-filter>\n                <action android:name="android.intent.action.DOWNLOAD_COMPLETE" />\n            </intent-filter>\n            <intent-filter>\n                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />\n            </intent-filter>\n        </receiver>\n\n        <receiver\n            android:name="androidx.profileinstaller.ProfileInstallReceiver"',
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
    "    lateinit var graph: AppGraph\n        private set\n    var startupFailure: Throwable? = null\n        private set\n",
)
replace_once(
    cyt_application,
    """    override fun onCreate() {
        super.onCreate()
        CytLog.init(File(noBackupFilesDir, "logs"))
        graph = AppGraph(this)
        OperationService.Bus.channelName = getString(R.string.app_channel_setup_name)
        watchRuns()
        graph.sessionEnd.watch(graph.scope)
    }""",
    """    override fun onCreate() {
        super.onCreate()
        initializeGraph()
    }

    @Synchronized
    fun initializeGraph() {
        if (this::graph.isInitialized || startupFailure != null) return
        try {
            CytLog.init(File(noBackupFilesDir, "logs"))
            graph = AppGraph(this)
            OperationService.Bus.channelName = getString(R.string.app_channel_setup_name)
            watchRuns()
            graph.sessionEnd.watch(graph.scope)
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
            startupFailure = failure
            runCatching { CytLog.w("Clean Your TV initialization failed; original installer remains available", failure) }
        }
    }""",
)
replace_once(
    cyt_activity,
    "import tv.cleanyour.ui.design.theme.CytTheme",
    "import tv.cleanyour.ui.design.theme.CytTheme\nimport ru.tvapk.installer.TvInstallerApp\nimport ru.tvapk.installer.CleanYourTvUpdater",
)
replace_once(
    cyt_activity,
    "        super.onCreate(savedInstanceState)\n        graph.credentialGate.attach(this)",
    "        super.onCreate(savedInstanceState)\n        CleanYourTvUpdater.cleanupIfUpdated(this)\n        val cytApplication = application as CytApplication\n        cytApplication.initializeGraph()\n        if (cytApplication.startupFailure != null) {\n            setContent { TvInstallerApp() }\n            return\n        }\n        graph.credentialGate.attach(this)",
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

# Render the proven installer UI before optional background setup. WorkManager can be
# unavailable if a merged initializer is missing; that must not terminate the launcher.
legacy_activity = root / "app" / "src" / "main" / "java" / "ru" / "tvapk" / "installer" / "MainActivity.kt"
replace_once(
    legacy_activity,
    """        schedulePeriodicUpdateCheck(this)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            TvInstallerApp()
        }
""",
    """        // Draw the app first. Optional startup services must never prevent the UI from opening.
        setContent {
            TvInstallerApp()
        }
        try {
            schedulePeriodicUpdateCheck(applicationContext)
        } catch (failure: Exception) {
            android.util.Log.e("TVAPKStartup", "Periodic update scheduling failed", failure)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            try {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } catch (failure: Exception) {
                android.util.Log.e("TVAPKStartup", "Notification permission request failed", failure)
            }
        }
"""
)
print("Clean Your TV integration patched: applicationId ru.tvapk.installer, version 0.9.3 (193).")

# 0.9.88: Fail closed when an enabled first-run wizard is present but the exact
# device profile does not identify it as the wizard that may safely be switched off.
# RecoveryActivity can claim HOME after the stock launcher is disabled even when
# setupwraith was not reported as a HOME claimant during the initial snapshot.
home_switch_risk = upstream / "core" / "planner" / "src" / "main" / "kotlin" / "tv" / "cleanyour" / "core" / "planner" / "HomeSwitchRisk.kt"
replace_once(
    home_switch_risk,
    """            val wizard = snapshot.packages.keys.firstOrNull { it.value in Denylist.setupWizards && on(it) }
                ?: return if (homeWizards.isEmpty() && !firstRunClaims) null else Risk.WIZARD_STAYS_ON
            return if (wizardWifi == WizardWifi.OWNER && on(wizard)) Risk.WIZARD_WIFI else Risk.WIZARD_STAYS_ON""",
    """            val wizard = snapshot.packages.keys.firstOrNull { isWizard(it) && on(it) }
                ?: return if (homeWizards.isEmpty() && !firstRunClaims) null else Risk.WIZARD_STAYS_ON
            return if (wizard.value in Denylist.setupWizards && wizardWifi == WizardWifi.OWNER && on(wizard)) Risk.WIZARD_WIFI else Risk.WIZARD_STAYS_ON"""
)
replace_once(
    home_switch_risk,
    """        val allowed = profile.setupWraith?.takeIf { Denylist.isWizardAllowed(it, profile) }
        if (wizardWifi != WizardWifi.NOT_OWNER && allowed != null && on(allowed)) {""",
    """        val allowed = profile?.setupWraith?.takeIf { Denylist.isWizardAllowed(it, profile) }
        // Haier's RecoveryActivity can appear after the initial HOME snapshot, even on
        // profiles with HOME_TAKEOVER_SETUPWRAITH. The video reports this on a Haier TV,
        // so do not disable an active first-run wizard or switch HOME on this vendor path
        // until that exact firmware has been verified across restarts.
        val haier = facts.brand.equals("Haier", ignoreCase = true) || facts.manufacturer.equals("Haier", ignoreCase = true)
        val haierWizard = snapshot.packages.keys.firstOrNull { isWizard(it) && on(it) }
        if (haier && haierWizard != null) return Risk.WIZARD_STAYS_ON
        if (wizardWifi != WizardWifi.NOT_OWNER && allowed != null && on(allowed)) {"""
)
print("Haier/unknown setup-wizard HOME guard patch applied.")

home_switch_risk_test = upstream / "core" / "planner" / "src" / "test" / "kotlin" / "tv" / "cleanyour" / "core" / "planner" / "HomeSwitchRiskTest.kt"
replace_once(
    home_switch_risk_test,
    """    // (b) Google's own hardware: the switch costs casting after every restart.""",
    """    @Test fun haierWithAnUnlistedSetupWizardKeepsItsStockHome() {
        val haier = withFacts(facts("haier-tv", "Haier"))
        assertEquals(
            Risk.WIZARD_STAYS_ON,
            HomeSwitchRisk.of(haier.facts, PlannerFixtures.profile(), haier, WizardWifi.NOT_OWNER)
        )
        val cleanHaier = noWizard.copy(facts = haier.facts).let { it.copy(contentHash = SnapshotCodec.hash(it)) }
        assertNull(HomeSwitchRisk.of(cleanHaier.facts, PlannerFixtures.profile(), cleanHaier, WizardWifi.NOT_OWNER))
        val takeoverProfile = PlannerFixtures.profile(quirks = setOf(Quirk.HOME_TAKEOVER_SETUPWRAITH), setupWraith = wizard)
        assertEquals(
            Risk.WIZARD_STAYS_ON,
            HomeSwitchRisk.of(haier.facts, takeoverProfile, haier, WizardWifi.NOT_OWNER),
            "even a profile with the takeover quirk must not disable Haier's active recovery wizard before firmware verification"
        )
    }

    // (b) Google's own hardware: the switch costs casting after every restart."""
)




# Show each discovered TV's resolved IPv4 address as the picker subtitle.
# FoundTv.host is populated by NSD discovery and is already a literal LAN address.
connect_screens = upstream / "feature" / "connect" / "src" / "main" / "kotlin" / "tv" / "cleanyour" / "feature" / "connect" / "ConnectScreens.kt"
replace_once(
    connect_screens,
    "ChoiceOption(tv.host, title, subtitle = tv.model?.takeIf { it != name })",
    "ChoiceOption(tv.host, title, subtitle = tv.host)",
)

# Russian localization is generated inside the pinned upstream submodule at build time.
# The submodule is intentionally immutable from the parent repository's Contents API.
def write_russian_strings(relative: str, content: str) -> None:
    import re

    target = upstream / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    # Multiple reviewable localization blocks can target the same module.
    # Merge by resource type/name; later blocks intentionally replace duplicates.
    merged = {}
    pattern = re.compile(r'<(string|plurals|string-array|integer-array)\s+name="([^"]+)"[^>]*>[\s\S]*?</\1>')
    if target.exists():
        for match in pattern.finditer(target.read_text(encoding="utf-8")):
            merged[(match.group(1), match.group(2))] = match.group(0)
    for match in pattern.finditer(content):
        merged[(match.group(1), match.group(2))] = match.group(0)
    entries = "\n    ".join(merged.values())
    target.write_text('<?xml version="1.0" encoding="utf-8"?>\n<resources xmlns:tools="http://schemas.android.com/tools" xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2">\n    ' + entries + '\n</resources>\n', encoding="utf-8")

write_russian_strings(
    "feature/backup/src/main/res/values-ru/strings.xml",
    r'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="action_back">Назад</string>
    <string name="action_cancel">Отмена</string>
    <string name="action_ok">ОК</string>
    <string name="action_done">Готово</string>
    <string name="gate_title">Подтвердите, что это вы</string>
    <string name="gate_subtitle">Перед тем как менять настройки телевизора: {tv}</string>
    <string name="gate_negative">Отмена</string>
    <string name="gate_failed">Не удалось подтвердить вашу личность. Изменения не внесены.</string>
    <string name="restore_title">Точка восстановления</string>
    <string name="restore_current">Сохранена: {date}</string>
    <string name="restore_none">Точки восстановления пока нет. Она будет создана перед первым изменением.</string>
    <string name="restore_read_failed">Не удалось прочитать точку восстановления</string>
    <string name="restore_row_undo">Отменить все изменения</string>
    <string name="restore_row_undo_sub">Вернуть телевизор к состоянию на {date}</string>
    <string name="restore_row_save">Сохранить копию</string>
    <string name="restore_row_save_sub">Сохранить ZIP-архив в выбранное вами место.</string>
    <string name="restore_row_factory">Перед сбросом к заводским настройкам</string>
    <string name="restore_row_factory_sub">Включить мастер первоначальной настройки, чтобы после сброса телевизор смог нормально запуститься.</string>
    <string name="restore_preview_title">Телевизор будет возвращён к состоянию на {date}:</string>
    <string name="restore_preview_things">{count, plural, one {Будет включён # компонент} few {Будут включены # компонента} many {Будут включены # компонентов} other {Будут включены # компонента}}</string>
    <string name="restore_preview_home">Снова будет использоваться исходный главный экран телевизора ({name})</string>
    <string name="restore_preview_wizard">Мастер первоначальной настройки будет включён</string>
    <string name="restore_preview_helper">Помощник кнопки «Домой» будет выключен</string>
    <string name="restore_preview_screensaver">Вернётся стандартная заставка телевизора с часами</string>
    <string name="restore_preview_keyboard">Вернётся стандартная клавиатура телевизора</string>
    <string name="restore_launcher_title">Новый главный экран</string>
    <string name="restore_launcher_keep">Оставить установленным как обычное приложение</string>
    <string name="restore_launcher_remove">Удалить с телевизора</string>
    <string name="restore_launcher_remove_note">При удалении также будут удалены ответы, которые карточки модулей получили на телевизоре.</string>
    <string name="restore_preview_button">Вернуть всё как было</string>
    <string name="restore_confirm_title">Вернуть всё как было?</string>
    <string name="restore_confirm_body">Реклама и отслеживание тоже снова включатся. При желании очистку можно будет запустить повторно.</string>
    <string name="restore_confirm_button">Вернуть всё как было</string>
    <string name="restore_working_title">Восстанавливаем прежние настройки</string>
    <string name="restore_working_things">{total, plural, other {Включаем компоненты: {done} из #...}}</string>
    <string name="restore_working_home">Возвращаем исходный главный экран телевизора...</string>
    <string name="restore_working_wizard">Включаем мастер первоначальной настройки...</string>
    <string name="restore_done_title">Всё восстановлено</string>
    <string name="restore_done_body">Телевизор возвращён к состоянию на {date}. Точка восстановления сохранена на случай, если вы передумаете.</string>
    <string name="restore_clean_again">Запустить настройку заново</string>
    <string name="restore_factory_title">Перед сбросом к заводским настройкам</string>
    <string name="restore_factory_body">Сброс к заводским настройкам возвращает мастер первоначальной настройки телевизора. Мы включим его заранее, чтобы после сброса телевизор смог нормально запуститься.</string>
    <string name="restore_factory_button">Включить мастер настройки</string>
    <string name="restore_factory_working">Включаем мастер первоначальной настройки...</string>
    <string name="restore_factory_done">Мастер первоначальной настройки включён. Теперь можно сбросить телевизор.</string>
    <string name="restore_export_saved">Копия точки восстановления сохранена.</string>
    <string name="restore_export_failed">Не удалось сохранить копию. Попробуйте выбрать другое место.</string>
</resources>'''
)


write_russian_strings(
    "feature/connect/src/main/res/values-ru/strings.xml",
    r'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="action_back">Назад</string>
    <string name="action_cancel">Отмена</string>
    <string name="action_ok">ОК</string>
    <string name="welcome_overline" translatable="false">Очистка ТВ</string>
    <string name="welcome_title">Приведём ваш телевизор в порядок</string>
    <string name="welcome_body">Отключите лишнее, сохраните нужные приложения и получите спокойный главный экран. Это займёт около пяти минут. Все изменения можно отменить.</string>
    <string name="welcome_hero_cd">Главный экран телевизора с рекламными рядами превращается в чистый экран с четырьмя приложениями внизу</string>
    <string name="welcome_looking">Ищем телевизоры в вашей сети Wi-Fi...</string>
    <string name="welcome_found_one">Найдено устройство: {name} ({room})</string>
    <string name="welcome_found_one_noroom">Найдено устройство: {name}</string>
    <string name="welcome_found_many">{count, plural, one {Найден # телевизор} few {Найдено # телевизора} many {Найдено # телевизоров} other {Найдено # телевизора}}</string>
    <string name="welcome_none_yet">Телевизор пока не найден. Продолжайте, мы будем искать.</string>
    <string name="welcome_start">Начать</string>
    <string name="welcome_returning">Я уже настраивал телевизор</string>
    <string name="picker_title">Какой телевизор?</string>
    <string name="picker_body">Не уверены? На выбранном телевизоре скоро появится сообщение.</string>
    <string name="picker_row">{name}, {room} · {platform}</string>
    <string name="picker_row_noroom">{name} · {platform}</string>
    <string name="picker_name_with_suffix">{name} ...{suffix}</string>
    <string name="picker_platform_android_tv">Android TV {version}</string>
    <string name="picker_platform_google_tv">Google TV {version}</string>
    <string name="picker_platform_fire_tv">Fire TV</string>
    <string name="picker_footer">Телефон и телевизор должны быть подключены к одной сети Wi-Fi.</string>
    <string name="picker_primary">Выбрать {name}</string>
    <string name="picker_tertiary">Ввести адрес телевизора</string>
    <string name="door_title">Разрешите подключение телефона</string>
    <string name="door_body">Возьмите пульт телевизора. Это займёт около минуты — самая сложная часть настройки.</string>
    <string name="door_step_cd">Шаг {current} из {total}</string>
    <string name="door_next">Далее</string>
    <string name="door_menu_names_vary">Названия пунктов меню на вашем телевизоре могут немного отличаться.</string>
    <string name="door_same_wifi">Телефон и телевизор должны быть в одной сети Wi-Fi.</string>
    <string name="door_waiting">Ожидаем ответ телевизора...</string>
    <string name="door_connecting">Подключаемся...</string>
    <string name="door_still_waiting">Всё ещё ждём. Вы включили отладку по USB? На некоторых телевизорах нужно также включить сетевую отладку.</string>
    <string name="door_still_waiting_show_me">Показать инструкцию</string>
    <string name="door_change_model">Это не {model}? Изменить модель</string>
    <string name="door_change_model_generic">Это другой телевизор? Изменить модель</string>
    <string name="door_not_this_tv">Не этот телевизор? Ввести его адрес</string>
    <string name="door_brand_prompt">Выберите марку</string>
    <string name="door_brand_xiaomi">Xiaomi</string>
    <string name="door_brand_tcl">TCL</string>
    <string name="door_brand_sony">Sony</string>
    <string name="door_brand_hisense">Hisense</string>
    <string name="door_brand_philips">Philips</string>
    <string name="door_brand_google">Google</string>
    <string name="door_brand_amazon">Amazon</string>
    <string name="door_brand_other">Другая</string>
    <string name="door_tcl_menus_prompt">На каком языке меню телевизора?</string>
    <string name="door_tcl_menus_global">Английский или другой привычный язык</string>
    <string name="door_tcl_menus_chinese">Китайский</string>
    <string name="door_connect_failed_title">Телевизор отвечает, но телефону не удалось подключиться</string>
    <string name="door_connect_failed_body">Не удалось установить соединение ({kind}). Пока этот экран открыт, мы продолжим попытки.</string>
    <string name="door_connect_failed_hint">Если ADB с компьютера работает, а приложение не может подключиться к телевизору, возможно, мешают настройки телефона: ограничение сети для приложения, VPN, частный DNS или мобильный интернет. Временно отключите их и попробуйте снова.</string>
    <string name="door_connect_refused_title">На телевизоре выключена отладка</string>
    <string name="door_connect_refused_body">Телевизор отклонил подключение ({kind}), потому что отладка выключена. Мы специально отключаем её после настройки, поэтому это нормально для телевизора, который уже настраивали.</string>
    <string name="door_connect_refused_hint">Чтобы включить её снова, откройте на телевизоре Настройки → Настройки устройства или Система → Для разработчиков и включите отладку по USB. Пока этот экран открыт, мы продолжим попытки.</string>
    <string name="door_copy_error">Скопировать ошибку</string>
    <string name="door_error_copied">Скопировано. Вставьте текст в обращение о проблеме.</string>
    <string name="connect_report">Сообщить о проблеме</string>
    <string name="door_unknown_note">Мы ещё не настраивали именно эту модель телевизора. Обычно шаги такие же.</string>
    <string name="family_atv_step1">На телевизоре откройте Настройки (значок шестерёнки справа вверху), затем Настройки устройства → Об устройстве. Нажмите ОК на пункте «Сборка» семь раз, пока не появится сообщение «Вы стали разработчиком».</string>
    <string name="family_atv_step2">Вернитесь на один экран назад. Откройте Для разработчиков и включите Отладку по USB. Если есть пункты «Сетевая отладка» или «Отладка ADB», включите и их.</string>
    <string name="family_atv_step3">Оставьте телевизор на этом экране. Телефон подключится автоматически.</string>
    <string name="family_atv_extra_shield">На NVIDIA Shield важнее всего включить «Сетевую отладку».</string>
    <string name="family_gtv_step1">На телевизоре откройте Настройки → Система → Об устройстве. Нажмите ОК на пункте «Сборка ОС Android TV» семь раз, пока не появится сообщение «Вы стали разработчиком».</string>
    <string name="family_gtv_step2">Вернитесь на один экран назад. Откройте Система → Для разработчиков, включите Отладку по USB, а затем Беспроводную отладку.</string>
    <string name="family_gtv_step3">Оставьте телевизор на этом экране. Телефон подключится автоматически.</string>
    <string name="family_fire_step1">На телевизоре откройте Настройки → Мой Fire TV → О системе. Выберите название устройства семь раз, пока не появится сообщение «Вы стали разработчиком».</string>
    <string name="family_fire_step2">Вернитесь на один экран назад. Откройте Для разработчиков и включите Отладку ADB.</string>
    <string name="family_fire_step3">Оставьте телевизор на этом экране. Телефон подключится автоматически.</string>
    <string name="family_tcl_step1">На телевизоре откройте Настройки → Об устройстве → 本机信息, затем нажмите на пульте Вверх, Вниз, Влево, Вправо.</string>
    <string name="family_tcl_step2">Появится небольшое окно «ADB» с сетевым адресом. Не закрывайте его.</string>
    <string name="family_tcl_step3">Оставьте телевизор на этом экране. Телефон подключится автоматически.</string>
    <string name="family_tcl_extra_gtv_step1">На Google TV откройте Система → Об устройстве и нажмите ОК на пункте «Сборка ОС Android TV» семь раз.</string>
    <string name="family_tcl_extra_gtv">На Google TV также включите Беспроводную отладку.</string>
    <string name="family_unknown_step1">На телевизоре откройте Настройки (значок шестерёнки справа вверху), затем Настройки устройства → Об устройстве. Нажмите ОК на пункте «Сборка» семь раз, пока не появится сообщение «Вы стали разработчиком».</string>
    <string name="family_unknown_step2">Вернитесь на один экран назад. Откройте Для разработчиков и включите Отладку по USB. Если есть пункты «Сетевая отладка» или «Отладка ADB», включите и их.</string>
    <string name="family_unknown_step3">Оставьте телевизор на этом экране. Телефон подключится автоматически.</string>
    <string name="banner_fire">На Fire TV можно отключить рекламу и отслеживание, но останется собственный главный экран. Очистка всё равно будет выполнена.</string>
    <string name="banner_tcl">На этом телевизоре останется собственный главный экран (TCL не разрешает заменять его). Остальные функции очистки будут доступны.</string>
    <string name="banner_mibox_usb">Пока отладка включена, эта приставка отключает USB-порт. USB-колонка или накопитель временно перестанут работать. После настройки мы снова выключим отладку.</string>
    <string name="allow_title">Посмотрите на телевизор</string>
    <string name="allow_body">Телевизор спрашивает, доверять ли этому телефону. Установите флажок «Всегда разрешать», затем выберите ОК.</string>
    <string name="allow_body_emphasis_always">«Всегда разрешать»</string>
    <string name="allow_body_emphasis_ok">ОК.</string>
    <string name="allow_code_label">Последние символы кода на телевизоре</string>
    <string name="allow_code_caption">Это код вашего телефона.</string>
    <string name="allow_waiting">Ожидаем подтверждения на телевизоре...</string>
    <string name="allow_still_waiting">Всё ещё ждём нажатия ОК на телевизоре.</string>
    <string name="allow_trusted">Доверие подтверждено</string>
    <string name="allow_dont_see">Не вижу это окно на телевизоре</string>
    <string name="allow_art_cd">Окно разрешения отладки по USB на телевизоре. Отпечаток заканчивается на {tail}.</string>
    <string name="notseen_title">Не вижу это окно на телевизоре</string>
    <string name="notseen_hidden">Окно могло оказаться за другим экраном. Нажмите Домой на пульте, затем один раз Назад. Если окно не появилось, мы повторно отправим запрос телевизору.</string>
    <string name="notseen_cancelled">Если кто-то нажал «Отмена», выключите и снова включите отладку по USB на телевизоре, затем нажмите «Запросить снова».</string>
    <string name="notseen_greyed">Если пункт «Всегда разрешать» неактивен, достаточно нажать ОК. В следующий раз мы снова запросим разрешение.</string>
    <string name="notseen_ask_again">Запросить снова</string>
    <string name="notseen_asking">Повторно запрашиваем разрешение...</string>
    <string name="notseen_restart">Телевизор не принимает подключение телефона. Такое бывает на некоторых моделях, если отладку включили до полной загрузки. Перезагрузите телевизор, снова включите отладку и вернитесь сюда.</string>
    <string name="notseen_restarted">Телевизор перезагружен</string>
    <string name="connected_title">Подключено к {tv}</string>
    <string name="connected_cd">Подключено</string>
    <string name="identity_question">Это ваш телевизор?</string>
    <string name="identity_detail">{name}, серийный номер оканчивается на {serial}</string>
    <string name="identity_body">Убедитесь, что изображение совпадает с тем, что сейчас показано на вашем телевизоре.</string>
    <string name="identity_screenshot_cd">То, что сейчас отображается на телевизоре</string>
    <string name="identity_yes">Да, это мой телевизор</string>
    <string name="identity_no">Нет</string>
    <string name="pairing_title">Для этого телевизора нужен код сопряжения</string>
    <string name="pairing_body">В этой версии пока нельзя вводить код сопряжения; такая возможность появится в следующей версии. Если у вас есть компьютер, один раз выполните сопряжение через adb и команду adb tcpip 5555, затем вернитесь сюда — телефон продолжит настройку.</string>
    <string name="pairing_show_steps">Показать инструкцию для компьютера</string>
    <string name="pairing_steps_title">Однократное сопряжение через компьютер</string>
    <string name="pairing_steps_intro">На телевизоре откройте Для разработчиков → Беспроводная отладка → Сопряжение устройства с помощью кода. Затем по очереди выполните на компьютере три команды.</string>
    <string name="pairing_steps_cmd_pair" translatable="false">adb pair ADDRESS:PAIR_PORT</string>
    <string name="pairing_steps_cmd_connect" translatable="false">adb connect ADDRESS:CONNECT_PORT</string>
    <string name="pairing_steps_cmd_tcpip" translatable="false">adb -s ADDRESS:CONNECT_PORT tcpip 5555</string>
    <string name="pairing_steps_note">Используйте адрес, порты и шестизначный код, показанные на телевизоре. После последней команды вернитесь в приложение. После перезагрузки телевизора повторите две последние команды с портом, который он покажет. Повторно сопрягать устройства не нужно.</string>
    <string name="stop_tv_not_found_title">Телевизор пока не найден</string>
    <string name="stop_tv_not_found_body">Убедитесь, что телевизор включён и подключён к той же сети Wi-Fi, что и телефон. После выхода из спящего режима телевизору может потребоваться минута, чтобы появиться в списке.</string>
    <string name="stop_tv_not_found_primary">Продолжить поиск</string>
    <string name="stop_tv_not_found_secondary">Ввести адрес телевизора</string>
    <string name="stop_tv_not_found_tertiary">Помощь</string>
    <string name="stop_tv_not_found_help_title">Почему телевизор может не отображаться</string>
    <string name="stop_tv_not_found_help_isolation">Гостевая сеть Wi-Fi или роутер, изолирующий устройства друг от друга. Найдите в настройках роутера «Изоляция точек доступа» или «Изоляция клиентов» и отключите её.</string>
    <string name="stop_tv_not_found_help_ethernet">Телевизор подключён кабелем Ethernet, а телефон — по Wi-Fi. Обычно это работает, но некоторые роутеры разделяют эти сети. Ввод адреса телевизора поможет обойти ограничение.</string>
    <string name="stop_tv_not_found_help_mesh">В Mesh-системе может быть отдельная гостевая сеть. Подключите телефон к основной сети.</string>
    <string name="stop_wrong_network_title">Телефон и телевизор подключены к разным сетям</string>
    <string name="stop_wrong_network_body">В последний раз телевизор был обнаружен в другой сети. Подключитесь к той же сети Wi-Fi, что и телевизор, и мы продолжим.</string>
    <string name="stop_wrong_network_primary">Открыть настройки Wi-Fi</string>
    <string name="stop_wrong_network_secondary">Я подключён к нужной сети, повторить поиск</string>
    <string name="stop_wrong_network_off_title">Телефон не подключён к Wi-Fi</string>
    <string name="stop_wrong_network_off_body">Wi-Fi выключен. Подключиться к телевизору можно только через ту же сеть Wi-Fi.</string>
    <string name="stop_identity_changed_title">По этому адресу ответил другой телевизор</string>
    <string name="stop_identity_changed_body">По этому адресу находится не тот телевизор, который мы настраивали как {name}. Его адрес мог измениться или адрес заняло другое устройство. Мы остановились до внесения каких-либо изменений.</string>
    <string name="stop_identity_changed_primary">Найти телевизор заново</string>
    <string name="stop_identity_changed_secondary">Назад</string>
    <string name="stop_tv_not_supported_title">Пока нельзя настроить этот телевизор с телефона</string>
    <string name="stop_tv_not_supported_body">Отладочное подключение этого телевизора не поддерживает все необходимые команды для безопасной настройки. Мы остановились, ничего не изменив на телевизоре.</string>
    <string name="stop_tv_not_supported_primary">Назад</string>
</resources>'''
)


write_russian_strings(
    "feature/modules/src/main/res/values-ru/strings.xml",
    r'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="action_back">Назад</string>
    <string name="action_cancel">Отмена</string>
    <string name="action_ok">ОК</string>
    <string name="action_done">Готово</string>
    <string name="action_undo">Отменить</string>
    <string name="gate_title">Подтвердите, что это вы</string>
    <string name="gate_subtitle">Перед изменением настроек телевизора: {tv}</string>
    <string name="gate_negative">Отмена</string>
    <string name="gate_failed">Не удалось подтвердить вашу личность. Изменения не внесены.</string>
    <string name="modules_title">Карточки и модули</string>
    <string name="modules_state_shown">Показано</string>
    <string name="modules_state_hidden">Скрыто</string>
    <string name="modules_state_over_limit">Превышен лимит</string>
    <string name="modules_over_limit_note">На главном экране можно разместить не более восьми карточек.</string>
    <string name="modules_remove">Удалить с телевизора</string>
    <string name="modules_show_card">Показать карточку</string>
    <string name="modules_cards_off_note">Карточки отключены на главном экране телевизора. При показе карточки телевизор попросит подтвердить действие.</string>
    <string name="modules_update">Обновить</string>
    <string name="modules_add">Добавить</string>
    <string name="modules_update_available">Доступна версия {version}</string>
    <string name="modules_empty">Карточек пока нет. Добавьте карточку, чтобы разместить её на главном экране.</string>
    <string name="modules_normal_app_note">На этом телевизоре модуль отображается как обычное приложение.</string>
    <string name="modules_remove_title">Удалить {name} с телевизора {tv}?</string>
    <string name="modules_remove_body">Приложение будет удалено с телевизора. Главный экран и остальные карточки останутся без изменений.</string>
    <string name="modules_remove_confirm">Удалить</string>
    <string name="modules_removed">{name} удалён.</string>
    <string name="modules_state_cd">{name}: {state}</string>
    <string name="modules_update_consent">В версии {version} приложения {name} появились дополнительные разрешения: {permissions}. Разрешить их?</string>
    <string name="modules_update_skip">Пропустить эту версию</string>
    <string name="addmodule_title">Добавить на телевизор {tv}</string>
    <string name="addmodule_search_hint">Поиск</string>
    <string name="addmodule_search_cd">Поиск модулей</string>
    <string name="addmodule_open_source">Открытый исходный код</string>
    <string name="addmodule_size">{size, plural, other {# МБ}}</string>
    <string name="addmodule_tier_community">Сообщество</string>
    <string name="addmodule_tier_verified">Проверено специалистом</string>
    <string name="addmodule_tier_paused">Приостановлено</string>
    <string name="addmodule_loading">Загружаем список...</string>
    <string name="addmodule_empty">По вашему запросу ничего не найдено.</string>
    <string name="addmodule_refresh">Обновить список</string>
    <string name="addmodule_footer">Все модули созданы участниками сообщества и автоматически проверяются.</string>
    <string name="addmodule_footer_link">Кто создал модули</string>
    <string name="addmodule_tiers_title">Кто создал модули</string>
    <string name="addmodule_tiers_community">Сообщество: модуль создан независимым автором и автоматически проверен на соответствие опубликованным данным.</string>
    <string name="addmodule_tiers_verified">Проверено специалистом: указанная версия прошла проверку конкретным специалистом.</string>
    <string name="addmodule_tiers_paused">Приостановлено: сопровождающие временно прекратили распространение модуля.</string>
    <string name="module_what_title">ЧТО МОДУЛЬ МОЖЕТ ДЕЛАТЬ НА ТЕЛЕВИЗОРЕ</string>
    <string name="module_who_title">КТО СОЗДАЛ МОДУЛЬ</string>
    <string name="module_runs_background">Работает в фоне: {reason}</string>
    <string name="module_no_background">Фоновые службы отсутствуют</string>
    <string name="module_perm_can_cd">{statement}. Это действие разрешено.</string>
    <string name="module_perm_cannot_cd">{statement}. Это действие не выполняется.</string>
    <string name="module_maker">{handle} на GitHub</string>
    <string name="module_version_line">Версия {version}, выпущена {date}</string>
    <string name="module_checks">{count, plural, =0 {Автоматическая проверка: файл совпадает с опубликованным автором и не запрашивает разрешений.} one {Автоматическая проверка: файл совпадает с опубликованным автором и запрашивает только указанное выше разрешение.} few {Автоматическая проверка: файл совпадает с опубликованным автором и запрашивает только # указанных выше разрешения.} many {Автоматическая проверка: файл совпадает с опубликованным автором и запрашивает только # указанных выше разрешений.} other {Автоматическая проверка: файл совпадает с опубликованным автором и запрашивает только # указанных выше разрешения.}}</string>
    <string name="module_not_reviewed">Автоматическая проверка пройдена. Специалистом ещё не проверено.</string>
    <string name="module_key_line">Ключ {key}, такой же, как на телевизоре</string>
    <string name="module_source">Исходный код</string>
    <string name="module_report">Сообщить о проблеме</string>
    <string name="module_hero_badge">Карточка на вашем телевизоре</string>
    <string name="module_add">Добавить на телевизор {tv}</string>
    <string name="module_outside_store">Дополнения загружаются из раздела релизов GitHub их авторов, а не из магазина приложений. Они не собираются и не проверяются F-Droid.</string>
    <string name="module_add_turns_cards_on">Добавление включает карточки на главном экране телевизора. Перед показом карточки телевизор запросит подтверждение.</string>
    <string name="module_paused_reason">Приостановлено сопровождающими: {reason}</string>
    <string name="module_install_adding">Добавляем {name}...</string>
    <string name="module_install_downloading">{size, plural, other {Загрузка (# МБ)...}}</string>
    <string name="module_install_checking">Проверяем соответствие копии автора...</string>
    <string name="module_install_installing">Устанавливаем на телевизор...</string>
    <string name="module_install_asking">Просим телевизор показать запрос подтверждения...</string>
    <string name="module_install_failed">Не удалось установить {name} на телевизор. Другие настройки не изменены.</string>
    <string name="cardprompt_title">Посмотрите на телевизор</string>
    <string name="cardprompt_art_keep">Оставить скрытой</string>
    <string name="cardprompt_art_cd">Телевизор спрашивает: {name} хочет добавить карточку на главный экран. Ключ {key}. Доступны варианты «Оставить скрытой» и «Показать карточку».</string>
    <string name="cardprompt_body">Телевизор запрашивает подтверждение, чтобы приложение не могло добавить себя на главный экран без вашего разрешения.</string>
    <string name="cardprompt_instruction">Выберите на пульте «Показать карточку». Ключ на телевизоре должен совпадать с этим: {key}.</string>
    <string name="cardprompt_waiting">Ожидаем ваш ответ на телевизоре...</string>
    <string name="cardprompt_later">Можно сделать позже: удерживайте ОК на пункте «Настройки» телевизора, чтобы найти карточки модулей.</string>
    <string name="cardprompt_shown">{name} добавлен на главный экран.</string>
    <string name="cardprompt_hidden">{name} установлен, но скрыт. Он по-прежнему доступен как обычное приложение. В любой момент можно показать карточку в разделе «Карточки и модули».</string>
    <string name="cardprompt_signer_changed">Новая версия {name} подписана другим ключом. Это необычно, поэтому телевизор специально запрашивает подтверждение ещё раз. Если вы не ожидали обновления, выберите «Оставить скрытой».</string>
    <string name="index_failure_bad_signature">Не удалось подтвердить подлинность списка модулей, поэтому мы не стали его использовать. Телевизор не изменён.</string>
    <string name="index_failure_unknown_key">Список модулей подписан неизвестным ключом, поэтому мы не стали его использовать. Телевизор не изменён.</string>
    <string name="index_failure_revoked_key">Список модулей подписан отозванным ключом, поэтому мы не стали его использовать. Телевизор не изменён.</string>
    <string name="index_failure_bad_keys_file">Список доверенных ключей не прошёл проверку, поэтому список модулей не использован. Телевизор не изменён.</string>
    <string name="index_failure_expired">Список модулей устарел. Подключитесь к интернету и обновите его.</string>
    <string name="index_failure_rollback">Полученный список модулей старее уже сохранённого. Мы оставили более новую версию.</string>
    <string name="index_failure_malformed">Не удалось прочитать список модулей. Попробуйте немного позже.</string>
    <string name="index_stale_warning">Список модулей может быть устаревшим. Обновите его при подключении к интернету.</string>
    <string name="fetch_failure_offline">Сейчас у телефона нет подключения к интернету. Подключитесь и попробуйте снова.</string>
    <string name="fetch_failure_host_not_allowed">Загрузка выполняется с неизвестного сайта, поэтому мы остановились. Ничего не установлено.</string>
    <string name="fetch_failure_http_error">Сайт загрузки вернул ошибку ({code}). Попробуйте немного позже.</string>
    <string name="fetch_failure_too_large">Файл загрузки оказался больше ожидаемого. Мы остановились, ничего не установив.</string>
    <string name="fetch_failure_hash_mismatch">Загруженный файл не совпадает с копией автора, поэтому мы не стали его устанавливать.</string>
    <string name="fetch_failure_size_mismatch">Размер файла не совпадает с указанным в списке. Мы остановились, ничего не установив.</string>
    <string name="fetch_failure_signer_mismatch">Приложение подписано не тем автором, который указан в списке. Мы не стали его устанавливать.</string>
    <string name="fetch_failure_try_again">Повторить</string>
</resources>'''
)


write_russian_strings(
    "app/src/main/res/values-ru/strings.xml",
    r'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name" translatable="false">Очистка ТВ</string>
    <string name="app_channel_setup_name">Настройка телевизора</string>
    <string name="app_channel_setup_description">Показывает ход настройки телевизора с телефона и сообщает, когда нужно посмотреть на экран телевизора.</string>
    <string name="app_restore_read_failed">Не удалось прочитать точку восстановления</string>
</resources>'''
)

write_russian_strings(
    "ui/design/src/main/res/values-ru/strings.xml",
    r'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="cyt_back">Назад</string>
    <string name="cyt_close">Закрыть</string>
    <string name="cyt_help">Помощь</string>
    <string name="cyt_step_counter">%1$d из %2$d</string>
    <string name="cyt_step_counter_extra">%1$d из %2$d · %3$s</string>
    <string name="cyt_status_connected">Подключено</string>
    <string name="cyt_status_waiting">Ожидание</string>
    <string name="cyt_status_working">Выполняется</string>
    <string name="cyt_status_debugging_off">Отладка выключена</string>
    <string name="cyt_status_offline">Не в сети</string>
    <string name="cyt_pill_off">Выключено</string>
    <string name="cyt_pill_kept">Оставлено</string>
    <string name="cyt_action_keep">Оставить</string>
    <string name="cyt_action_switch_off">Выключить</string>
    <string name="cyt_why">Почему?</string>
    <string name="cyt_close_group">Свернуть группу</string>
    <plurals name="cyt_show_more">
        <item quantity="one">Показать ещё %d</item>
        <item quantity="few">Показать ещё %d</item>
        <item quantity="many">Показать ещё %d</item>
        <item quantity="other">Показать ещё %d</item>
    </plurals>
    <string name="cyt_group_count">%1$s · %2$d из %3$d</string>
    <string name="cyt_count_of">%1$d из %2$d</string>
    <string name="cyt_undo">Отменить</string>
    <string name="cyt_obscured_hint">Другое приложение перекрывает экран, поэтому нажатие не сработало. Приостановите или закройте его и попробуйте снова.</string>
    <string name="cyt_countdown_60">Осталась одна минута. Подтвердите действие или вернитесь назад.</string>
    <string name="cyt_countdown_30">Осталось 30 секунд.</string>
    <string name="cyt_countdown_10">Осталось 10 секунд.</string>
    <string name="cyt_countdown_left">Осталось %1$s</string>
    <plurals name="cyt_earlier_steps">
        <item quantity="one">%d предыдущий шаг</item>
        <item quantity="few">%d предыдущих шага</item>
        <item quantity="many">%d предыдущих шагов</item>
        <item quantity="other">%d предыдущего шага</item>
    </plurals>
    <string name="cyt_state_done">Готово</string>
    <string name="cyt_state_doing">Выполняется</string>
    <string name="cyt_state_upcoming">Следующий шаг</string>
    <string name="cyt_state_skipped">Пропущено</string>
    <string name="cyt_percent">%1$d%%</string>
    <string name="cyt_live_from_tv">Текущие данные с телевизора</string>
    <string name="cyt_copy_as_commands">Скопировать как команды</string>
    <string name="cyt_mirror_allow_title">Разрешить отладку по USB?</string>
    <string name="cyt_mirror_allow_body">Отпечаток RSA-ключа компьютера:</string>
    <string name="cyt_mirror_allow_always">Всегда разрешать этому компьютеру</string>
    <string name="cyt_mirror_cancel" tools:ignore="ButtonCase">ОТМЕНА</string>
    <string name="cyt_mirror_ok">ОК</string>
    <string name="cyt_mirror_card_title">Новая карточка от %1$s</string>
    <string name="cyt_mirror_card_body">%1$s может показывать карточку на главном экране. Если оставить её скрытой, обычная плитка приложения не изменится.</string>
    <string name="cyt_mirror_card_later">Это можно изменить позже: удерживайте ОК на пункте «Настройки».</string>
    <string name="cyt_mirror_card_key">%1$s, ключ %2$s</string>
    <string name="cyt_mirror_keep_hidden">Оставить скрытой</string>
    <string name="cyt_mirror_show_card">Показать карточку</string>
    <string name="cyt_dock_row_dock">Панель приложений</string>
    <string name="cyt_dock_row_n">Ряд %1$d</string>
    <string name="cyt_dock_item_dock_cd">%1$s, на панели приложений, место %2$d</string>
    <string name="cyt_dock_item_row_cd">%1$s, в ряду %2$d, место %3$d</string>
    <string name="cyt_dock_remove_cd">Убрать %1$s</string>
    <string name="cyt_dock_add_dock_cd">Добавить %1$s на панель приложений</string>
    <string name="cyt_dock_add_row_cd">Добавить %1$s в ряд %2$d</string>
    <string name="cyt_dock_item_out_cd">%1$s, не на панели и не в её рядах</string>
    <string name="cyt_dock_action_up">Переместить вверх</string>
    <string name="cyt_dock_action_down">Переместить вниз</string>
    <string name="cyt_dock_action_to_dock">Переместить на панель приложений</string>
    <string name="cyt_dock_action_to_row">Переместить в ряд %1$d</string>
    <string name="cyt_dock_action_remove">Убрать</string>
    <string name="cyt_dock_add_row">Добавить ряд под панелью</string>
    <string name="cyt_dock_remove_row">Удалить ряд</string>
    <string name="cyt_dock_remove_row_cd">Удалить ряд %1$d</string>
    <string name="cyt_dock_row_removed">Ряд %1$d удалён. Его приложения снова доступны в разделе «Другие приложения».</string>
    <string name="cyt_dock_row_empty">Нажмите на приложение ниже, чтобы добавить его в этот ряд.</string>
    <string name="cyt_dock_full">На панели помещается %1$d приложений. Уберите одно или добавьте ряд ниже.</string>
    <string name="cyt_dock_row_full">Эти ряды заполнены. Уберите приложение или добавьте ещё один ряд.</string>
    <string name="cyt_dock_all_full">Панель и два ряда под ней заполнены. Уберите приложение, чтобы добавить другое.</string>
    <string name="cyt_dock_gaps">Пустые места на панели останутся пустыми и на телевизоре.</string>
    <string name="cyt_dock_gaps_rows">В пустых местах рядов под панелью будут показаны названия остальных приложений.</string>
    <string name="cyt_dock_others">Другие приложения на телевизоре</string>
    <string name="cyt_look_title">Внешний вид</string>
    <string name="cyt_look_columns_title">Приложений в ряду</string>
    <string name="cyt_look_columns_help">Чем меньше приложений, тем крупнее плитки.</string>
    <string name="cyt_look_columns_biggest">При значении 4 плитки будут максимально крупными.</string>
    <string name="cyt_look_columns_end_big">Крупнее</string>
    <string name="cyt_look_columns_end_many">Больше приложений</string>
    <string name="cyt_look_picture_title">Изображение</string>
    <string name="cyt_picture_diwali">Дивали</string>
    <string name="cyt_picture_aurora">Северное сияние</string>
    <string name="cyt_picture_mist">Туман</string>
    <string name="cyt_picture_navy">Тёмно-синий</string>
    <string name="cyt_picture_autumn_lake">Осеннее озеро</string>
    <string name="cyt_picture_curtains">Шторы</string>
    <string name="cyt_picture_mountains">Горы</string>
    <string name="cyt_picture_lamp">Свет лампы</string>
    <string name="cyt_picture_neon">Неон</string>
    <string name="cyt_picture_cobalt">Кобальт</string>
    <string name="cyt_look_picture_none">Без изображения</string>
    <string name="cyt_look_picture_choose">Выбрать фотографию</string>
    <string name="cyt_look_picture_new">Новое фото</string>
    <string name="cyt_photo_title">Ваша фотография</string>
    <string name="cyt_photo_body">Изменяйте масштаб и положение, чтобы выбрать изображение для телевизора. Не размещайте лица под часами и панелью приложений.</string>
    <string name="cyt_photo_frame_cd">Так будет выглядеть фотография на телевизоре — поверх неё будут часы, кнопки и панель приложений.</string>
    <string name="cyt_photo_dim">Затемнение для лучшей видимости часов и названий: {dim}%</string>
    <string name="cyt_photo_dim_cd">Затемнение для лучшей видимости часов и названий</string>
    <string name="cyt_photo_use">Использовать этот фрагмент</string>
    <string name="cyt_photo_unreadable">Не удалось прочитать фотографию. Попробуйте выбрать другую.</string>
    <string name="cyt_photo_too_big">Не удалось уменьшить фотографию до размера, подходящего для телевизора. Выберите другую.</string>
    <string name="cyt_cancel">Отмена</string>
    <string name="cyt_look_more_cd">Дополнительные параметры</string>
    <string name="cyt_look_advanced_cd">Расширенные настройки</string>
    <string name="cyt_look_back_look">Назад к внешнему виду</string>
    <string name="cyt_look_back_more">Назад к дополнительным параметрам</string>
    <string name="cyt_look_preview_drawing">Создаём предварительный просмотр...</string>
    <string name="cyt_look_preview_bigger_cd">Увеличить предварительный просмотр</string>
    <string name="cyt_look_preview_smaller_cd">Уменьшить предварительный просмотр</string>
    <string name="cyt_look_style_title">Стиль</string>
    <string name="cyt_look_style_classic">Классический</string>
    <string name="cyt_look_style_classic_desc">Тёмная панель, крупное название и мягкое свечение.</string>
    <string name="cyt_look_style_glass">Стекло</string>
    <string name="cyt_look_style_glass_desc">Матовая полупрозрачная панель и подсветка цветом каждого приложения.</string>
    <string name="cyt_look_style_spotlight">В центре внимания</string>
    <string name="cyt_look_style_spotlight_desc">Большой баннер выбранного приложения, как на новых Smart TV.</string>
    <string name="cyt_look_style_all_apps">Все приложения</string>
    <string name="cyt_look_style_all_apps_desc">Все приложения в одной сетке без панели.</string>
    <string name="cyt_look_style_calm">Спокойный</string>
    <string name="cyt_look_style_calm_desc">Без свечения и крупных надписей. Только приложения и ваше изображение.</string>
    <string name="cyt_tv_good_morning">Доброе утро</string>
    <string name="cyt_tv_good_afternoon">Добрый день</string>
    <string name="cyt_tv_good_evening">Добрый вечер</string>
    <string name="cyt_tv_good_night">Доброй ночи</string>
    <string name="cyt_tv_am">ДО ПОЛУДНЯ</string>
    <string name="cyt_tv_pm">ПОСЛЕ ПОЛУДНЯ</string>
    <string name="cyt_tv_inputs">Входы</string>
    <string name="cyt_tv_wifi">Домашний Wi-Fi</string>
    <string name="cyt_tv_settings">Настройки</string>
</resources>'''
)


write_russian_strings(
    "feature/launcher/src/main/res/values-ru/strings.xml",
    r'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="action_back">Назад</string>
    <string name="action_cancel">Отмена</string>
    <string name="action_done">Готово</string>
    <string name="action_undo">Отменить</string>
    <string name="gate_title">Подтвердите, что это вы</string>
    <string name="gate_subtitle">Перед изменением настроек телевизора: {tv}</string>
    <string name="gate_negative">Отмена</string>
    <string name="gate_failed">Не удалось подтвердить вашу личность. Изменения не внесены.</string>
    <string name="home_title">Главный экран</string>
    <string name="home_row_dock">Панель приложений</string>
    <string name="home_row_dock_rows">{names}, а ниже ещё {count, plural, one {# приложение} few {# приложения} many {# приложений} other {# приложения}}</string>
    <string name="home_row_look">Внешний вид</string>
    <string name="home_tcl_remote_keys">На TCL с Google TV кнопки Настройки (шестерёнка), Netflix и YouTube на пульте не работают с новым главным экраном. Чтобы вернуть их, выберите «Использовать собственный главный экран телевизора».</string>
    <string name="home_google_tv_settings_key">На Google TV кнопка Настройки (шестерёнка) на пульте открывает собственную панель настроек Google TV, где на многих телевизорах также находятся настройки изображения и звука текущего входа. С новым главным экраном эта кнопка может перестать работать. Чтобы вернуть её, выберите «Использовать собственный главный экран телевизора».</string>
    <string name="home_row_stock">Использовать собственный главный экран телевизора</string>
    <string name="home_row_stock_note">Вернёт главный экран телевизора. Наш экран останется установленным, поэтому вы сможете снова переключиться.</string>
    <string name="home_drift_banner">Настройки главного экрана телевизора изменились после последней настройки.</string>
    <string name="home_drift_restore">Отправить мои настройки снова</string>
    <string name="home_applying">Обновляем главный экран телевизора...</string>
    <string name="home_snackbar_done">Готово.</string>
    <string name="home_not_on_tv_lead">{tv} использует собственный главный экран. Панель приложений и оформление относятся к новому экрану, который на этом телевизоре не установлен.</string>
    <string name="home_not_on_tv_refuses">{brand} разрешает использовать только собственный главный экран, поэтому настройка не установила наш. Очистка телевизора работает и без него.</string>
    <string name="home_not_on_tv_old_android">Для нового главного экрана требуется Android 8 или новее, поэтому настройка не установила его. Очистка работает и без него.</string>
    <string name="home_not_on_tv_unknown_home">Мы недостаточно хорошо знаем собственный главный экран этого телевизора, чтобы безопасно его заменить. Поэтому настройка оставила всё как есть.</string>
    <string name="home_not_on_tv_wizard_wifi">Данные Wi-Fi этого телевизора сохранены мастером первоначальной настройки. Для нового главного экрана мастер нужно отключить, но тогда сеть Wi-Fi будет забыта. Если оставить мастер включённым, телевизор может работать с ошибками. Поэтому главный экран не меняли.</string>
    <string name="home_not_on_tv_wizard_wifi_unlisted">Данные Wi-Fi сохранены мастером настройки, а мы пока недостаточно хорошо знаем эту модель, чтобы безопасно его отключить. Если оставить мастер включённым, новый главный экран может вызвать сбой. Поэтому экран не меняли.</string>
    <string name="home_not_on_tv_wizard_wifi_unknown">Не удалось проверить, кто сохранил данные Wi-Fi. Для нового главного экрана мастер настройки нужно отключить, но сеть может быть забыта. Поэтому главный экран оставили без изменений.</string>
    <string name="home_not_on_tv_casting">На этом устройстве Google для нового главного экрана нужно отключить мастер настройки. После этого трансляция с телефонов перестаёт работать после каждой перезагрузки. Поэтому главный экран не меняли.</string>
    <string name="home_not_on_tv_boot_loop">На телевизорах этой серии мастер настройки запускается при каждой перезагрузке. Если отключить собственный главный экран, телевизор может перестать загружаться. Поэтому экран не меняли.</string>
    <string name="home_not_on_tv_remote_buttons">На этом телевизоре кнопки приложений на пульте работают только через собственный главный экран. Поэтому настройка оставила его без изменений.</string>
    <string name="home_not_on_tv_wizard_stays_on">Мы недостаточно хорошо знаем эту модель, чтобы отключить мастер настройки. Если оставить его включённым, а собственный главный экран выключить, телевизор может дать сбой. Поэтому экран не меняли.</string>
    <string name="home_not_on_tv_home_changed">Главный экран этого телевизора уже был изменён другим приложением или ранее. Настройка оставила его как есть и не устанавливала наш экран. Очистка работает и без него.</string>
    <string name="home_not_on_tv_earlier">При предыдущей настройке главный экран оставили без изменений, но приложение обновилось и теперь, возможно, сможет установить новый экран.</string>
    <string name="home_not_on_tv_other_home">На телевизоре уже настроено другое приложение главного экрана, поэтому настройка не стала его менять.</string>
    <string name="home_not_on_tv_next_other_home">Чтобы использовать новый экран, удалите то приложение и запустите настройку снова. Или сообщите о проблеме.</string>
    <string name="home_not_on_tv_vendor_home">На этом телевизоре системная служба тоже обрабатывает кнопку Домой. На одном из таких телевизоров после замены главного экрана произошёл сбой загрузки, поэтому настройка оставила всё как есть.</string>
    <string name="home_not_on_tv_patchwall_feeds">На телевизоре включён PatchWall, а Mi TV+ или его веб-содержимое выключено. На похожем устройстве после замены главного экрана произошёл сбой, поэтому настройка оставила всё как есть.</string>
    <string name="home_not_on_tv_next_patchwall_feeds">Чтобы установить новый экран, снова запустите настройку и отключите PatchWall в разделе «Настроить».</string>
    <string name="home_not_on_tv_none">Новый главный экран сейчас не установлен на этом телевизоре.</string>
    <string name="home_not_on_tv_next_wifi">Чтобы изменить это, удалите все сохранённые на телевизоре сети Wi-Fi и подключитесь к своей сети заново. Подключение кабелем Ethernet ничего не меняет. Затем запустите настройку снова.</string>
    <string name="home_not_on_tv_next_check">Повторный запуск настройки ещё раз проверит возможность замены.</string>
    <string name="home_not_on_tv_next_home_changed">Если хотите установить новый экран, назначьте собственный экран телевизора главным в настройках ТВ, затем запустите настройку снова.</string>
    <string name="home_not_on_tv_next_setup">Запустите настройку снова, чтобы установить его.</string>
    <string name="home_not_on_tv_next_list">Для этого нужна база, подготовленная для вашей модели. Функция «Помочь добавить телевизор» отправит нам отчёт. Мы составляем базу по таким отчётам и сообщим в обращении, когда появится подходящий вариант.</string>
    <string name="home_run_setup_again">Запустить настройку снова</string>
    <string name="home_help_add_tv">Помочь добавить телевизор</string>
    <string name="home_report">Сообщить о проблеме</string>
    <string name="home_tv_now_title">Так теперь выглядит ваш телевизор</string>
    <string name="home_tv_now_cd">Текущее изображение на телевизоре</string>
    <string name="home_tv_now_again">Что-то не так? Отправить ещё раз</string>
    <string name="home_installed_lead">Новый главный экран установлен, но пока не используется. Сейчас работает собственный экран телевизора.</string>
    <string name="home_installed_body">Повторная попытка запускает настройку ещё раз и вместе с вами проверяет главный экран на телевизоре.</string>
    <string name="home_installed_rows_note">Появится, когда новый главный экран начнёт использоваться.</string>
    <string name="home_try_switch_again">Повторить переключение</string>
    <string name="home_by_design">Это не ошибка, а предусмотренное решение: настройка выбрала его с учётом особенностей телевизора. Причина и возможные варианты указаны выше. Если телевизор изменился, всё равно можно сообщить о проблеме.</string>
    <string name="home_tv_list">Посмотреть, на каких телевизорах доступен новый главный экран</string>
    <string name="home_report_sent">Отчёт об этом телевизоре отправлен {date}. Мы сообщим в обращении, когда появятся новости. Его можно отправить повторно.</string>
    <string name="home_fire_tv_name" translatable="false">Fire TV</string>
    <string name="home_tv_changes_note">Настройки, изменённые непосредственно на телевизоре, будут заменены настройками, отправленными отсюда.</string>
    <string name="home_row_look_summary_picture">{style}, изображение «{picture}»</string>
    <string name="home_row_look_summary_photo">{style}, ваша фотография</string>
    <string name="home_row_look_summary_none">{style}, без изображения</string>
    <string name="home_row_look_columns">{count, plural, one {# приложение в ряду} few {# приложения в ряду} many {# приложений в ряду} other {# приложения в ряду}}</string>
    <string name="home_row_look_sending">Отправляем...</string>
    <string name="home_look_failed">Не удалось передать оформление на телевизор.</string>
    <string name="home_look_failed_open">Открыть оформление</string>
    <string name="home_row_update">Обновить главный экран</string>
    <string name="home_row_update_note">Доступна версия {version}. Панель приложений и оформление сохранятся.</string>
    <string name="home_update_preview_title">Обновить главный экран?</string>
    <string name="home_update_preview_body">Установим версию {version} поверх текущей. Панель приложений, оформление и ответы на запросы карточек сохранятся. Если этот экран сейчас используется, телевизор один раз перезагрузится.</string>
    <string name="home_update_confirm">Обновить</string>
    <string name="home_update_done">Главный экран обновлён до версии {version}.</string>
    <string name="home_stock_preview_title">Использовать собственный главный экран телевизора?</string>
    <string name="home_stock_preview_body">Включим собственный главный экран телевизора и назначим его основным. Новый экран останется установленным.</string>
    <string name="home_stock_confirm">Использовать экран телевизора</string>
    <string name="home_stock_working">Возвращаем собственный главный экран телевизора...</string>
    <string name="home_stock_done">Собственный главный экран телевизора восстановлен. Наш экран по-прежнему установлен.</string>
    <string name="theme_default">По умолчанию</string>
    <string name="home_look_ended_banner">Срок действия изображения «{picture}» истёк. Вернуть на телевизор предыдущее изображение?</string>
    <string name="home_look_ended_restore">Восстановить</string>
    <string name="dock_title">Ваша панель приложений</string>
    <string name="dock_body">Внизу можно разместить до {count, spellout} приложений. Перетаскивайте приложение за точки, чтобы изменить порядок.</string>
    <string name="dock_waiting">{count, plural, one {# приложение ожидает} few {# приложения ожидают} many {# приложений ожидают} other {# приложения ожидают}} в сетке, пока вы не выберете больше приложений в одном ряду в разделе «Внешний вид».</string>
    <string name="helper_card_title">Помощник кнопки Домой</string>
    <string name="helper_card_on">Кнопка Домой открывает новый главный экран через небольшой вспомогательный модуль. Собственный экран телевизора остаётся включённым, поэтому трансляция продолжает работать.</string>
    <string name="helper_card_off_by_tv">Телевизор отключил помощник (после перезагрузки или работы приложения-очистителя). Запустите настройку снова, чтобы включить его.</string>
    <string name="helper_card_no_redirect">Помощник включён, но в оформлении на телевизоре не указан главный экран, поэтому он ничего не делает. Отключите помощник или запустите настройку снова.</string>
    <string name="helper_card_redirect_only">Новый главный экран настроен для работы с помощником, но сам помощник выключен. Запустите настройку снова, чтобы включить его.</string>
    <string name="helper_card_escape">На телевизоре его также можно отключить: Настройки → Специальные возможности → Кнопка Домой.</string>
    <string name="helper_card_apps_only">Google TV продолжает работать в фоне. Режим «Только приложения» делает интерфейс спокойнее, но также отключает Поиск и Ассистент. Их можно включить обратно в настройках телевизора.</string>
    <string name="helper_card_switch_off">Отключить</string>
</resources>'''
)

write_russian_strings(
    "tv/curtain/src/main/res/values-ru/strings.xml",
    r'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Очистка ТВ</string>
    <string name="caption">Очистка ТВ</string>
    <string name="hint">Не выключайте телевизор. Подробности отображаются на телефоне.</string>
    <string name="chip">Точка восстановления</string>
    <string name="all_clean">Очистка завершена.</string>
    <string name="stage_scan">Сохраняем точку восстановления...</string>
    <string name="stage_restore_saved">Точка восстановления сохранена</string>
    <string name="stage_off">Отключаем элементы: %1$d из %2$d...</string>
    <string name="stage_off_group">%1$s: %2$d из %3$d...</string>
    <string name="stage_group_done">Отключено: %1$d</string>
    <string name="stage_group_done_group">Отключено: %1$s (%2$d)</string>
    <string name="stage_install">Устанавливаем новый главный экран</string>
    <string name="stage_installed">Новый главный экран установлен</string>
    <string name="stage_finale">Переключаем кнопку Домой</string>
    <string name="group_adverts">Реклама</string>
    <string name="group_tracking">Отслеживание</string>
    <string name="group_nobody">Ненужные приложения</string>
    <string name="group_brand">Дополнения производителя: %1$s</string>
    <string name="group_brand_unknown">Дополнения производителя</string>
    <string name="group_shop">Магазин и демонстрационный режим</string>
    <string name="group_other">Другие приложения</string>
</resources>'''
)


write_russian_strings(
    "feature/setup/src/main/res/values-ru/strings.xml",
    r'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="action_back">Назад</string>
    <string name="action_cancel">Отмена</string>
    <string name="action_ok">ОК</string>
    <string name="action_done">Готово</string>
    <string name="action_undo">Отменить</string>
    <string name="gate_title">Подтвердите, что это вы</string>
    <string name="gate_subtitle">Перед изменением настроек телевизора: {tv}</string>
    <string name="gate_negative">Отмена</string>
    <string name="gate_failed">Не удалось подтвердить вашу личность. Изменения не внесены.</string>
    <string name="notify_channel_name">Настройка телевизора</string>
    <string name="notify_channel_description">Показывает ход настройки телевизора с телефона и сообщает, когда нужно посмотреть на экран телевизора.</string>
    <string name="notify_progress" formatted="false">Настройка {tv}, {percent}%</string>
    <string name="notify_look">Посмотрите на телевизор. Нажмите, чтобы ответить.</string>
    <string name="notify_rationale">Мы сообщим, когда потребуется посмотреть на телевизор. Разрешите уведомления, чтобы получать их, даже когда телефон в кармане.</string>
    <string name="notify_rationale_allow">Разрешить уведомления</string>
    <string name="notify_rationale_skip">Не сейчас</string>
    <string name="probing_line">Читаем информацию с телевизора...</string>
    <string name="probing_progress_cd">Читаем информацию с телевизора</string>
    <string name="stop_probe_failed_title">Не удалось прочитать информацию с телевизора</string>
    <string name="stop_probe_failed_body">Телевизор перестал отвечать во время проверки. Ничего не изменено. Убедитесь, что он включён, и попробуйте снова.</string>
    <string name="stop_probe_failed_primary">Повторить</string>
    <string name="stop_probe_failed_secondary">Назад</string>
    <string name="stop_probe_failed_tertiary">Показать подробности</string>
    <string name="stop_plan_refused_title">Не удалось составить безопасный план для этого телевизора</string>
    <string name="stop_plan_refused_body">Телевизор ответил, но обнаруженные данные не позволяют составить план, безопасность которого мы можем гарантировать. Поэтому мы остановились до внесения изменений. Ничего не изменено. Нажмите «Сообщить о проблеме», чтобы отправить нам причину и помочь исправить ситуацию.</string>
    <string name="found_title">{count, plural, =0 {Нечего отключать. Хотите установить новый главный экран?} one {Найден один элемент для отключения} few {Найдено # элемента для отключения} many {Найдено # элементов для отключения} other {Найдено # элемента для отключения}}</string>
    <string name="found_headline">{count, plural, =0 {Нечего отключать. Хотите установить новый главный экран?} one {элемент для отключения} few {элемента для отключения} many {элементов для отключения} other {элемента для отключения}}</string>
    <string name="found_overline">Найдено</string>
    <string name="found_zero_body">На этом телевизоре уже всё в порядке.</string>
    <string name="found_title_zero_kept">Нечего отключать</string>
    <string name="found_zero_kept_body">На телевизоре уже всё в порядке, и он продолжает использовать собственный главный экран. Больше ничего делать не нужно.</string>
    <string name="found_zero_kept_unknown_body">Мы не нашли на этом телевизоре ничего, в чём уверены, а собственный главный экран остаётся включённым. Пока ничего делать не нужно. Мы составляем список по отчётам пользователей и сообщим в вашем обращении, когда появится подходящий вариант.</string>
    <string name="found_switch_home">Заменить главный экран</string>
    <string name="found_group_ads">Реклама и рекламные ряды</string>
    <string name="found_group_tracking">Отслеживание и аналитика</string>
    <string name="found_group_nobody">Ненужные приложения</string>
    <string name="found_group_brand">Дополнения {brand}</string>
    <string name="found_group_shop">Магазин и демонстрационный режим</string>
    <string name="found_group_needed">Необходимо для нового главного экрана</string>
    <string name="found_group_count_cd">{group}: {count, plural, one {# элемент} few {# элемента} many {# элементов} other {# элемента}}</string>
    <string name="found_see_list">Посмотреть список</string>
    <string name="found_keep_intro">Мы сохраняем всё, чем вы пользуетесь:</string>
    <string name="found_keep_paragraph">Мы сохраняем всё, чем вы пользуетесь: {apps}, {fixed}.</string>
    <string name="found_keep_paragraph_no_apps">Мы сохраняем всё, чем вы пользуетесь: {fixed}.</string>
    <string name="found_keep_fixed">Эфирное ТВ и HDMI, пульт, клавиатуру, Chromecast, голосового помощника и обновления системы</string>
    <string name="found_keep_fixed_fire">Эфирное ТВ и HDMI, пульт, клавиатуру, голосового помощника и обновления системы</string>
    <string name="found_home_row">Главный экран: {dock}</string>
    <string name="found_home_row_rows">Главный экран: {dock}, а ниже ещё {count, plural, one {# приложение} few {# приложения} many {# приложений} other {# приложения}}</string>
    <string name="found_home_row_cd">Панель приложений главного экрана: {dock}</string>
    <string name="found_home_change">Изменить</string>
    <string name="found_home_row_empty">Ваш новый главный экран</string>
    <string name="found_safety">Сначала будет сохранена точка восстановления, чтобы при необходимости вернуть всё обратно.</string>
    <string name="found_make_clean">Очистить телевизор</string>
    <string name="found_customise">Настроить</string>
    <string name="found_replaces_launcher">Заменит приложение главного экрана, уже установленное на телевизоре. Его оформление, порядок приложений, настройки и ответы на запросы карточек будут сброшены.</string>
    <string name="found_tcl_remote_keys">На TCL с Google TV кнопки Настройки (шестерёнка), Netflix и YouTube на пульте перестанут работать с новым главным экраном. Чтобы вернуть их, выберите «Использовать собственный главный экран телевизора» на странице главного экрана.</string>
    <string name="found_google_tv_settings_key">На Google TV кнопка Настройки (шестерёнка) открывает панель настроек Google TV, где на многих моделях находятся настройки изображения и звука текущего входа. С новым главным экраном она может перестать работать. Чтобы вернуть её, выберите «Использовать собственный главный экран телевизора» на странице главного экрана.</string>
    <string name="found_no_lock">На телефоне нет блокировки экрана, поэтому мы не можем дополнительно проверить, что это вы.</string>
    <string name="found_unknown_title">{count, plural, =0 {Мы не нашли ничего, в чём уверены} one {Найден один элемент, в котором мы уверены} few {Найдено # элемента, в которых мы уверены} many {Найдено # элементов, в которых мы уверены} other {Найдено # элемента, в которых мы уверены}}</string>
    <string name="found_unknown_headline">{count, plural, =0 {Мы не нашли ничего, в чём уверены} one {элемент, в котором мы уверены} few {элемента, в которых мы уверены} many {элементов, в которых мы уверены} other {элемента, в которых мы уверены}}</string>
    <string name="found_unknown_body">Мы ещё не работали с этой точной моделью телевизора, поэтому действуем осторожно. Дополнительные элементы можно будет отключить, когда появится список для этой модели. Функция «Помочь добавить телевизор» поможет его составить.</string>
    <string name="found_help_add_tv">Помочь добавить телевизор</string>
    <string name="found_report_sent">Отчёт об этом телевизоре отправлен {date}. Мы сообщим в вашем обращении, когда появятся новости. Вы можете отправить его повторно.</string>
    <string name="fire_tv_name" translatable="false">Fire TV</string>
    <string name="kept_outcome">Телевизор продолжает использовать собственный главный экран.</string>
    <string name="helper_outcome">Кнопка Домой открывает новый главный экран.</string>
    <string name="helper_outcome_body">Собственный экран телевизора остаётся включённым, поэтому трансляция продолжает работать.</string>
    <string name="kept_outcome_short">Главный экран: собственный экран ТВ</string>
    <string name="kept_why">Почему?</string>
    <string name="kept_why_title">Почему телевизор сохраняет собственный главный экран</string>
    <string name="kept_what_changes_title">Что может это изменить</string>
    <string name="kept_tv_list">Посмотреть, на каких телевизорах доступен новый главный экран</string>
    <string name="kept_clean_up_note">Мы всё равно отключаем рекламу, отслеживание и ненужные фоновые приложения. Реклама, которую показывает собственный главный экран телевизора, останется.</string>
    <string name="kept_reason_tv_refuses">Телевизоры {brand} разрешают использовать только собственный главный экран.</string>
    <string name="kept_reason_fire_tv">Fire TV разрешает использовать только собственный главный экран.</string>
    <string name="kept_reason_old_android">Для нового главного экрана требуется Android 8 или новее.</string>
    <string name="kept_reason_unknown_home">Мы недостаточно хорошо знаем главный экран этой модели, чтобы безопасно его заменить.</string>
    <string name="kept_reason_wizard_wifi">Данные Wi-Fi сохранены мастером настройки, а для замены экрана мастер нужно отключить.</string>
    <string name="kept_reason_wizard_wifi_unknown">Не удалось проверить, кто сохранил данные Wi-Fi.</string>
    <string name="kept_reason_casting">После замены экрана трансляция с телефонов перестанет работать после каждой перезагрузки.</string>
    <string name="kept_reason_boot_loop">Замена может помешать запуску телевизоров этой серии.</string>
    <string name="kept_reason_remote_buttons">Кнопки приложений на пульте работают только с собственным главным экраном телевизора.</string>
    <string name="kept_reason_wizard_stays_on">Мастер настройки останется включённым, что может вызвать сбой телевизора.</string>
    <string name="kept_reason_home_changed">Главный экран уже был изменён, поэтому мы оставляем его как есть.</string>
    <string name="kept_reason_other_home">На телевизоре настроено другое приложение главного экрана.</string>
    <string name="kept_reason_vendor_home">Кнопку Домой также обрабатывает системная служба.</string>
    <string name="kept_reason_patchwall_feeds">PatchWall включён, а Mi TV+ выключен. Чтобы установить новый экран, отключите PatchWall.</string>
    <string name="kept_reason_none">Сейчас заменить главный экран на этом телевизоре нельзя.</string>
    <string name="customise_title">Настроить</string>
    <string name="customise_nothing_deleted">Ничего не удаляется: «выключить» означает перевести в неактивное состояние.</string>
    <string name="customise_show_fewer">Показать меньше</string>
    <string name="customise_group_voice">Голосовой помощник</string>
    <string name="customise_group_cast">Chromecast</string>
    <string name="customise_group_updates">Обновления системы</string>
    <string name="customise_group_streaming">Неиспользуемые приложения для просмотра видео</string>
    <string name="customise_group_brand_features">Функции {brand}</string>
    <string name="customise_updates_note">Оставляем включёнными, чтобы телевизор получал исправления безопасности. Отключение также убирает напоминания об обновлениях.</string>
    <string name="customise_wizard_name">Мастер первоначальной настройки</string>
    <string name="customise_wizard_off">Отключается вместе с заменой главного экрана. Включится снова, если отменить изменения.</string>
    <string name="customise_own_home_off">Собственный главный экран телевизора. Отключается только после успешной работы нового экрана.</string>
    <string name="customise_switch_part">Часть замены главного экрана; включится снова при отмене изменений.</string>
    <string name="customise_wizard_kept">Остаётся включённым. Телевизор сохраняет собственный главный экран и мастер настройки.</string>
    <string name="customise_wizard_helper">Остаётся включённым. Помощник кнопки Домой работает вместе с собственным главным экраном и мастером настройки.</string>
    <string name="customise_own_home_helper">Собственный главный экран телевизора остаётся включённым, а кнопка Домой открывает новый экран.</string>
    <string name="customise_own_home_kept">Собственный главный экран телевизора остаётся включённым, поскольку эта модель должна его сохранить.</string>
    <string name="customise_protected_title">Мы сохраняем эти элементы</string>
    <string name="customise_protected_pill">Защищено</string>
    <string name="customise_protected_why_title">Почему мы это сохраняем</string>
    <string name="customise_group_home">Главный экран</string>
    <string name="customise_dock_row">Панель приложений: {names}</string>
    <string name="customise_dock_row_empty">Панель приложений</string>
    <string name="customise_look_row">Оформление: {theme}</string>
    <string name="customise_look_photo">{style}, ваша фотография</string>
    <string name="customise_look_picture">{style}, изображение «{picture}»</string>
    <string name="customise_group_tv">На телевизоре</string>
    <string name="customise_show_on_tv">Показывать ход очистки на телевизоре</string>
    <string name="customise_show_on_tv_note">Телевизор показывает каждый шаг во время очистки.</string>
    <string name="customise_group_afterwards">После завершения</string>
    <string name="customise_keep_debugging">Оставить отладку включённой</string>
    <string name="customise_keep_debugging_warning">Другие устройства в вашей сети Wi-Fi смогут запрашивать подключение.</string>
    <string name="customise_group_commands">Показывать команды</string>
    <string name="customise_commands_intro">Все команды, которые будут выполнены, в правильном порядке.</string>
    <string name="customise_copy_commands">Скопировать команды</string>
    <string name="customise_commands_copied">Скопировано</string>
    <string name="customise_snackbar_kept">{name} останется включённым.</string>
    <string name="customise_snackbar_off">{name} будет отключено.</string>
    <string name="customise_snackbar_off_home_back">{name} будет отключено. Новый главный экран снова включён в план.</string>
    <string name="customise_snackbar_kept_home_own">{name} останется включённым. Телевизор сохранит собственный главный экран.</string>
    <string name="theme_default">По умолчанию</string>
    <string name="theme_aurora">Северное сияние</string>
    <string name="theme_six_columns">Шесть столбцов</string>
    <string name="theme_card_cd">{name}. {description}</string>
    <string name="theme_card_selected_cd">{name}, выбрано. {description}</string>
    <string name="dock_title">Ваша панель приложений</string>
    <string name="dock_body">Внизу можно разместить до {count, spellout} приложений. Перетаскивайте приложение за точки, чтобы изменить порядок.</string>
    <string name="working_title">Очищаем телевизор</string>
    <string name="working_step_restore_active">Сохраняем точку восстановления...</string>
    <string name="working_step_restore_done">Точка восстановления сохранена</string>
    <string name="working_group_ads">Реклама</string>
    <string name="working_group_tracking">Отслеживание</string>
    <string name="working_group_nobody">Ненужные приложения</string>
    <string name="working_group_brand">Дополнения {brand}</string>
    <string name="working_group_shop">Магазин и демонстрационный режим</string>
    <string name="working_step_group_active">{total, plural, other {{group}: {done} из #...}}</string>
    <string name="working_step_group_done">{count, plural, other {{group}: отключено (#)}}</string>
    <string name="working_step_install_active">Устанавливаем новый главный экран</string>
    <string name="working_step_install_done">Новый главный экран установлен</string>
    <string name="working_step_replace_active">Заменяем приложение главного экрана</string>
    <string name="working_step_replace_done">Приложение главного экрана заменено</string>
    <string name="working_step_home_active">Переключаем кнопку Домой</string>
    <string name="working_step_home_done">Кнопка Домой переключена</string>
    <string name="working_step_done_cd">{step}, выполнено</string>
    <string name="working_thumbnail_caption">Изображение с телевизора в реальном времени</string>
    <string name="working_thumbnail_cd">Изображение с телевизора в реальном времени</string>
    <string name="working_keep_on">Не выключайте телевизор. Телефон можно отложить — мы сообщим, когда потребуется посмотреть на экран.</string>
    <string name="working_keep_on_no_check">Не выключайте телевизор. Телефон можно отложить — мы сообщим, когда всё будет готово.</string>
    <string name="working_stop">Остановить и отменить изменения</string>
    <string name="working_putting_back">Возвращаем собственный главный экран телевизора...</string>
    <string name="working_stopping">Останавливаем работу и отменяем внесённые изменения...</string>
    <string name="check_overline">Посмотрите на телевизор</string>
    <string name="check_title">Вы видите новый экран?</string>
    <string name="check_dock_sentence">Новый главный экран с панелью приложений внизу: {dock}.</string>
    <string name="check_dock_sentence_empty">Новый главный экран.</string>
    <string name="check_press_home">Один раз нажмите только кнопку Домой на пульте. Не открывайте другие разделы телевизора.</string>
    <string name="check_live_ok">Кнопка Домой открывает новый экран</string>
    <string name="check_live_pending_cd">Ещё не проверено</string>
    <string name="check_live_done_cd">Проверено</string>
    <string name="check_countdown_text">Если вы не ответите, мы автоматически вернём собственный главный экран телевизора. Не беспокойтесь.</string>
    <string name="check_thumbnail_note">Если телевизор не виден, изображение выше показывает, что сейчас отображается на его экране.</string>
    <string name="check_no_thumbnail_note">Не удалось получить изображение. Посмотрите непосредственно на телевизор.</string>
    <string name="check_yes">Да, я вижу новый экран</string>
    <string name="check_no">Нет, вернуть прежний</string>
    <string name="stop_show_details">Показать подробности</string>
    <string name="stop_report">Сообщить о проблеме</string>
    <string name="stop_home_wait_hint">Подождите несколько секунд.</string>
    <string name="stop_home_setupwraith_title">Собственный главный экран телевизора восстановлен</string>
    <string name="stop_home_setupwraith_secondary">Оставить собственный главный экран телевизора</string>
    <string name="stop_home_setupwraith_wifi_body">Данные Wi-Fi сохранены мастером настройки телевизора, поэтому его отключение удалит сохранённую сеть. Чтобы повторить переключение, сначала удалите сохранённую сеть и подключитесь к ней заново.</string>
    <string name="stop_home_setupwraith_wifi_secondary">Я сделал это, попробовать снова</string>
    <string name="stop_home_setupwraith_wifi_unknown_body">Не удалось проверить, кто сохранил данные Wi-Fi. Поэтому мы оставили мастер настройки включённым, чтобы не потерять подключение.</string>
    <string name="stop_home_other_launcher_title">Собственный главный экран телевизора восстановлен</string>
    <string name="stop_home_other_launcher_body">Кнопку Домой обрабатывает другое приложение главного экрана: {launcher}. Мы не отключаем приложения, назначение которых нам неизвестно.</string>
</resources>'''
)


write_russian_strings(
    "feature/manage/src/main/res/values-ru/strings.xml",
    r'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="action_back">Назад</string>
    <string name="action_cancel">Отмена</string>
    <string name="action_ok">ОК</string>
    <string name="action_done">Готово</string>
    <string name="action_undo">Отменить</string>
    <string name="gate_title">Подтвердите, что это вы</string>
    <string name="gate_subtitle">Перед изменением настроек телевизора: {tv}</string>
    <string name="gate_subtitle_reset_key">Перед сбросом ключа этого телефона</string>
    <string name="gate_subtitle_delete_all">Перед полным удалением данных</string>
    <string name="gate_negative">Отмена</string>
    <string name="gate_failed">Не удалось подтвердить вашу личность. Изменения не внесены.</string>
    <string name="manage_title" translatable="false">Очистка ТВ</string>
    <string name="manage_settings_cd">Настройки приложения</string>
    <string name="manage_tv_title">{tv}, {room}</string>
    <string name="manage_tv_title_noroom">{tv}</string>
    <string name="manage_state_clean">Очищен</string>
    <string name="manage_state_original">Исходное состояние</string>
    <string name="manage_checked">Проверено {date}</string>
    <string name="manage_tv_card_cd">{tv}. {state}. {checked}</string>
    <string name="manage_thumbnail_cd">Последнее изображение с телевизора {tv}. Нажмите, чтобы обновить.</string>
    <string name="manage_clean_again">Запустить настройку снова</string>
    <string name="manage_level_simple">Простой</string>
    <string name="manage_level_custom">Настраиваемый</string>
    <string name="manage_level_simple_note">Отключает обычные ненужные элементы, сохраняя всё остальное.</string>
    <string name="manage_level_custom_note">Позволяет самостоятельно выбрать каждый элемент.</string>
    <string name="manage_row_home">Главный экран</string>
    <string name="manage_row_home_own">Телевизор сохраняет собственный экран. Почему?</string>
    <string name="manage_row_home_not_in_use">Используется собственный экран телевизора</string>
    <string name="manage_row_home_helper">Кнопка Домой открывает его. Собственный экран телевизора остаётся включённым.</string>
    <string name="manage_row_home_stock_back">Телевизор снова включил собственный главный экран.</string>
    <string name="manage_row_cards">Карточки и модули</string>
    <string name="manage_row_cards_own_home">Отображаются как приложения: телевизор сохраняет собственный главный экран</string>
    <string name="manage_row_cards_none">Карточек пока нет</string>
    <string name="manage_row_switched_off">{count, plural, other {Отключено (#)}}</string>
    <string name="manage_row_switched_off_sub">Любой элемент можно вернуть</string>
    <string name="manage_row_better_back_on">{count, plural, one {Лучше снова включить # приложение} few {Лучше снова включить # приложения} many {Лучше снова включить # приложений} other {Лучше снова включить # приложения}}</string>
    <string name="manage_row_leftovers">Проверить восстановившиеся элементы</string>
    <string name="manage_row_leftovers_sub">После обновления ПО телевизора</string>
    <string name="manage_row_restore">Точка восстановления</string>
    <string name="manage_row_restore_sub">{date}. Отсюда можно отменить все изменения.</string>
    <string name="manage_row_tvsettings">Настройки телевизора</string>
    <string name="manage_add_tv">+ Настроить ещё один телевизор</string>
    <string name="manage_offline_banner">Телевизор не отвечает. Он включён?</string>
    <string name="manage_reconnect">Подключиться снова</string>
    <string name="manage_reconnecting">Подключаемся снова...</string>
    <string name="manage_restarts_held">{count, plural, one {Пережил # перезагрузку} few {Пережил # перезагрузки} many {Пережил # перезагрузок} other {Пережил # перезагрузки}}</string>
    <string name="manage_restarts_ask">Это важно для этой модели телевизора. Вы видите, что запускается первым.</string>
    <string name="manage_restarts_told">Вы сообщили нам {date}. Спасибо!</string>
    <string name="manage_restarts_tell">Сообщить, что всё работает</string>
    <string name="manage_restarts_tell_again">Сообщить ещё раз</string>
    <string name="manage_restart_failed">После перезагрузки вернулся собственный главный экран телевизора.</string>
    <string name="manage_restart_failed_body">Сообщите нам об этом, чтобы мы могли проверить эту модель телевизора.</string>
    <string name="manage_restart_report">Сообщить о проблеме</string>
    <string name="debugstep_title">Чтобы изменить настройки телевизора, нужно ненадолго включить отладку.</string>
    <string name="debugstep_body">На телевизоре откройте: Настройки → Настройки устройства → Для разработчиков → Отладка по USB. Дальше мы всё сделаем сами и снова отключим отладку по завершении.</string>
    <string name="debugstep_note">Повторно нажимать на пункт «Сборка» не потребуется. Режим разработчика останется разблокированным.</string>
    <string name="debugstep_waiting">Ожидаем телевизор...</string>
    <string name="debugstep_connecting">Подключаемся...</string>
    <string name="debugstep_off_why">Отладка выключена, потому что в прошлый раз мы отключили её после завершения настройки, как и планировали.</string>
    <string name="debugstep_nowifi_title">Телефон не подключён к Wi-Fi</string>
    <string name="debugstep_nowifi_body">Подключитесь к той же сети Wi-Fi, что и телевизор. Мы продолжим автоматически.</string>
    <string name="debugstep_allow_title">Посмотрите на телевизор и разрешите подключение телефона</string>
    <string name="debugstep_allow_body">Телевизор спрашивает «Разрешить отладку по USB?». Установите флажок «Всегда разрешать», затем выберите «Разрешить» (на некоторых телевизорах — ОК).</string>
    <string name="debugstep_allow_code">Код на телевизоре оканчивается на {tail}. Это код данного телефона.</string>
    <string name="debugstep_allow_waiting">Ожидаем вашего нажатия на телевизоре...</string>
    <string name="debugstep_details_show">Показать подробности</string>
    <string name="debugstep_details_hide">Скрыть подробности</string>
    <string name="debugstep_sees">Что видит приложение</string>
    <string name="debugstep_row_wifi">Телефон подключён к Wi-Fi</string>
    <string name="debugstep_row_wifi_no">Подключитесь к той же сети Wi-Fi, что и телевизор.</string>
    <string name="debugstep_row_network">Та же сеть, что использовалась при настройке телевизора</string>
    <string name="debugstep_row_network_no">После настройки сеть изменилась. Ищем телевизор в текущей сети.</string>
    <string name="debugstep_row_saved">Телевизор отвечает по сохранённому адресу</string>
    <string name="debugstep_row_saved_no">Включите отладку по USB на телевизоре. Если она включена, возможно, адрес телевизора изменился.</string>
    <string name="debugstep_row_found">Телевизор найден по другому адресу в этой сети</string>
    <string name="debugstep_row_found_no">В этой сети пока не найдено устройство, которое можно опознать как ваш телевизор. Продолжаем поиск.</string>
    <string name="debugstep_row_allow">Телевизор ожидает разрешения на подключение телефона</string>
    <string name="debugstep_row_allow_yes">Посмотрите на телевизор: установите флажок «Всегда разрешать» и выберите «Разрешить».</string>
    <string name="debugstep_yes">Да</string>
    <string name="debugstep_no">Нет</string>
    <string name="debugstep_checking">Проверяем...</string>
    <string name="debugstep_unknown">Не удалось определить</string>
    <string name="debugstep_stuck">Всё ещё не работает? На некоторых телевизорах сетевой порт отладки открывается только после перезагрузки. Перезагрузите телевизор один раз. На некоторых моделях также нужно включить «Сетевую отладку».</string>
    <string name="debugstep_report">Сообщить о проблеме</string>
    <string name="debugstep_address">Ввести адрес телевизора</string>
    <string name="debugstep_row_typed">Телевизор отвечает по введённому адресу</string>
    <string name="debugstep_row_typed_no">Проверьте адрес на телевизоре в разделе Настройки → Сеть. Отладка по USB также должна быть включена.</string>
    <string name="details_copy">Скопировать данные подключения</string>
    <string name="details_share">Поделиться</string>
    <string name="details_collecting">Проверяем Wi-Fi и телевизоры...</string>
    <string name="details_copied">Скопировано. Вставьте данные в сообщение или обращение.</string>
    <string name="details_title">Сведения о подключении</string>
    <string name="details_body">Около 10 секунд проверяем Wi-Fi и каждый сохранённый телевизор, затем копируем результаты. В данных есть заданные вами названия телевизоров, но нет паролей.</string>
    <string name="appsettings_details">Сведения о подключении</string>
    <string name="appsettings_details_note">Информация о Wi-Fi и телевизорах, которую можно скопировать или отправить.</string>
    <string name="openwrite_title">Не удалось завершить изменение на телевизоре {tv}</string>
    <string name="openwrite_body_lost">Телевизор перестал отвечать в процессе. Можно продолжить оставшиеся действия или отменить уже внесённые изменения. До завершения этого процесса другие настройки телевизора менять нельзя.</string>
    <string name="openwrite_body">Работа остановилась на середине. Можно продолжить или отменить уже внесённые изменения. До завершения этого процесса другие настройки телевизора менять нельзя.</string>
    <string name="openwrite_body_setup">Настройка телевизора {tv} не завершена. Откройте её, чтобы продолжить или отменить изменения.</string>
    <string name="openwrite_not_taken">Телевизор не ответил. Убедитесь, что он включён и подключён к той же сети Wi-Fi, что и телефон, затем попробуйте снова.</string>
    <string name="openwrite_busy">Выполняется...</string>
    <string name="openwrite_carry_on">Продолжить</string>
    <string name="openwrite_title_stale">Состояние телевизора {tv} изменилось после последней проверки</string>
    <string name="openwrite_body_stale_nothing">После проверки на телевизоре что-то изменилось, поэтому мы остановились до внесения изменений. Ничего не изменено. Проверьте телевизор ещё раз, чтобы продолжить.</string>
    <string name="openwrite_body_stale">После проверки на телевизоре что-то изменилось, поэтому мы остановились на середине процесса. Проверьте телевизор ещё раз, чтобы продолжить с учётом его текущего состояния.</string>
    <string name="openwrite_check_again">Проверить снова</string>
    <string name="openwrite_undo">Отменить наши изменения</string>
    <string name="openwrite_stop">Остановиться здесь</string>
    <string name="openwrite_open_setup">Открыть настройку</string>
    <string name="openwrite_later">Не сейчас</string>
    <string name="openwrite_report">Сообщить о проблеме</string>
    <string name="openwrite_title_took_home">{app} перехватило кнопку Домой</string>
    <string name="openwrite_body_took_home">После повторного включения {app} телевизор назначил его обработчиком кнопки Домой, поэтому кнопка больше не открывает новый главный экран. Отключите {app} снова, чтобы восстановить работу.</string>
    <string name="openwrite_switch_off_again">Отключить снова</string>
    <string name="openwrite_keep_it">Оставить включённым</string>
    <string name="openwrite_title_look">Не удалось передать оформление на телевизор {tv}</string>
    <string name="openwrite_body_look">Больше ничего не изменилось. Отправьте оформление ещё раз или отмените изменения, чтобы сохранить прежний вид телевизора. До этого момента другие настройки менять нельзя.</string>
    <string name="openwrite_send_again">Отправить ещё раз</string>
    <string name="switchedoff_intro">Каждый элемент можно вернуть. Ничего не удалено: «выключено» означает, что приложение или функция неактивны.</string>
    <string name="switchedoff_group_ads">Реклама и рекламные ряды</string>
    <string name="switchedoff_group_tracking">Отслеживание и аналитика</string>
    <string name="switchedoff_group_nobody">Ненужные приложения</string>
    <string name="switchedoff_group_brand">Дополнения {brand}</string>
    <string name="switchedoff_group_shop">Магазин и демонстрационный режим</string>
    <string name="switchedoff_group_needed">Необходимо для нового главного экрана</string>
    <string name="switchedoff_group_other">Выбрано в разделе «Настроить»</string>
    <string name="switchedoff_put_back">Вернуть</string>
    <string name="switchedoff_put_back_cd">Вернуть {name}</string>
    <string name="switchedoff_group_better">Рекомендуется включить</string>
    <string name="switchedoff_better_kept">В новом списке для этого телевизора элемент оставлен включённым.</string>
    <string name="switchedoff_switch_back_on">Включить снова</string>
    <string name="switchedoff_switch_back_on_cd">Снова включить {name}</string>
    <string name="switchedoff_snackbar">{name} снова включено.</string>
    <string name="switchedoff_empty">Сейчас ничего не отключено.</string>
    <string name="switchedoff_empty_sub">Все элементы телевизора включены.</string>
    <string name="leftovers_title">Проверить восстановившиеся элементы</string>
    <string name="leftovers_checking">Проверяем...</string>
    <string name="leftovers_found">{count, plural, one {После обновления вернулся # элемент. Отключить его снова?} few {После обновления вернулось # элемента. Отключить их снова?} many {После обновления вернулось # элементов. Отключить их снова?} other {После обновления вернулось # элемента. Отключить их снова?}}</string>
    <string name="leftovers_switch_off">Отключить</string>
    <string name="leftovers_not_now">Не сейчас</string>
    <string name="leftovers_none">Ничего не восстановилось. Всё осталось как было.</string>
    <string name="leftovers_done">Повторно отключено.</string>
    <string name="leftovers_error">Сейчас не удалось выполнить проверку. Телевизор ещё включён?</string>
    <string name="leftovers_notice">{count, plural, one {На телевизоре восстановился # элемент.} few {На телевизоре восстановилось # элемента.} many {На телевизоре восстановилось # элементов.} other {На телевизоре восстановился # элемент.}}</string>
    <string name="leftovers_notice_action">Проверить</string>
    <string name="tvsettings_title">Настройки телевизора</string>
    <string name="tvsettings_name_room">Название и комната</string>
    <string name="tvsettings_name_label">Название</string>
    <string name="tvsettings_room_label">Комната</string>
    <string name="tvsettings_level_label">Режим</string>
    <string name="tvsettings_level_simple">Простой</string>
    <string name="tvsettings_level_custom">Настраиваемый</string>
    <string name="tvsettings_level_note">Возврат к простому режиму скрывает дополнительные параметры. Состояние телевизора не изменится.</string>
    <string name="tvsettings_address">Адрес</string>
    <string name="tvsettings_last_seen">Последний раз обнаружен: {date}</string>
    <string name="tvsettings_forget">Забыть этот телевизор</string>
    <string name="tvsettings_forget_explain">Это удалит с телефона запись об этом телевизоре, журнал действий и точки восстановления. Сам телевизор не изменится, но после этого отменить изменения с этого телефона будет нельзя.</string>
    <string name="tvsettings_forget_title">Забыть телевизор {tv}?</string>
    <string name="tvsettings_forget_confirm">Забыть этот телевизор</string>
    <string name="tvsettings_forget_busy">Настройка этого телевизора ещё выполняется. Дождитесь её завершения или сначала остановите процесс.</string>
    <string name="appsettings_title">Настройки приложения</string>
    <string name="appsettings_large_text">Крупный текст как в системе</string>
    <string name="appsettings_large_text_note">Размер текста соответствует настройке на вашем телефоне.</string>
    <string name="appsettings_haptics">Виброотклик</string>
    <string name="appsettings_haptics_note">Короткая вибрация, когда действие завершено или требуется ваше участие.</string>
    <string name="appsettings_about">О приложении</string>
    <string name="appsettings_version">Версия {version}</string>
    <string name="appsettings_launcher_pin_label">Встроенное приложение главного экрана</string>
    <string name="appsettings_launcher_pin_line">{package} {version} · SHA-256 {digest}</string>
    <string name="appsettings_key_label">Ключ телефона</string>
    <string name="appsettings_key_strongbox">Хранится в отдельном защищённом чипе (StrongBox).</string>
    <string name="appsettings_key_tee">Хранится в защищённом оборудовании телефона (TEE).</string>
    <string name="appsettings_key_wrapped">Хранится в программном виде и защищён ключом из защищённого хранилища телефона. Это устройство не поддерживает аппаратное хранение ключа.</string>
    <string name="appsettings_check_updates">Проверить обновления</string>
    <string name="appsettings_checking">Проверяем...</string>
    <string name="appsettings_update_available">Доступна версия {version}</string>
    <string name="appsettings_update_open">Открыть страницу выпуска</string>
    <string name="appsettings_up_to_date">У вас установлена последняя версия</string>
    <string name="appsettings_update_error">Не удалось проверить обновления</string>
    <string name="appsettings_licences">Лицензии</string>
    <string name="appsettings_licence_app">Clean Your TV: MIT</string>
    <string name="appsettings_licence_font">Шрифт Outfit: SIL Open Font Licence 1.1</string>
    <string name="appsettings_licence_apache">Адаптированный код dadb и Shizuku: уведомления Apache-2.0</string>
    <string name="appsettings_licence_pictures">Изображения оформления: собственная работа, CC0 1.0 (общественное достояние); Aurora, Glass и Autumn lake (Ruska) из лаунчера Clean Your TV, MIT</string>
    <string name="appsettings_licence_bouncycastle">Криптографическая библиотека Bouncy Castle: лицензия Bouncy Castle</string>
    <string name="appsettings_privacy">Конфиденциальность</string>
    <string name="appsettings_privacy_text">За пределы вашей сети передаются только загрузки с github.com. GitHub видит адрес телефона и общий идентификатор браузера. Отчёт отправляется только по вашему выбору.</string>
    <string name="appsettings_privacy_text_offline">Данные не покидают вашу сеть. Приложение взаимодействует только с выбранным телевизором и самостоятельно не обращается к интернету. Отчёт отправляется только по вашему выбору.</string>
    <string name="appsettings_report">Сообщить о проблеме</string>
    <string name="appsettings_report_note">Сначала просмотрите отчёт, затем поделитесь им или опубликуйте на GitHub.</string>
    <string name="appsettings_reset_key">Сбросить мой ключ</string>
    <string name="appsettings_reset_key_note">Каждый телевизор снова попросит разрешить доверие этому телефону.</string>
    <string name="appsettings_reset_key_title">Сбросить ключ на этом телефоне?</string>
    <string name="appsettings_reset_key_confirm">Сбросить мой ключ</string>
    <string name="appsettings_delete_all">Удалить всё</string>
    <string name="appsettings_delete_all_note">Удаляет с телефона все записи телевизоров, журналы, точки восстановления, диагностический журнал приложения и ключ. Сами телевизоры не изменяются.</string>
    <string name="appsettings_delete_all_title">Удалить все данные на этом телефоне?</string>
    <string name="appsettings_delete_all_confirm">Удалить всё</string>
    <string name="appsettings_delete_all_busy">Настройка телевизора {tv} ещё выполняется. Дождитесь её завершения или остановите процесс.</string>
    <string name="confirm_stop_title">Остановить сейчас?</string>
    <string name="confirm_stop_body">Мы остановимся и вернём всё, что успели изменить. Телевизор останется в безопасном состоянии.</string>
    <string name="confirm_stop_button">Остановить и отменить изменения</string>
    <string name="confirm_keep_going">Продолжить</string>
    <string name="confirm_stop_here_title">Остановиться здесь?</string>
    <string name="confirm_stop_here_body">Сохраним уже выполненные изменения и больше ничего менять не будем. Телевизор останется в безопасном состоянии.</string>
    <string name="confirm_stop_here_button">Остановиться здесь</string>
    <string name="confirm_undo_title">Отменить изменения на телевизоре?</string>
    <string name="confirm_undo_body">Вернём всё, что изменил этот процесс, и восстановим прежнее состояние телевизора. Ничего не удаляется.</string>
    <string name="confirm_undo_button">Отменить изменения</string>
    <string name="manage_help_add_tv_banner">Мы ещё не работали с этой точной моделью телевизора, поэтому действовали осторожно. Короткий отчёт от вас поможет добавить её в список.</string>
    <string name="manage_help_add_tv">Помочь добавить телевизор</string>
    <string name="manage_report_sent_banner">Отчёт об этом телевизоре отправлен {date}. Мы сообщим в обращении, когда появятся новости.</string>
    <string name="manage_send_again">Отправить снова</string>
    <string name="manage_wizard_home_title">Мастер настройки может перехватить кнопку Домой</string>
    <string name="manage_wizard_home_body">Снова включите собственный главный экран телевизора, чтобы он продолжал нормально запускаться.</string>
    <string name="manage_wizard_home_button">Включить снова</string>
    <string name="manage_wizard_home_working">Включаем собственный главный экран телевизора...</string>
    <string name="report_tv_title">Помочь добавить телевизор</string>
    <string name="report_tv_body">В отчёте указаны модель телевизора и предустановленные приложения, чтобы мы могли составить список для этой модели. Приложения, установленные вами самостоятельно, учитываются только в общем количестве.</string>
    <string name="report_problem_title">Сообщить о проблеме</string>
    <string name="report_confirm_title">Сообщить, что всё работает</string>
    <string name="report_confirm_body">Отчёт подтверждает, что новый главный экран сохранился после перезагрузки. В нём указаны модель телевизора и результат проверки приложения. Никаких имён и сетевых адресов.</string>
    <string name="report_confirm_unavailable">Для этого телевизора ещё не зарегистрирована перезагрузка. Перезагрузите его один раз, пока приложение подключено, затем попробуйте снова.</string>
    <string name="report_confirm_label">Что будет отправлено на GitHub</string>
    <string name="report_problem_body">Отчёт содержит выполненные шаги и краткую информацию о телевизоре в виде обычного текста. Адреса, серийные номера, ключи и коды удалены.</string>
    <string name="report_public">Если опубликовать отчёт на GitHub, он станет общедоступным. Приложение ничего не отправляет самостоятельно: вы сами выбираете, куда его передать.</string>
    <string name="report_preview_label">Отчёт</string>
    <string name="report_paste_hint">Мы скопируем отчёт и откроем форму на GitHub. Вставьте его в поле для описания проблемы.</string>
    <string name="report_copy_open">Скопировать и открыть GitHub</string>
    <string name="report_open">Открыть GitHub</string>
    <string name="report_in_link_hint">Форма GitHub откроется с уже добавленным отчётом. При желании добавьте несколько слов и отправьте его. Мы также скопируем отчёт на случай, если ссылка не сработает.</string>
    <string name="report_in_link_short_hint">Форма GitHub откроется с отчётом и последними строками журнала, чтобы он поместился. При желании добавьте пояснение и отправьте его.</string>
    <string name="report_too_long">Отчёт слишком длинный для автоматической вставки в форму, поэтому мы скопируем его. На GitHub нажмите и удерживайте поле отчёта, выберите «Вставить», затем отправьте форму.</string>
    <string name="report_share">Поделиться</string>
    <string name="report_loading">Подготавливаем отчёт</string>
    <string name="report_unavailable">Сначала нужно один раз прочитать данные этого телевизора. Настройте его или подключитесь к нему, затем попробуйте снова.</string>
    <string name="report_sent_line">Отчёт об этом телевизоре отправлен {date}. Мы сообщим в обращении, когда появятся новости. Его можно отправить повторно.</string>
    <string name="report_sent_issue">Ваше обращение: {issue}. Откройте его, чтобы посмотреть новости.</string>
    <string name="report_issue_label">Ссылка на ваше обращение (необязательно)</string>
    <string name="report_issue_hint">Вставьте ссылку на обращение этого приложения на GitHub, и мы сохраним её здесь.</string>
    <string name="report_issue_error">Это не ссылка на обращение данного приложения на GitHub.</string>
    <string name="report_issue_save">Сохранить ссылку</string>
    <string name="report_issue_open">Открыть обращение</string>
</resources>'''
)


write_russian_strings(
    "feature/setup/src/main/res/values-ru/strings.xml",
    r'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="found_home_tv_refuses">{brand} разрешает использовать только собственный главный экран, а новый установить нельзя. Поэтому телевизор сохраняет свой экран.</string>
    <string name="found_home_fire_tv">Fire TV разрешает использовать только собственный главный экран, а новый установить нельзя. Поэтому телевизор сохраняет свой экран.</string>
    <string name="found_home_old_android">Для нового главного экрана требуется Android 8 или новее, а на этом телевизоре установлена более старая версия. Поэтому он сохраняет собственный экран.</string>
    <string name="found_home_changed">Главный экран телевизора уже был изменён другим приложением или ранее. Мы не отменяем такие решения без вашего участия, поэтому оставляем текущий экран.</string>
    <string name="found_home_unknown_stock">Мы недостаточно хорошо знаем собственный главный экран телевизора, чтобы безопасно его заменить. Поэтому оставляем его.</string>
    <string name="found_home_wizard_wifi">Данные Wi-Fi сохранены мастером настройки. Для нового главного экрана мастер нужно отключить, но тогда сеть будет забыта. Если оставить мастер включённым, телевизор может дать сбой. Поэтому сохраняем собственный экран.</string>
    <string name="found_home_wizard_wifi_unlisted">Данные Wi-Fi сохранены мастером настройки, а мы пока недостаточно хорошо знаем эту модель, чтобы безопасно его отключить. Если оставить мастер включённым, новый экран может вызвать сбой. Поэтому сохраняем собственный экран.</string>
    <string name="found_home_wizard_wifi_unknown">Не удалось проверить, кто сохранил данные Wi-Fi. Отключение мастера может привести к потере сети, а если оставить его включённым, телевизор может дать сбой. Поэтому сохраняем собственный экран.</string>
    <string name="found_home_casting">На этом устройстве Google для нового главного экрана требуется отключить мастер настройки. После этого трансляция с телефонов перестанет работать после каждой перезагрузки. Поэтому сохраняем собственный экран.</string>
    <string name="found_home_boot_loop">На телевизорах этой серии мастер настройки запускается при каждой перезагрузке. Если отключить собственный главный экран, телевизор может перестать загружаться. Поэтому сохраняем собственный экран.</string>
    <string name="found_home_remote_buttons">Кнопки приложений на пульте (Netflix, YouTube, Prime Video и YouTube Music) работают только через собственный главный экран. Поэтому сохраняем его.</string>
    <string name="found_home_vendor_home">На этом телевизоре кнопку Домой также обрабатывает системная служба. На похожей модели после замены экрана телевизор не смог загрузиться. Поэтому сохраняем собственный экран.</string>
    <string name="found_home_patchwall_feeds">На телевизоре включён PatchWall, а Mi TV+ или его веб-содержимое выключено. На похожей модели после замены экрана возник сбой загрузки. Поэтому сохраняем собственный экран.</string>
    <string name="found_home_other_home">На телевизоре настроено другое приложение главного экрана. Мы не отключаем приложения, назначение которых нам неизвестно, поэтому сохраняем текущий экран.</string>
    <string name="found_home_wizard_stays_on">Мы недостаточно хорошо знаем эту модель, чтобы отключить мастер настройки. Если оставить его включённым, а собственный экран выключить, телевизор может дать сбой. Поэтому сохраняем собственный экран.</string>
    <string name="found_home_none">В текущем состоянии заменить главный экран этого телевизора нельзя. Поэтому сохраняем собственный экран.</string>
    <string name="kept_next_wifi">Чтобы изменить это, удалите все сохранённые на телевизоре сети Wi-Fi и подключитесь к своей сети заново. Одно лишь подключение кабеля ничего не изменит. Затем запустите настройку снова.</string>
    <string name="kept_next_wifi_unknown">Повторный запуск настройки ещё раз проверит эту возможность.</string>
    <string name="kept_next_home_changed">Если хотите установить новый экран, назначьте собственный экран телевизора главным в настройках ТВ, затем запустите настройку снова.</string>
    <string name="kept_next_other_home">Чтобы использовать новый экран, удалите другое приложение главного экрана и запустите настройку снова. Или сообщите о проблеме.</string>
    <string name="kept_next_patchwall_feeds">Чтобы установить новый экран, отключите PatchWall в разделе «Реклама и рекламные ряды» на странице настройки.</string>
    <string name="kept_next_list">Для этого нужна база, подготовленная для вашей модели. Функция «Помочь добавить телевизор» отправит нам отчёт. Мы составим список по таким отчётам и сообщим в обращении, когда появится подходящий вариант.</string>
    <string name="kept_next_nothing">Это ограничение самого телевизора, и обновление приложения его не изменит. На этой модели доступна только очистка.</string>
    <string name="customise_live">{total, plural, other {{off} из # отключено}}</string>
    <string name="customise_dock_row_rows">Панель приложений: {names}, а ниже ещё {count, plural, one {# приложение} few {# приложения} many {# приложений} other {# приложения}} в {rows, plural, one {ряду} few {рядах} many {рядах} other {рядах}}</string>
    <string name="customise_look_row_count">Оформление: {theme}, {count, plural, one {# приложение в ряду} few {# приложения в ряду} many {# приложений в ряду} other {# приложения в ряду}}</string>
    <string name="look_photo_after">Изображение ещё не отправлено. Оно появится на телевизоре после нажатия «Очистить телевизор».</string>
    <string name="check_thumbnail_cd">{count, plural, =0 {На телевизоре новый главный экран с пустой панелью приложений} one {На телевизоре новый главный экран с одним приложением на панели} few {На телевизоре новый главный экран с # приложениями на панели} many {На телевизоре новый главный экран с # приложениями на панели} other {На телевизоре новый главный экран с # приложением на панели}}</string>
    <string name="stop_home_other_launcher_primary">Использовать новый главный экран</string>
    <string name="stop_home_other_launcher_secondary">Оставить {launcher}</string>
    <string name="stop_home_settings_open_body">Во время проверки на телевизоре была открыта страница настроек, поэтому для безопасности мы вернули собственный главный экран. С телевизором всё в порядке. Попробуйте снова и на этот раз нажмите на пульте только кнопку Домой.</string>
    <string name="stop_home_low_storage_body">На телевизоре почти не осталось места (свободно {free, plural, other {# МБ}}), поэтому установить новый главный экран не удалось. Собственный экран сохранён, остальные изменения остались. Освободите место и запустите настройку снова.</string>
    <string name="stop_home_chooser_body">{count, plural, one {На телевизоре установлено другое приложение главного экрана ({names}). Android спросил, какой экран использовать, поэтому переключение не завершилось. Мы вернули собственный главный экран телевизора. Удалите другое приложение или выберите его в качестве основного.} other {На телевизоре установлены другие приложения главного экрана ({names}). Android спросил, какой экран использовать, поэтому переключение не завершилось. Мы вернули собственный главный экран телевизора. Удалите другие приложения или выберите нужное в качестве основного.}}</string>
    <string name="stop_home_unknown_title">Собственный главный экран телевизора восстановлен</string>
    <string name="stop_home_unknown_body">Не удалось переключить этот телевизор на новый главный экран, поэтому мы вернули собственный. Все остальные выполненные изменения сохранены. Чтобы попробовать снова, откройте страницу главного экрана этого телевизора или отправьте нам отчёт.</string>
    <string name="stop_home_unknown_primary">Сообщить о проблеме</string>
    <string name="stop_home_unknown_secondary">ОК</string>
    <string name="stop_home_user_said_no_title">Собственный главный экран телевизора восстановлен</string>
    <string name="stop_home_user_said_no_body">Прежний экран восстановлен. Что-то выглядело неправильно или вы просто хотели сначала посмотреть?</string>
    <string name="stop_home_user_said_no_primary">Попробовать снова</string>
    <string name="stop_home_user_said_no_secondary">Что-то выглядело неправильно</string>
    <string name="stop_home_timed_out_title">Собственный главный экран телевизора восстановлен</string>
    <string name="stop_home_timed_out_body">Возвращаем собственный главный экран телевизора... Готово. Телевизор снова в прежнем состоянии. Чтобы попробовать снова, откройте страницу главного экрана этого телевизора.</string>
    <string name="stop_home_timed_out_body_off">{count, plural, one {Возвращаем собственный главный экран телевизора... Готово. # элемент всё ещё отключён, а главный экран восстановлен. Чтобы попробовать снова, откройте страницу главного экрана телевизора.} few {Возвращаем собственный главный экран телевизора... Готово. # элемента всё ещё отключены, а главный экран восстановлен. Чтобы попробовать снова, откройте страницу главного экрана телевизора.} many {Возвращаем собственный главный экран телевизора... Готово. # элементов всё ещё отключены, а главный экран восстановлен. Чтобы попробовать снова, откройте страницу главного экрана телевизора.} other {Возвращаем собственный главный экран телевизора... Готово. # элемента всё ещё отключено, а главный экран восстановлен. Чтобы попробовать снова, откройте страницу главного экрана телевизора.}}</string>
    <string name="stop_home_timed_out_primary">ОК</string>
    <string name="stop_home_timed_out_secondary">Попробовать снова</string>
    <string name="stop_step_failed_title">Работа остановлена</string>
    <string name="stop_step_failed_body">{total, plural, other {Один из шагов не выполнен, поэтому мы остановились после отключения {done} из # элементов. Больше ничего не изменено.}}</string>
    <string name="stop_undone_title">Все наши изменения отменены</string>
    <string name="stop_undone_body">Телевизор снова в прежнем состоянии. Вы можете запустить настройку в любое время.</string>
    <string name="stop_undone_primary">ОК</string>
    <string name="stop_undone_secondary">Показать подробности</string>
    <string name="stop_connection_lost_title">Телевизор перестал отвечать</string>
    <string name="stop_connection_lost_body">{total, plural, one {Телевизор перестал отвечать после отключения {done} из # элемента. Обычно он снова отвечает через несколько секунд.} few {Телевизор перестал отвечать после отключения {done} из # элементов. Обычно он снова отвечает через несколько секунд.} many {Телевизор перестал отвечать после отключения {done} из # элементов. Обычно он снова отвечает через несколько секунд.} other {Телевизор перестал отвечать после отключения {done} из # элемента. Обычно он снова отвечает через несколько секунд.}}</string>
    <string name="stop_connection_lost_reconnecting">Подключаемся снова...</string>
    <string name="stop_connection_lost_back">Вернуться и продолжить?</string>
    <string name="stop_connection_lost_primary">Продолжить остальные действия</string>
    <string name="stop_connection_lost_secondary">Остановиться здесь</string>
    <string name="stop_connection_lost_tertiary">Отменить наши изменения</string>
    <string name="stop_connection_lost_suspect">Похоже, мы снова включили «{name}», поскольку от него зависит подключение Wi-Fi на этом телевизоре. Продолжаем без отключения этого элемента.</string>
    <string name="stop_connection_lost_gone_body">Телевизор по-прежнему не отвечает. Убедитесь, что он включён и подключён к той же сети Wi-Fi, что и телефон.</string>
    <string name="stop_connection_lost_keep_trying">Продолжать попытки</string>
    <string name="stop_resume_title">Настройка телевизора {tv} не была завершена</string>
    <string name="stop_resume_body">{total, plural, one {{done} из # элементов отключён; главный экран ещё не менялся. Продолжить?} few {{done} из # элементов отключено; главный экран ещё не менялся. Продолжить?} many {{done} из # элементов отключено; главный экран ещё не менялся. Продолжить?} other {{done} из # элемента отключено; главный экран ещё не менялся. Продолжить?}}</string>
    <string name="stop_resume_primary">Продолжить</string>
    <string name="stop_resume_secondary">Отменить наши изменения</string>
    <string name="stop_resume_tertiary">Оставить как есть</string>
    <string name="stop_resume_home_title">Мы вернули собственный главный экран телевизора</string>
    <string name="stop_resume_home_body">Для безопасности мы восстановили собственный главный экран. Попробовать переключить его снова?</string>
    <string name="stop_resume_home_primary">Попробовать снова</string>
    <string name="stop_resume_home_secondary">Оставить как есть</string>
    <string name="stop_resume_change_title">Изменение на телевизоре {tv} не было завершено</string>
    <string name="stop_resume_change_body">Процесс остановился до завершения. Продолжить остальные действия или отменить сделанное?</string>
    <string name="stop_reconnect_home_body">Подключитесь к телевизору, чтобы вернуть его собственный главный экран. Мы сделаем это, как только {tv} ответит.</string>
    <string name="stop_install_failed_title">Не удалось установить новый главный экран</string>
    <string name="stop_install_failed_body">На телевизоре недостаточно места: свободно {free, plural, other {# МБ}}, требуется {needed, plural, other {# МБ}}. Мы очистили кэш и повторили попытку, но это не помогло. Очистка завершена; чтобы позже установить главный экран, освободите место и запустите настройку снова.</string>
    <string name="stop_install_failed_generic_body">Телевизор не разрешил установить новый главный экран. Очистка завершена. Позже вы можете снова запустить настройку и повторить установку.</string>
    <string name="stop_install_failed_primary">ОК</string>
    <string name="stop_install_failed_secondary">Показать подробности</string>
    <string name="stop_not_back_title">Телевизор ещё не запустился</string>
    <string name="stop_not_back_body">Если телевизор завис на логотипе, удерживайте кнопку питания на самом телевизоре (не на пульте) в течение 10 секунд, затем включите его снова. Если появится экран «Не удаётся загрузить Android», выберите «Повторить попытку».</string>
    <string name="stop_not_back_auto">Как только телевизор снова подключится к Wi-Fi, мы автоматически подключимся и восстановим его собственный главный экран. Вы сможете спокойно решить, что делать дальше.</string>
    <string name="stop_not_back_primary">Телевизор снова включён</string>
    <string name="stop_not_back_secondary">Помогите мне</string>
    <string name="done_title">Телевизор готов</string>
    <string name="done_title_kept">Очистка завершена</string>
    <string name="done_title_nothing">Изменений не внесено</string>
    <string name="done_body_nothing">Нечего было отключать, поэтому телевизор остался в прежнем состоянии.</string>
    <string name="done_body_nothing_kept">Все элементы, которые мы пытались отключить, остались включёнными. Телевизор не изменился.</string>
    <string name="done_body_nothing_stopped">Настройка остановилась до внесения изменений. Телевизор остался в прежнем состоянии.</string>
    <string name="done_body_nothing_unknown">Мы не нашли на этом телевизоре ничего, в чём уверены, поэтому он остался без изменений. Помогите добавить модель — в будущей версии мы сможем сделать больше.</string>
    <string name="done_hero_cd">Чистый главный экран с отметкой о завершении</string>
    <string name="done_body">{count, plural, =0 {Новый главный экран включён. Все изменения можно отменить.} one {Отключён # элемент. Новый главный экран включён. Все изменения можно отменить.} few {Отключено # элемента. Новый главный экран включён. Все изменения можно отменить.} many {Отключено # элементов. Новый главный экран включён. Все изменения можно отменить.} other {Отключено # элемента. Новый главный экран включён. Все изменения можно отменить.}}</string>
    <string name="done_body_helper">{count, plural, =0 {Кнопка Домой открывает новый главный экран. Собственный экран телевизора остаётся включённым, поэтому трансляция продолжает работать. Все изменения можно отменить.} one {Отключён # элемент. Кнопка Домой открывает новый главный экран. Собственный экран телевизора остаётся включённым, поэтому трансляция продолжает работать. Все изменения можно отменить.} few {Отключено # элемента. Кнопка Домой открывает новый главный экран. Собственный экран телевизора остаётся включённым, поэтому трансляция продолжает работать. Все изменения можно отменить.} many {Отключено # элементов. Кнопка Домой открывает новый главный экран. Собственный экран телевизора остаётся включённым, поэтому трансляция продолжает работать. Все изменения можно отменить.} other {Отключено # элемента. Кнопка Домой открывает новый главный экран. Собственный экран телевизора остаётся включённым, поэтому трансляция продолжает работать. Все изменения можно отменить.}}</string>
    <string name="done_body_no_home">{count, plural, one {Отключён # элемент. Все изменения можно отменить.} few {Отключено # элемента. Все изменения можно отменить.} many {Отключено # элементов. Все изменения можно отменить.} other {Отключено # элемента. Все изменения можно отменить.}}</string>
    <string name="done_body_home_kept">{count, plural, =0 {Телевизор сохраняет собственный главный экран, поэтому его внешний вид не изменился. Все изменения можно отменить.} one {Отключён # элемент. Телевизор сохраняет собственный главный экран, поэтому его внешний вид не изменился. Все изменения можно отменить.} few {Отключено # элемента. Телевизор сохраняет собственный главный экран, поэтому его внешний вид не изменился. Все изменения можно отменить.} many {Отключено # элементов. Телевизор сохраняет собственный главный экран, поэтому его внешний вид не изменился. Все изменения можно отменить.} other {Отключено # элемента. Телевизор сохраняет собственный главный экран, поэтому его внешний вид не изменился. Все изменения можно отменить.}}</string>
    <string name="done_body_partial">{total, plural, other {Отключено {done} из # элементов; главный экран не изменён. Все изменения можно отменить.}}</string>
    <string name="done_body_partial_home_on">{total, plural, other {Отключено {done} из # элементов. Новый главный экран включён. Все изменения можно отменить.}}</string>
    <string name="done_home_kept_timed_out">Собственный главный экран телевизора восстановлен: время проверки на телевизоре истекло. Чтобы попробовать снова, откройте страницу главного экрана этого телевизора.</string>
    <string name="done_home_kept_said_no">Собственный главный экран телевизора восстановлен: вы сообщили, что новый экран выглядит неправильно. Чтобы попробовать снова, откройте страницу главного экрана этого телевизора.</string>
    <string name="done_home_kept">Собственный главный экран телевизора восстановлен: новый экран не смог стать основным. Чтобы попробовать снова, откройте страницу главного экрана этого телевизора.</string>
    <string name="done_look_failed">Не удалось передать оформление на телевизор. Отправьте его снова со страницы главного экрана.</string>
    <string name="done_restore_saved">Точка восстановления сохранена на этом телефоне ({date})</string>
    <string name="done_restore_earlier">Точка восстановления на этом телефоне (от {date})</string>
    <string name="done_debug_note">После нажатия «Готово» отладка на телевизоре отключится. В следующий раз её нужно будет снова включить с помощью пульта.</string>
    <string name="done_debug_kept">Отладка на телевизоре остаётся включённой, как вы и выбрали.</string>
    <string name="done_skipped">{name} не удалось отключить; это не опасно.</string>
    <string name="kept_tv_input">{name}. Оставлено включённым: обеспечивает работу HDMI и ТВ-тюнера.</string>
    <string name="done_skipped_many">{count, plural, one {Не удалось отключить # элемент; это не опасно.} few {Не удалось отключить # элемента; это не опасно.} many {Не удалось отключить # элементов; это не опасно.} other {Не удалось отключить # элемента; это не опасно.}}</string>
    <string name="done_mibox_usb">Питание USB-порта восстановлено.</string>
    <string name="done_add_card_title">Добавить карточку на главный экран</string>
    <string name="done_add_card_body">Необязательно. Карточки можно добавить позже в разделе «Карточки и модули».</string>
    <string name="done_note_title">Сохранить заметку о восстановлении для семьи</string>
    <string name="done_finish">Завершить</string>
    <string name="done_finishing">Отключаем отладку...</string>
    <string name="done_finished">Отключаем отладку... Готово.</string>
    <string name="restart_card_title">Перезагрузите телевизор, чтобы проверить результат</string>
    <string name="restart_card_recommended">2 минуты, рекомендуется</string>
    <string name="restart_card_optional">Необязательно</string>
    <string name="restart_button">Перезагрузить</string>
    <string name="restart_getting_ready">{seconds, plural, other {Подготовка... # с}}</string>
    <string name="restart_intro">Перезагружаем телевизор. Обычно это занимает около минуты. Мы следим за его запуском.</string>
    <string name="restart_progress_off">Телевизор выключен...</string>
    <string name="restart_progress_starting">Запускается...</string>
    <string name="restart_progress_found">Обнаружен в {time}...</string>
    <string name="restart_progress_home">Используется новый главный экран.</string>
    <string name="restart_progress_home_helper">Кнопка Домой открывает новый главный экран.</string>
    <string name="restart_progress_home_own">Используется собственный главный экран телевизора, как и раньше.</string>
    <string name="restart_progress_good">Всё в порядке.</string>
    <string name="restart_progress_held">Новый главный экран сохранился после перезагрузки.</string>
    <string name="restart_progress_failed">После перезагрузки вернулся собственный главный экран телевизора.</string>
    <string name="done_tell_us">Сообщить, что всё работает</string>
    <string name="done_tell_us_note">Это поможет проверить данную модель телевизора. Вы увидите содержимое отчёта; ничего не отправится, пока вы сами не опубликуете его на GitHub.</string>
    <string name="done_restart_report_note">Сообщите нам, чтобы мы могли проверить эту модель телевизора. В отчёте указаны результаты проверки приложения.</string>
    <string name="done_tv_now_title">Так теперь выглядит ваш телевизор</string>
    <string name="done_tv_now_cd">Текущее изображение на телевизоре</string>
    <string name="done_tv_now_again">Что-то не так? Отправить ещё раз</string>
    <string name="restart_came_back">{count, plural, one {После перезагрузки вернулся # элемент; он снова отключён.} few {После перезагрузки вернулось # элемента; они снова отключены.} many {После перезагрузки вернулось # элементов; они снова отключены.} other {После перезагрузки вернулся # элемент; он снова отключён.}}</string>
    <string name="restart_slow">Перезагрузка занимает больше обычного. Некоторые телевизоры медленно подключаются к Wi-Fi. Если на экране только логотип, подождите ещё две минуты.</string>
    <string name="candidate_overline">Тестовая сборка</string>
    <string name="candidate_title">Список для этой модели новый</string>
    <string name="candidate_body">Мы составили список для этой модели по отчётам о таких же телевизорах, но его ещё никто не проверял. В плане, как обычно, указано, что будет отключено. Всё можно вернуть из точки восстановления.</string>
    <string name="candidate_ask_issue">Если попробуете, пожалуйста, сообщите в обращении {issue}, как всё работало через несколько дней:</string>
    <string name="candidate_ask">Если попробуете, сообщите через «Сообщить о проблеме», как всё работало через несколько дней:</string>
    <string name="candidate_check_wifi">Wi-Fi после перезагрузки</string>
    <string name="candidate_check_remote">Все кнопки пульта, включая Вход и голосовое управление</string>
    <string name="candidate_check_hdmi">Каждый вход HDMI</string>
    <string name="candidate_check_live_tv">Эфирное телевидение</string>
    <string name="candidate_check_casting">Трансляция с телефона</string>
    <string name="candidate_try">Попробовать тестовый список</string>
    <string name="candidate_careful">Осторожный режим, как в версии 0.3.4</string>
    <string name="candidate_careful_note">Осторожный режим включает только те изменения, безопасность которых подтверждена для телевизоров этой марки, как и в версии 0.3.4.</string>
    <string name="helper_group">Кнопка Домой</string>
    <string name="helper_row">Помощник кнопки Домой</string>
    <string name="helper_row_note">Собственный главный экран телевизора остаётся включённым; кнопка Домой открывает новый экран.</string>
    <string name="helper_row_on">Включён: кнопка Домой открывает новый экран, а собственный экран телевизора остаётся включённым.</string>
    <string name="helper_consent_title">Помощник кнопки Домой</string>
    <string name="helper_consent_keep">Собственный главный экран телевизора остаётся включённым, а кнопка Домой открывает новый. Мастер настройки также остаётся включённым, поэтому Wi-Fi и трансляция не затрагиваются. Небольшой помощник внутри нового главного экрана отслеживает нажатие кнопки Домой.</string>
    <string name="helper_consent_sees">Помощник — это служба специальных возможностей Android. Android предупредит, что она может видеть экран и нажатия кнопок. Этот помощник запрашивает только название открытого приложения, но не содержимое экрана и не текст, который вы вводите.</string>
    <string name="helper_consent_changes">Что изменится: на короткое время может появляться собственный главный экран телевизора, а затем новый. Если телевизор или приложение очистки принудительно остановит главный экран, помощник выключится, пока вы снова не включите его здесь.</string>
    <string name="helper_consent_off">Вы можете отключить помощник в любое время: здесь, с помощью отмены изменений или на телевизоре в Настройки → Специальные возможности → Кнопка Домой.</string>
    <string name="helper_consent_yes">Включить помощник</string>
    <string name="helper_consent_no">Не сейчас</string>
</resources>'''
)


write_russian_strings(
    "feature/setup/src/main/res/values-ru/strings.xml",
    r'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="confirm_stop_title">Остановить сейчас?</string>
    <string name="confirm_stop_body">Мы остановимся и вернём всё, что успели изменить. Телевизор останется в безопасном состоянии.</string>
    <string name="confirm_stop_button">Остановить и отменить изменения</string>
    <string name="confirm_keep_going">Продолжить</string>
    <string name="confirm_stop_here_title">Остановиться здесь?</string>
    <string name="confirm_stop_here_body">Сохраним уже выполненные изменения и больше ничего менять не будем. Телевизор останется в безопасном состоянии.</string>
    <string name="confirm_stop_here_button">Остановиться здесь</string>
    <string name="confirm_undo_title">Отменить изменения на телевизоре?</string>
    <string name="confirm_undo_body">Вернём всё, что изменил этот процесс, и восстановим прежнее состояние телевизора. Ничего не удаляется.</string>
    <string name="confirm_undo_button">Отменить изменения</string>
</resources>'''
)

write_russian_strings(
    "feature/manage/src/main/res/values-ru/strings.xml",
    r'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="switchedoff_title">{count, plural, other {Отключено (#)}}</string>
</resources>'''
)


write_russian_strings(
    "ui/design/src/main/res/values-ru/strings.xml",
    r'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="cyt_look_columns_empty">{apps, plural, one {На панели приложений # приложение} few {На панели приложений # приложения} many {На панели приложений # приложений} other {На панели приложений # приложения}}, поэтому {free, plural, one {останется # свободное место} few {останется # свободных места} many {останется # свободных мест} other {останется # свободного места}}</string>
    <string name="cyt_look_columns_waiting">{count, plural, one {# приложение ожидает} few {# приложения ожидают} many {# приложений ожидают} other {# приложения ожидают}} в сетке, пока вы не выберете больше приложений в одном ряду.</string>
    <string name="cyt_look_columns_seven">При значении 7 длинные названия приложений на телевизоре обрезаются.</string>
    <string name="cyt_look_columns_eight">При значении 8 плитки становятся маленькими, а длинные названия обрезаются. В собственных настройках телевизора максимум — 7.</string>
    <string name="cyt_look_columns_cd">{count, plural, one {# приложение в ряду} few {# приложения в ряду} many {# приложений в ряду} other {# приложения в ряду}}</string>
    <string name="cyt_look_columns_cd_big">{count, plural, one {# приложение в ряду, крупные плитки} few {# приложения в ряду, крупные плитки} many {# приложений в ряду, крупные плитки} other {# приложения в ряду, крупные плитки}}</string>
    <string name="cyt_look_columns_cd_many">{count, plural, one {# приложение в ряду, больше приложений} few {# приложения в ряду, больше приложений} many {# приложений в ряду, больше приложений} other {# приложения в ряду, больше приложений}}</string>
    <string name="cyt_look_caption">{style}. {description}</string>
    <string name="cyt_look_preview_cd">Предварительный просмотр главного экрана телевизора: {style}, {picture}, {count, plural, one {# приложение в ряду} few {# приложения в ряду} many {# приложений в ряду} other {# приложения в ряду}}.</string>
    <string name="cyt_look_preview_cd_names">Предварительный просмотр главного экрана телевизора: {style}, {picture}, {count, plural, one {# приложение в ряду} few {# приложения в ряду} many {# приложений в ряду} other {# приложения в ряду}}, названия под каждым приложением.</string>
    <string name="cyt_look_preview_cd_more">Предварительный просмотр главного экрана телевизора: {style}, {picture}, {count, plural, one {# приложение в ряду} few {# приложения в ряду} many {# приложений в ряду} other {# приложения в ряду}}, {extras}.</string>
    <string name="cyt_look_preview_cd_names_more">Предварительный просмотр главного экрана телевизора: {style}, {picture}, {count, plural, one {# приложение в ряду} few {# приложения в ряду} many {# приложений в ряду} other {# приложения в ряду}}, названия под каждым приложением, {extras}.</string>
    <string name="cyt_look_cd_one_row">все приложения в одном ряду</string>
    <string name="cyt_look_cd_no_band">без панели подложки</string>
    <string name="cyt_look_cd_no_greeting">без приветствия</string>
    <string name="cyt_look_cd_date_below">дата под часами</string>
    <string name="cyt_look_cd_bright_date">более яркая дата</string>
    <string name="cyt_look_cd_icon_tiles">значки на карточках</string>
    <string name="cyt_look_cd_rim">подсветка по краю</string>
    <string name="cyt_look_cd_halo">светящийся ореол</string>
    <string name="cyt_look_picture_words_photo">ваша фотография</string>
    <string name="cyt_look_picture_words_none">без изображения</string>
    <string name="cyt_look_picture_words">изображение «{name}»</string>
    <string name="cyt_look_style_own">У стиля «{style}» есть собственная настройка «{details}».</string>
    <string name="cyt_look_style_none">Для стиля «{style}» настройка «{details}» недоступна.</string>
    <string name="cyt_look_style_none_alone">Для стиля «{style}» нет настройки «{details}», поэтому она отключена.</string>
    <string name="cyt_look_join_last">{first} и {last}</string>
    <string name="cyt_look_join">{first}, {next}</string>
    <string name="cyt_look_word_big_name">крупное название</string>
    <string name="cyt_look_word_glow">свечение</string>
    <string name="cyt_look_word_ring">цветное кольцо</string>
    <string name="cyt_look_word_dock_band">подложка панели приложений</string>
    <string name="cyt_look_word_buttons">кнопки</string>
    <string name="cyt_look_word_wifi">цвет значка Wi-Fi</string>
    <string name="cyt_look_word_see_through">степень прозрачности</string>
    <string name="cyt_look_keep_mine">Оставить мой вариант</string>
    <string name="cyt_look_update_banner">Обновите главный экран на телевизоре, чтобы использовать все стили.</string>
    <string name="cyt_look_update_action">Обновить</string>
    <string name="cyt_look_needs_update">Требуется обновление главного экрана</string>
    <string name="cyt_look_picture_mine">Ваша фотография</string>
    <string name="cyt_look_picture_earlier">Предыдущее фото</string>
    <string name="cyt_look_picture_choose_cd">Выбрать фотографию. Откроется галерея.</string>
    <string name="cyt_look_picture_until">До {date}</string>
    <string name="cyt_look_picture_tint">Свечение цветом приложения придаёт оттенок вашей фотографии.</string>
    <string name="cyt_look_picture_tint_off">Отключить</string>
    <string name="cyt_look_columns_all_apps">Все приложения отображаются, по {count} в ряду.</string>
    <string name="cyt_look_columns_empty_dock">Панель приложений пуста, поэтому подложка тоже останется пустой. Добавить приложения можно в разделе «Панель приложений».</string>
    <string name="cyt_look_columns_spotlight_below">{count, plural, one {# приложение ожидает} few {# приложения ожидают} many {# приложений ожидают} other {# приложения ожидают}} под панелью, на один шаг вниз.</string>
    <string name="cyt_look_columns_below">{count, plural, one {# приложение переместится} few {# приложения переместятся} many {# приложений переместятся} other {# приложения переместятся}} в ряд под панелью.</string>
    <string name="cyt_look_names">Названия под каждым приложением</string>
    <string name="cyt_look_names_desc">Их легче читать с дивана.</string>
    <string name="cyt_look_more_title">Дополнительные параметры</string>
    <string name="cyt_look_more_summary_default">{movement}, а также свечение, панель и кнопки</string>
    <string name="cyt_look_more_summary_two">{movement}, {second}</string>
    <string name="cyt_look_more_summary_more">{movement}, {second} и ещё {count}</string>
    <string name="cyt_look_more_picked">{movement}, подобрано для этого телевизора</string>
    <string name="cyt_look_more_lift">{movement} с подъёмом и увеличением</string>
    <string name="cyt_look_word_lively">Живое движение</string>
    <string name="cyt_look_word_smooth">Плавное движение</string>
    <string name="cyt_look_word_quick">Быстрое движение</string>
    <string name="cyt_look_word_off">Без анимации</string>
    <string name="cyt_look_word_spot_on">Цвет приложения в верхней части экрана</string>
    <string name="cyt_look_word_spot_off">Без цвета приложения сверху</string>
    <string name="cyt_look_word_big_name_on">Крупное название над приложениями</string>
    <string name="cyt_look_word_big_name_off">Без крупного названия</string>
    <string name="cyt_look_word_glow_soft">Мягкое свечение</string>
    <string name="cyt_look_word_glow_top">Свечение сверху</string>
    <string name="cyt_look_word_glow_none">Без свечения</string>
    <string name="cyt_look_word_ring_on">Кольцо цветом приложения</string>
    <string name="cyt_look_word_ring_off">Без цветного кольца</string>
    <string name="cyt_look_word_band_solid">Непрозрачная панель</string>
    <string name="cyt_look_word_band_see">Прозрачная панель</string>
    <string name="cyt_look_word_band_glass">Стеклянная панель</string>
    <string name="cyt_look_word_buttons_names">Кнопки с названиями</string>
    <string name="cyt_look_word_buttons_round">Круглые кнопки</string>
    <string name="cyt_look_word_buttons_menu">Одна кнопка меню</string>
    <string name="cyt_look_word_wifi_on">Зелёный значок Wi-Fi</string>
    <string name="cyt_look_word_wifi_off">Обычный значок Wi-Fi</string>
    <string name="cyt_look_word_dim">Затемнение изображения: {dim}%</string>
    <string name="cyt_look_word_sky_on">Небо включено</string>
    <string name="cyt_look_word_sky_off">Небо выключено</string>
    <string name="cyt_look_undo_changes">Отменить изменения</string>
    <string name="cyt_look_undo_done">Ваши изменения отменены.</string>
    <string name="cyt_look_undo_bring_back">Вернуть их</string>
    <string name="cyt_look_earlier">Вернуть предыдущее оформление</string>
    <string name="cyt_look_earlier_question">Не то, что вы хотели?</string>
    <string name="cyt_look_earlier_chosen">Выбрано предыдущее оформление. Нажмите «Применить на телевизоре», чтобы отправить его.</string>
    <string name="cyt_look_tiles_note">Здесь приложения показаны простыми плитками. На телевизоре у них будут собственные изображения.</string>
    <string name="cyt_look_use">Применить на телевизоре</string>
    <string name="cyt_look_on_tv">На телевизоре уже используется это оформление.</string>
    <string name="cyt_look_done">Готово</string>
    <string name="cyt_look_tv_note">Изменения, сделанные пультом непосредственно на телевизоре, будут заменены.</string>
    <string name="cyt_look_leave_title">Выйти без отправки?</string>
    <string name="cyt_look_leave_body">Ваши изменения ещё не применены на телевизоре.</string>
    <string name="cyt_look_leave_body_photo">Новая фотография ещё не отправлена на телевизор.</string>
    <string name="cyt_look_leave_stay">Продолжить настройку</string>
    <string name="cyt_look_leave_go">Выйти</string>
    <string name="cyt_look_more_movement">Анимация</string>
    <string name="cyt_look_motion_lively">Живая</string>
    <string name="cyt_look_motion_lively_desc">Подсветка слегка пружинит при перемещении.</string>
    <string name="cyt_look_motion_smooth">Плавная</string>
    <string name="cyt_look_motion_smooth_desc">Плавно перемещается к следующему приложению без пружинящего эффекта.</string>
    <string name="cyt_look_motion_quick">Быстрая</string>
    <string name="cyt_look_motion_quick_desc">Быстрое перемещение почти без эффектов. Подходит для медленных телевизоров.</string>
    <string name="cyt_look_motion_off">Выключена</string>
    <string name="cyt_look_motion_off_desc">Без движущихся эффектов. Самый лёгкий вариант для медленного телевизора и тех, кому неприятна анимация.</string>
    <string name="cyt_look_motion_suggested">Рекомендуется для этого телевизора: объём памяти {size}.</string>
    <string name="cyt_look_memory_size">{size} ГБ</string>
    <string name="cyt_look_more_big_name">Крупное название</string>
    <string name="cyt_look_big_name">Крупное название над приложениями</string>
    <string name="cyt_look_big_name_desc">Название приложения, которое сейчас выбрано, отображается крупным шрифтом.</string>
    <string name="cyt_look_big_name_all_apps">В стиле «Все приложения» крупное название отсутствует.</string>
    <string name="cyt_look_big_name_spotlight">Стиль «В центре внимания» построен вокруг крупного названия.</string>
    <string name="cyt_look_big_name_three_rows">Отключается, если панель приложений занимает три ряда.</string>
    <string name="cyt_look_more_glow_dock">Свечение и панель</string>
    <string name="cyt_look_glow">Свечение</string>
    <string name="cyt_look_glow_soft">Мягкое</string>
    <string name="cyt_look_glow_soft_desc">Мягкое свечение цветом приложения за его названием.</string>
    <string name="cyt_look_glow_top">Сверху</string>
    <string name="cyt_look_glow_top_desc">Цвет приложения плавно распространяется сверху экрана вниз.</string>
    <string name="cyt_look_glow_none">Нет</string>
    <string name="cyt_look_glow_none_desc">Без свечения. Изображение остаётся чистым.</string>
    <string name="cyt_look_spot_colour">Цвет приложения в верхней части экрана</string>
    <string name="cyt_look_spot_colour_desc">Верх экрана окрашивается в цвет выбранного приложения.</string>
    <string name="cyt_look_ring">Кольцо цветом приложения</string>
    <string name="cyt_look_ring_desc">Выбранное приложение выделяется кольцом своего цвета.</string>
    <string name="cyt_look_ring_festival">Пока отображается «{picture}», подсветка будет золотой.</string>
</resources>'''
)


write_russian_strings(
    "ui/design/src/main/res/values-ru/strings.xml",
    r'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="cyt_look_band">Подложка панели приложений</string>
    <string name="cyt_look_band_solid">Непрозрачная</string>
    <string name="cyt_look_band_solid_desc">Тёмная подложка за панелью приложений.</string>
    <string name="cyt_look_band_see">Прозрачная</string>
    <string name="cyt_look_band_see_desc">Изображение просвечивает сквозь тёмную подложку.</string>
    <string name="cyt_look_band_glass">Стеклянная</string>
    <string name="cyt_look_band_glass_desc">Светлая матовая полупрозрачная подложка.</string>
    <string name="cyt_look_band_all_apps">В стиле «Все приложения» подложка не используется.</string>
    <string name="cyt_look_band_spotlight">В стиле «В центре внимания» используется собственная прозрачная подложка.</string>
    <string name="cyt_look_more_top">Верхняя часть экрана</string>
    <string name="cyt_look_buttons">Кнопки</string>
    <string name="cyt_look_buttons_names">С названиями</string>
    <string name="cyt_look_buttons_names_desc">Входы, Wi-Fi и Настройки с подписями.</string>
    <string name="cyt_look_buttons_round">Круглые значки</string>
    <string name="cyt_look_buttons_round_desc">Небольшие круглые кнопки без подписей.</string>
    <string name="cyt_look_buttons_menu">Одна кнопка меню</string>
    <string name="cyt_look_buttons_menu_desc">Входы, Wi-Fi и Настройки собраны в одной круглой кнопке. Надпись «Входы» больше не отображается на экране.</string>
    <string name="cyt_look_wifi_green">Зелёный значок Wi-Fi при подключении к интернету</string>
    <string name="cyt_look_wifi_green_desc">Значок Wi-Fi становится зелёным, когда телевизор подключён к сети.</string>
    <string name="cyt_look_wifi_green_menu">Недоступно при использовании одной кнопки меню.</string>
    <string name="cyt_look_more_picture">Изображение</string>
    <string name="cyt_look_darken">Затемнить изображение</string>
    <string name="cyt_look_percent">{value}%</string>
    <string name="cyt_look_percent_cd">{value} процентов</string>
    <string name="cyt_look_darken_none">Сначала выберите изображение.</string>
    <string name="cyt_look_sky">Небо, меняющееся в зависимости от времени</string>
    <string name="cyt_look_sky_desc">Голубое днём, тёплое на рассвете и закате, тёмное ночью.</string>
    <string name="cyt_look_reset">Использовать настройки стиля «{style}»</string>
    <string name="cyt_look_reset_count">{count, plural, one {# изменение} few {# изменения} many {# изменений} other {# изменения}}</string>
    <string name="cyt_look_reset_done">Параметры сброшены.</string>
    <string name="cyt_look_advanced">Расширенные настройки</string>
    <string name="cyt_look_advanced_desc">Подъём и увеличение, точные значения и файл настроек.</string>
    <string name="cyt_look_one_row">Все приложения в одном ряду</string>
    <string name="cyt_look_one_row_desc">Сначала приложения панели, затем остальные. Ряд прокручивается по горизонтали при перемещении.</string>
    <string name="cyt_look_one_row_note">При включении одного ряда дополнительные ряды под панелью не используются.</string>
    <string name="cyt_look_band_none">Нет</string>
    <string name="cyt_look_band_none_desc">Без подложки. Приложения располагаются прямо на изображении.</string>
    <string name="cyt_look_greeting">Приветствие над часами</string>
    <string name="cyt_look_greeting_desc">«Доброе утро», «Добрый вечер» и другие приветствия.</string>
    <string name="cyt_look_date_below">Дата под часами</string>
    <string name="cyt_look_date_below_desc">Дата отображается отдельной строкой под временем.</string>
    <string name="cyt_look_bright_date">Более яркая дата</string>
    <string name="cyt_look_bright_date_desc">Дата и линия рядом с ней отображаются ярко-белым цветом.</string>
    <string name="cyt_look_wifi_warn">Предупреждать об отсутствии интернета</string>
    <string name="cyt_look_wifi_warn_desc">Оранжевый цвет — Wi-Fi работает, но интернета нет; красный — телевизор отключён от сети.</string>
    <string name="cyt_look_wifi_warn_needs">Сначала включите параметр «Зелёный значок Wi-Fi при подключении к интернету».</string>
    <string name="cyt_look_more_tiles">Плитки</string>
    <string name="cyt_look_tiles">Плитки приложений</string>
    <string name="cyt_look_tiles_art">Собственные изображения приложений</string>
    <string name="cyt_look_tiles_art_desc">Баннер каждого приложения в том виде, в котором его предоставляет разработчик.</string>
    <string name="cyt_look_tiles_icons">Значки на карточках</string>
    <string name="cyt_look_tiles_icons_desc">Значок каждого приложения на карточке его собственного цвета, без названия.</string>
    <string name="cyt_look_highlight">Подсветка</string>
    <string name="cyt_look_highlight_glass">Стекло</string>
    <string name="cyt_look_highlight_glass_desc">Стеклянная панель с мягким блеском.</string>
    <string name="cyt_look_highlight_rim">Контур</string>
    <string name="cyt_look_highlight_rim_desc">Только тонкая подсвеченная граница.</string>
    <string name="cyt_look_highlight_halo">Ореол</string>
    <string name="cyt_look_highlight_halo_desc">Контур с мягким свечением.</string>
    <string name="cyt_look_needs_30">Дополнительные варианты доступны в главном экране версии 3.0.</string>
    <string name="cyt_look_needs_30_action">Обновить главный экран</string>
    <string name="cyt_look_word_one_row">Один ряд</string>
    <string name="cyt_look_word_no_band">Без подложки панели</string>
    <string name="cyt_look_word_no_greeting">Без приветствия</string>
    <string name="cyt_look_word_date_below">Дата под часами</string>
    <string name="cyt_look_word_bright_date">Яркая дата</string>
    <string name="cyt_look_word_wifi_warn">Предупреждения об интернете</string>
    <string name="cyt_look_word_icon_tiles">Плитки со значками</string>
    <string name="cyt_look_word_rim">Контурная подсветка</string>
    <string name="cyt_look_word_halo">Подсветка ореолом</string>
    <string name="cyt_look_tiles_note_icons">Здесь значки показаны буквами на карточках. На телевизоре у каждого приложения будет собственный значок.</string>
    <string name="cyt_look_adv_lead">Для тех, кто знаком с настройками главного экрана. Все параметры здесь будут переданы на телевизор вместе с оформлением.</string>
    <string name="cyt_look_lift">Подъём и увеличение</string>
    <string name="cyt_look_lift_desc">Выбранное приложение приподнимается с тенью и немного увеличивается, а приложения открываются с коротким эффектом приближения. Требует больше памяти.</string>
    <string name="cyt_look_lift_low_memory">В этом телевизоре {size} памяти, поэтому эффект может замедлить его работу.</string>
    <string name="cyt_look_lift_needs_motion">Работает со стилями анимации «Живая» или «Плавная».</string>
    <string name="cyt_look_see_amount">Степень прозрачности</string>
    <string name="cyt_look_see_amount_desc">Чем ниже значение, тем больше изображения видно сквозь подложку.</string>
    <string name="cyt_look_see_amount_off">Сначала выберите «Прозрачная» в разделе «Подложка панели».</string>
    <string name="cyt_look_sky_strength">Интенсивность неба</string>
    <string name="cyt_look_sky_strength_off">Сначала включите «Небо, меняющееся в зависимости от времени».</string>
    <string name="cyt_look_key_names">Показывать названия параметров лаунчера</string>
    <string name="cyt_look_key_names_desc">Показывает точные названия параметров из файла настроек главного экрана, например dock_glass.</string>
    <string name="cyt_look_key_names_phone_only">(только на телефоне)</string>
    <string name="cyt_look_file_title">Файл настроек</string>
    <string name="cyt_look_file_desc">Здесь показаны параметры оформления, которые будут переданы на телевизор. Полный файл также содержит список приложений панели. Кнопка «Копировать» копирует весь файл.</string>
    <string name="cyt_look_not_here">В этом приложении нельзя настроить ряды под панелью и скрытие приложений. Ряды можно настроить на телевизоре в параметрах лаунчера до следующего обновления.</string>
    <string name="cyt_look_file_copy">Копировать</string>
    <string name="cyt_look_file_copied">Скопировано.</string>
    <string name="cyt_photo_after">Ничего ещё не отправлено. Нажмите «Применить на телевизоре», когда будете готовы.</string>
    <string name="cyt_address_title">Введите адрес телевизора</string>
    <string name="cyt_address_body">На телевизоре откройте Настройки → Сеть → выберите свою сеть Wi-Fi. Там будет указан адрес, например 192.168.1.50.</string>
    <string name="cyt_address_field_label">Адрес телевизора</string>
    <string name="cyt_address_error">Это не адрес устройства в вашей домашней сети.</string>
    <string name="cyt_address_connect">Подключиться</string>
</resources>'''
)


# Additional strings introduced by upstream 0.5.0-beta1.
write_russian_strings(
    "feature/launcher/src/main/res/values-ru/strings.xml",
    r'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="home_not_on_tv_home_changed_off">Кнопка «Домой» открывает установленный вами лаунчер, а собственный главный экран телевизора отключён не этим приложением. Поэтому мастер настройки оставил экран как есть и не устанавливал наш. Очистка работает и без него.</string>
    <string name="home_not_on_tv_home_changed_ours">На телевизоре уже установлен главный экран Clean Your TV, добавленный другим способом (например, через компьютер) и подписанный другим ключом. Мастер настройки оставил его как есть и не устанавливал ещё одну копию.</string>
    <string name="home_not_on_tv_next_home_changed_off">Если хотите установить новый главный экран, сначала включите собственный экран телевизора. На телевизоре откройте: Настройки → Приложения → Все приложения → Показать системные приложения, выберите {app} и нажмите «Включить» (названия пунктов могут немного отличаться). Не удаляйте свой лаунчер. Затем запустите настройку ещё раз — приложение всё проверит.</string>
    <string name="home_not_on_tv_next_home_changed_ours">Чтобы это приложение могло управлять им (док-станцией, оформлением и обновлениями), сначала отмените установку через средство восстановления той программы, которая его установила: собственный главный экран телевизора должен вернуться. Затем запустите настройку ещё раз. Не удаляйте его до этого — иначе телевизору может быть не на что переключиться.</string>
    <string name="home_own_home_screen">собственный главный экран телевизора</string>
    <string name="miy_title">Настройте под себя</string>
    <string name="home_row_hub_note">Оформление, обои, приложения, виджеты и заставка</string>
    <string name="miy_row_home">Главный экран</string>
    <string name="miy_row_home_note">Какой главный экран используется и как его обновлять</string>
    <string name="miy_preview_caption">Так будет выглядеть телевизор. Все изменения ниже будут показаны здесь вместе.</string>
    <string name="miy_preview_cd">Предварительный просмотр главного экрана телевизора с вашими изменениями</string>
    <string name="miy_row_look">Внешний вид</string>
    <string name="miy_row_wallpaper">Обои</string>
    <string name="miy_row_wallpaper_none">Без изображения</string>
    <string name="miy_row_wallpaper_photo">Ваше фото</string>
    <string name="miy_row_apps">Приложения</string>
    <string name="miy_row_apps_dock">Док-станция: {names}</string>
    <string name="miy_row_apps_dock_more">Док-станция: {names} и ещё {count}</string>
    <string name="miy_row_widgets">Виджеты</string>
    <string name="miy_row_widgets_clock">Часы</string>
    <string name="miy_row_widgets_greets">Приветствие: {name}</string>
    <string name="miy_row_widgets_buttons">{count, plural, one {# кнопка} few {# кнопки} many {# кнопок} other {# кнопки}}</string>
    <string name="miy_row_widgets_menu">кнопки в одном меню</string>
    <string name="miy_row_widgets_second">Вторая дата</string>
    <string name="miy_row_widgets_stats">Состояние ТВ</string>
    <string name="miy_row_inputs">Входы и эфирное ТВ</string>
    <string name="miy_row_buttons">Кнопки пульта</string>
    <string name="miy_row_buttons_helper">Помощник кнопки «Домой»</string>
    <string name="miy_row_saver">Заставка</string>
    <string name="miy_row_saver_tv">Стандартная от телевизора</string>
    <string name="miy_row_saver_clock">Часы через {count, plural, one {# минуту} few {# минуты} many {# минут} other {# минуты}}</string>
    <string name="miy_start_home">Запускать главный экран</string>
    <string name="miy_start_live">Запускать эфирное ТВ</string>
    <string name="miy_start_app">Запускать приложение</string>
    <string name="miy_changes">{count, plural, one {# изменение ещё не применено к ТВ} few {# изменения ещё не применены к ТВ} many {# изменений ещё не применено к ТВ} other {# изменения ещё не применены к ТВ}}</string>
    <string name="miy_put">Применить на телевизоре</string>
    <string name="miy_discard">Оставить без изменений</string>
    <string name="miy_failed">Не удалось передать изменения на телевизор. Они сохранены здесь — попробуйте ещё раз.</string>
    <string name="miy_needs_update">Для некоторых параметров нужен более новый главный экран. Обновите его на странице «Главный экран».</string>
    <string name="miy_apps_sub">Какие приложения и где показывать</string>
    <string name="miy_apps_dock_hint">Количество приложений в строке и подписи под ними настраиваются в разделе «Внешний вид».</string>
    <string name="miy_apps_on_tv">На телевизоре</string>
    <string name="miy_apps_move">Переместить приложения с помощью пульта</string>
    <string name="miy_apps_move_desc">Удерживайте OK на любом приложении на телевизоре и выберите «Переместить». Порядок сохранится, пока вы не измените док-станцию здесь.</string>
    <string name="miy_apps_library">Библиотека приложений</string>
    <string name="miy_apps_library_desc">Кнопка «Библиотека» после последнего ряда: все приложения телевизора.</string>
    <string name="miy_apps_hidden">Скрытые приложения</string>
    <string name="miy_apps_hidden_hint">Скрытые приложения остаются установленными и доступны в библиотеке. Приложения из док-станции здесь не отображаются: сначала удалите их из док-станции.</string>
    <string name="miy_apps_hidden_cd">Скрыть {name}</string>
    <string name="miy_apps_names">Названия</string>
    <string name="miy_apps_names_hint">Собственное название плитки на телевизоре. Оставьте поле пустым, чтобы использовать название приложения.</string>
    <string name="miy_apps_name_label">Отображать {name} как</string>
    <string name="miy_inputs_start">Что открывать при включении телевизора</string>
    <string name="miy_inputs_home">Главный экран</string>
    <string name="miy_inputs_home_desc">Новый главный экран, как сейчас.</string>
    <string name="miy_inputs_live">Эфирное ТВ</string>
    <string name="miy_inputs_live_desc">Открывает {app}.</string>
    <string name="miy_inputs_app">Приложение</string>
    <string name="miy_inputs_app_desc">При первом запуске выберите его на телевизоре в настройках главного экрана.</string>
    <string name="miy_inputs_start_hint">Один раз после каждого перезапуска. Если сначала нажать другую кнопку, сработает она; до главного экрана всегда можно добраться одним нажатием.</string>
    <string name="miy_inputs_no_live">На этом телевизоре нет приложения эфирного ТВ, поэтому можно запускать главный экран или другое приложение.</string>
    <string name="miy_inputs_elsewhere">В другом месте</string>
    <string name="miy_inputs_live_tile">Плитка эфирного ТВ</string>
    <string name="miy_inputs_live_tile_in">В док-станции. Измените её в разделе «Док-станция».</string>
    <string name="miy_inputs_live_tile_out">Нет в док-станции. Добавьте её в разделе «Док-станция».</string>
    <string name="miy_inputs_input_key">Кнопка Input на пульте по-прежнему открывает собственный список входов телевизора.</string>
    <string name="miy_buttons_home">Кнопка «Домой»</string>
    <string name="miy_buttons_none">Кнопка «Домой» на пульте открывает новый главный экран.</string>
    <string name="miy_saver_sub">Что показывает телевизор, когда его никто не смотрит</string>
    <string name="miy_saver_tv">Стандартная от телевизора</string>
    <string name="miy_saver_tv_desc">Остаётся стандартная заставка телевизора. На Google TV это режим Ambient.</string>
    <string name="miy_saver_clock">Часы</string>
    <string name="miy_saver_clock_desc">Большие часы с лёгкой анимацией на главном экране.</string>
    <string name="miy_saver_after">Запускать через</string>
    <string name="miy_saver_minutes">{count} мин.</string>
    <string name="miy_saver_minutes_cd">{count, plural, one {# минуту} few {# минуты} many {# минут} other {# минуты}}</string>
    <string name="miy_saver_behind">Фон часов</string>
    <string name="miy_saver_picture">Изображение главного экрана</string>
    <string name="miy_saver_black">Просто чёрный фон — лучше для OLED</string>
    <string name="miy_saver_glow_now">Сейчас: мягкое свечение цветом часов, встроенное в главный экран.</string>
    <string name="miy_saver_where">Только на главном экране, не поверх эфира или приложения. В остальных случаях продолжает запускаться стандартная заставка телевизора.</string>
</resources>'''
)

write_russian_strings(
    "feature/setup/src/main/res/values-ru/strings.xml",
    r'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="found_zero_kept_choices">Некоторые приложения оставлены включёнными, чтобы вы могли решить сами. Они перечислены в разделе «Настроить».</string>
    <string name="kept_reason_home_changed_off">Собственный главный экран отключён, а кнопка «Домой» открывает ваш лаунчер, поэтому мы не трогаем его.</string>
    <string name="kept_reason_home_changed_ours">На телевизоре уже установлен главный экран Clean Your TV другим способом.</string>
    <string name="found_home_changed_off">Кнопка «Домой» открывает установленный вами лаунчер, а собственный главный экран телевизора отключён не этим приложением. Мы не отменяем такие изменения, поэтому телевизор сохранит текущий главный экран.</string>
    <string name="found_home_changed_ours">На телевизоре уже установлен главный экран Clean Your TV другим способом (например, через компьютер) и подписанный другим ключом. Мы оставляем его как есть и не устанавливаем свою копию.</string>
    <string name="kept_next_home_changed_off">Если хотите установить новый главный экран, сначала включите собственный экран телевизора. На телевизоре откройте: Настройки → Приложения → Все приложения → Показать системные приложения, выберите {app} и нажмите «Включить» (названия пунктов могут немного отличаться). Не удаляйте свой лаунчер. Затем запустите настройку ещё раз — приложение всё проверит.</string>
    <string name="kept_next_home_changed_ours">Чтобы это приложение могло управлять им (док-станцией, оформлением и обновлениями), сначала отмените установку через средство восстановления той программы, которая его установила: собственный главный экран телевизора должен вернуться. Затем запустите настройку ещё раз. Не удаляйте его до этого — иначе телевизору может быть не на что переключиться.</string>
    <string name="kept_own_home_screen">собственный главный экран телевизора</string>
    <string name="stop_home_other_key_title">Сначала удалите старую копию главного экрана</string>
    <string name="stop_home_other_key_body">На телевизоре уже есть копия нового главного экрана, установленная другим способом, например через компьютер. Она подписана другим ключом, поэтому телевизор не позволит обновить её. Собственный главный экран телевизора остаётся включённым, а все остальные выполненные изменения сохраняются. Чтобы безопасно удалить старую копию, сначала убедитесь, что собственный главный экран телевизора включён и открывается кнопкой «Домой» (если программа установки отключила его, включите обратно с помощью той же программы). Никогда не оставляйте телевизор без главного экрана. Затем удалите старую копию (com.gathin.tvhome) в Настройки → Приложения и снова запустите настройку.</string>
    <string name="done_restart_first">Перед завершением перезапустите телевизор и проверьте, что новый главный экран сохранился.</string>
    <string name="helper_not_offered_new_home">Недоступно, пока новый главный экран назначен основным. Чтобы попробовать, выберите «Использовать собственный главный экран телевизора» в разделе «Главный экран для этого ТВ», затем снова запустите настройку.</string>
    <string name="helper_not_offered_not_google">Недоступно: сейчас кнопка «Домой» на этом телевизоре открывает не главный экран Google. Если это приложение меняло его раньше, команда «Отменить все изменения» восстановит исходное состояние. Затем снова запустите настройку.</string>
    <string name="helper_not_offered_home_unknown">Недоступно: мы недостаточно хорошо знаем, как устроен главный экран этого телевизора.</string>
    <string name="helper_not_offered_tv">Недоступно: этот телевизор не поддерживает установку нового главного экрана.</string>
    <string name="helper_not_offered_accessibility">Недоступно: безопасно изменить специальные возможности телевизора не удалось.</string>
</resources>'''
)

write_russian_strings(
    "ui/design/src/main/res/values-ru/strings.xml",
    r'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="cyt_look_moved_widgets">Приветствие, дата и кнопки в верхней части экрана теперь находятся в разделе «Виджеты» меню «Настройте под себя» — рядом с переключателями их отображения.</string>
</resources>'''
)


# Russian localization for the 0.5.0-beta1 Widgets page (all 154 resources).
write_russian_strings(
    "feature/launcher/src/main/res/values-ru/strings_widgets.xml",
    r'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="widgets_place_clock">Часы</string>
    <string name="widgets_place_under_clock">Под часами</string>
    <string name="widgets_place_buttons">Кнопки</string>
    <string name="widgets_place_under_buttons">Под кнопками</string>
    <string name="widgets_place_before_apps">Перед приложениями</string>
    <string name="widgets_board_place_cd">{place}. {now}</string>
    <string name="widgets_board_add">+ Добавить</string>
    <string name="widgets_board_add_card">+ Добавить карточку</string>
    <string name="widgets_board_nothing">Пока ничего нет</string>
    <string name="widgets_board_no_cards">В этой версии карточек нет</string>
    <string name="widgets_board_hint">Расположение как на телевизоре. Места остаются на своих позициях — вы выбираете, что в них показывать.</string>
    <string name="widgets_sample_greeting">Добрый вечер</string>
    <string name="widgets_sample_greeting_name">Добрый вечер, {name}</string>
    <string name="widgets_sample_time_12">8:41</string>
    <string name="widgets_sample_am_pm">PM</string>
    <string name="widgets_sample_time_24">20:41</string>
    <string name="widgets_sample_date">Вторник, 28 октября</string>
    <string name="widgets_second_name">Вторая дата</string>
    <string name="widgets_second_sub">Солнечная хиджра или лунный календарь</string>
    <string name="widgets_line_shamsi">6 абана 1405</string>
    <string name="widgets_line_lunar">Лунная дата 13/9 · Год Лошади</string>
    <string name="widgets_stats_name">Состояние ТВ</string>
    <string name="widgets_stats_sub">Каждые 10 секунд</string>
    <string name="widgets_line_stats" formatted="false">ЦП 14% · Свободно в памяти 1,2 ГБ · Свободно на накопителе 61%</string>
    <string name="widgets_caption_name">Название изображения</string>
    <string name="widgets_caption_sub">Переключается ниже</string>
    <string name="widgets_line_caption">Название изображения</string>
    <string name="widgets_retro_name">Ретро</string>
    <string name="widgets_retro_sub">Дополнительная карточка</string>
    <string name="widgets_line_retro">Классические игры</string>
    <string name="widgets_later_tag">Позже</string>
    <string name="widgets_later_sub">Пока недоступно на телевизорах</string>
    <string name="widgets_later_cd">{name}. Появится позже, пока недоступно на телевизорах.</string>
    <string name="widgets_later_action">Что для этого нужно</string>
    <string name="widgets_weather_name">Погода</string>
    <string name="widgets_weather_line">29°, небольшой дождь</string>
    <string name="widgets_weather_why">Главный экран пока не показывает здесь погоду. Он остаётся компактным и не подключается к интернету, поэтому для погоды сначала потребуется небольшое изменение главного экрана.</string>
    <string name="widgets_world_name">Мировое время</string>
    <string name="widgets_world_line">Лондон 15:12 · Нью-Йорк 10:12</string>
    <string name="widgets_countdown_name">Обратный отсчёт</string>
    <string name="widgets_countdown_line">До Дивали 4 дня</string>
    <string name="widgets_sun_name">Восход и закат</string>
    <string name="widgets_sun_line">Закат в 18:04</string>
    <string name="widgets_note_name">Заметка</string>
    <string name="widgets_note_line">Стоматолог в 16:00</string>
    <string name="widgets_small_change_why">Требуется изменение главного экрана. Его основа остаётся компактной, поэтому мы добавим небольшое собственное изменение.</string>
    <string name="widgets_prayer_name">Время молитвы</string>
    <string name="widgets_prayer_line">Аср 15:42</string>
    <string name="widgets_prayer_why">Появится в строке «Скоро», когда дополнение Prayer Times получит поддержку, или как карточка после подписания.</string>
    <string name="widgets_f1_name">Этапы Формулы-1</string>
    <string name="widgets_f1_line">F1 · На этих выходных</string>
    <string name="widgets_f1_why">Главный экран уже умеет это показывать. Сначала приложению нужно загружать календарь гонок каждого сезона, поэтому функция появится в одной из следующих версий.</string>
    <string name="widgets_match_name">Скоро</string>
    <string name="widgets_match_line">IND — NZ · Сб, 14:00</string>
    <string name="widgets_match_why">Главный экран 3.3 уже умеет показывать здесь матчи и события через дополнение Game Day. Функция появится после подписания дополнения.</string>
    <string name="widgets_sources_name">Источники</string>
    <string name="widgets_sources_line">Ваши входы</string>
    <string name="widgets_sources_why">Карточка из дополнений Gathin. Появится после подписания, как и «Ретро».</string>
    <string name="widgets_wcard_name">Карточка погоды</string>
    <string name="widgets_wcard_line">Прогноз и качество воздуха</string>
    <string name="widgets_wclock_name">Мировые часы</string>
    <string name="widgets_wclock_line">Время в разных городах</string>
    <string name="widgets_card_why">Карточка из дополнений Gathin. Появится после подписания.</string>
    <string name="widgets_pick_on">Включено</string>
    <string name="widgets_pick_off">Выключено</string>
    <string name="widgets_greeting">Приветствие</string>
    <string name="widgets_greeting_sub">«Доброе утро», «Добрый день», «Добрый вечер» или «Спокойной ночи» над часами.</string>
    <string name="widgets_greeting_plain">Без имени</string>
    <string name="widgets_greeting_with_name">С вашим именем</string>
    <string name="widgets_name_label">Ваше имя</string>
    <string name="widgets_name_hint">Имя остаётся на телевизоре и не попадает в отчёты об ошибках.</string>
    <string name="widgets_greeting_times">Утро — до 12:00, день — до 17:00, вечер — до 22:00, затем ночь. Во время праздничных сезонов, например Дивали, приветствие меняется вместе с оформлением.</string>
    <string name="widgets_date_label">Дата</string>
    <string name="widgets_date_below">Дата под часами</string>
    <string name="widgets_date_below_sub">Если выключено — отображается рядом с часами, после короткой линии.</string>
    <string name="widgets_bright_date">Яркая дата</string>
    <string name="widgets_bright_date_sub">Белым, как часы, а не приглушённым цветом.</string>
    <string name="widgets_time_label">Время</string>
    <string name="widgets_time_system">Как на телевизоре</string>
    <string name="widgets_time_12">12-часовой формат</string>
    <string name="widgets_time_24">24-часовой формат</string>
    <string name="widgets_time_hint">Главный экран этого телевизора получает настройку с телефона. Если выбрано «Как на телевизоре», используется настройка самого ТВ.</string>
    <string name="widgets_time_older">Главный экран этого телевизора использует собственную настройку ТВ — 12- или 24-часовой формат. Измените её в настройках телевизора.</string>
    <string name="widgets_clock_always">Часы всегда отображаются, как только телевизор узнаёт время: подключите его к интернету или установите дату.</string>
    <string name="widgets_under_hint">Элементы располагаются в порядке главного экрана. Нажмите на элемент с пометкой «Позже», чтобы узнать, что ему требуется.</string>
    <string name="widgets_second_label">Вторая дата</string>
    <string name="widgets_second_shamsi">Солнечная хиджра</string>
    <string name="widgets_second_lunar">Лунный календарь (китайский и вьетнамский)</string>
    <string name="widgets_second_shamsi_hint">Над обычной датой; она станет немного меньше, чтобы обе даты поместились.</string>
    <string name="widgets_second_lunar_hint">Под обычной датой, рассчитывается для вашего часового пояса.</string>
    <string name="widgets_stats_hint">Состояние ТВ: насколько загружен телевизор и сколько свободного места осталось. Полезно для медленных телевизоров.</string>
    <string name="widgets_look_label">Внешний вид</string>
    <string name="widgets_look_names">С подписями</string>
    <string name="widgets_look_names_sub">Текст и значки</string>
    <string name="widgets_look_icons">Круглые значки</string>
    <string name="widgets_look_icons_sub">Также для входов</string>
    <string name="widgets_look_menu">Одно меню</string>
    <string name="widgets_look_menu_sub">Как в Google TV</string>
    <string name="widgets_look_menu_hint">Одна кнопка с шестерёнкой. В её меню находятся входы, сеть, сортировка и настройки.</string>
    <string name="widgets_which_label">Какие кнопки показывать</string>
    <string name="widgets_which_hint">На телевизоре они располагаются в этом порядке, слева направо.</string>
    <string name="widgets_btn_inputs">Входы</string>
    <string name="widgets_btn_inputs_sub">Скрывается, если ничего не подключено</string>
    <string name="widgets_btn_apps">Ваши приложения</string>
    <string name="widgets_btn_apps_sub">Круглые значки вне рядов</string>
    <string name="widgets_btn_wifi">Wi-Fi</string>
    <string name="widgets_btn_wifi_sub">Показывает название сети</string>
    <string name="widgets_btn_vpn">VPN</string>
    <string name="widgets_btn_vpn_sub">Зелёный — включён, красный — выключен</string>
    <string name="widgets_btn_reorder">Сортировка</string>
    <string name="widgets_btn_reorder_sub">Изменить порядок приложений</string>
    <string name="widgets_btn_library">Библиотека</string>
    <string name="widgets_btn_library_sub">Все приложения на телевизоре</string>
    <string name="widgets_btn_library_needs">Требуется библиотека приложений. Она также будет включена.</string>
    <string name="widgets_btn_settings">Настройки</string>
    <string name="widgets_btn_settings_sub">Всегда отображается</string>
    <string name="widgets_btn_settings_always">Кнопка настроек всегда отображается на телевизоре, поэтому настройки ТВ доступны одним нажатием.</string>
    <string name="widgets_btn_menu">Меню</string>
    <string name="widgets_apps_label">Ваши приложения в верхней панели</string>
    <string name="widgets_apps_hint">До 3 приложений в виде круглых значков. Они убираются из рядов ниже, поэтому каждое приложение будет только в одном месте.</string>
    <string name="widgets_apps_full">В верхней панели может быть до 3 приложений. Сначала уберите одно из них.</string>
    <string name="widgets_apps_number">Номер {n} в верхней панели</string>
    <string name="widgets_wifi_label">Кнопка Wi-Fi</string>
    <string name="widgets_wifi_green">Зелёный цвет при подключении к сети</string>
    <string name="widgets_wifi_green_sub">Значок Wi-Fi становится зелёным, когда телевизор подключён к интернету.</string>
    <string name="widgets_wifi_warn">Оранжевый или красный при проблемах</string>
    <string name="widgets_wifi_warn_sub">Оранжевый: подключено, но нет интернета. Красный: нет подключения.</string>
    <string name="widgets_wifi_name">Название сети рядом со значком</string>
    <string name="widgets_wifi_name_sub">Если выключено — только круглый значок.</string>
    <string name="widgets_opens_label">При нажатии OK открывается</string>
    <string name="widgets_wifi_opens_wifi">Настройки Wi-Fi</string>
    <string name="widgets_wifi_opens_vpn">Приложение VPN</string>
    <string name="widgets_wifi_hold">При удержании OK по-прежнему открываются настройки Wi-Fi.</string>
    <string name="widgets_vpn_app_label">Какое приложение VPN</string>
    <string name="widgets_vpn_none">На этом телевизоре приложение VPN не найдено.</string>
    <string name="widgets_vpn_label">Кнопка VPN открывает</string>
    <string name="widgets_vpn_hint">Зелёный замок — VPN подключён, красная надпись «VPN выключен» — нет. В этом случае кнопка Wi-Fi всегда открывает настройки Wi-Fi.</string>
    <string name="widgets_settings_label">Кнопка настроек</string>
    <string name="widgets_settings_tv">Настройки телевизора</string>
    <string name="widgets_settings_home">Настройки главного экрана</string>
    <string name="widgets_settings_hint">Удерживайте OK, чтобы открыть меню с обоими вариантами.</string>
    <string name="widgets_collide">Дата накладывается на кнопки. При таком количестве кнопок на телевизоре нет места для даты рядом с часами.</string>
    <string name="widgets_collide_action">Переместить дату под часы</string>
    <string name="widgets_caption_none">У ваших изображений пока нет названий. Приложение отправляет одно изображение; названия появятся в коллекциях изображений.</string>
    <string name="widgets_caption_showing">Сейчас отображается название каждого изображения под кнопками.</string>
    <string name="widgets_caption_off">У ваших изображений есть названия, но их показ выключен.</string>
    <string name="widgets_caption_switch">Названия изображений</string>
    <string name="widgets_caption_switch_sub">Название каждого изображения под кнопками.</string>
    <string name="widgets_cards_label">Карточки перед приложениями</string>
    <string name="widgets_cards_hint">Карточка — это плитка с названием и одной короткой строкой, как у приложения. Она находится в начале ряда под док-панелью. Каждая карточка — дополнение из репозитория Gathin, подписанное нами; автор указан на телевизоре. До 8 карточек.</string>
    <string name="widgets_retro_hint">«Ретро» открывает игры из дополнения. Интернет и специальные разрешения не требуются.</string>
    <string name="widgets_retro_action">Изменить в разделе «Карточки и модули»</string>
    <string name="widgets_cards_link">Карточки и модули</string>
    <string name="widgets_cards_play">Карточки добавляются дополнениями, которых в этой версии приложения пока нет.</string>
</resources>'''
)

# Fail the build if a Clean Your TV resource module is missing any Russian
# translation or if a generated translation file is not well-formed XML.
import xml.etree.ElementTree as ET

_russian_modules = (
    "app",
    "feature/backup",
    "feature/connect",
    "feature/launcher",
    "feature/manage",
    "feature/modules",
    "feature/setup",
    "tv/curtain",
    "ui/design",
)
for _module in _russian_modules:
    _base_dir = upstream / _module / "src/main/res/values"
    _localized_dir = upstream / _module / "src/main/res/values-ru"
    _base_files = sorted(_base_dir.glob("strings*.xml"))
    if not _base_files:
        raise SystemExit(f"Missing source strings: {_base_dir}")
    if not _localized_dir.exists():
        raise SystemExit(f"Missing Russian strings: {_localized_dir}")
    _base_keys = set()
    for _base_file in _base_files:
        try:
            _base_root = ET.parse(_base_file).getroot()
        except ET.ParseError as _error:
            raise SystemExit(f"Invalid source XML in {_base_file}: {_error}")
        _base_keys.update(node.attrib.get("name") for node in _base_root if node.attrib.get("name"))
    _localized_keys = set()
    for _localized_file in sorted(_localized_dir.glob("strings*.xml")):
        try:
            _localized_root = ET.parse(_localized_file).getroot()
        except ET.ParseError as _error:
            raise SystemExit(f"Invalid localization XML in {_localized_file}: {_error}")
        _localized_keys.update(node.attrib.get("name") for node in _localized_root if node.attrib.get("name"))
    _missing = sorted(_base_keys - _localized_keys)
    if _missing:
        raise SystemExit(f"Russian localization incomplete in {_module}: {', '.join(_missing)}")
    print(f"Russian localization verified: {_module} ({len(_base_keys)} resources)")



replace_once(
    manage_screens,
    'import androidx.compose.material3.ExperimentalMaterial3Api',
    'import androidx.compose.material3.ExperimentalMaterial3Api\nimport androidx.compose.material3.LinearProgressIndicator',
)

# Poll Android DownloadManager and expose its live percentage on the app-settings update button.
replace_once(
    app_root,
    '                    val state by m.state.collectAsStateWithLifecycle()\n                    LaunchedEffect(m) { m.effects.collect { onManageEffect(it) } }\n                    SecureWindow(state.confirm == AppConfirm.RESET_KEY)',
    '                    val state by m.state.collectAsStateWithLifecycle()\n                    var updateDownloadProgress by remember { mutableStateOf<Int?>(null) }\n                    LaunchedEffect(Unit) { while (true) { updateDownloadProgress = CleanYourTvUpdater.downloadProgress(context); kotlinx.coroutines.delay(500) } }\n                    LaunchedEffect(m) { m.effects.collect { onManageEffect(it) } }\n                    SecureWindow(state.confirm == AppConfirm.RESET_KEY)',
)
replace_once(
    app_root,
    'onDownloadUpdate = { CleanYourTvUpdater.download(context) },',
    'onDownloadUpdate = { if (updateDownloadProgress == null || updateDownloadProgress == 100) CleanYourTvUpdater.download(context) },\n                        updateDownloadProgress = updateDownloadProgress,',
)


# 0.9.35: explicit clean-only setup mode. This is a machine-owned choice (not presentation
# state): re-plan the signed action list with every launcher/HOME/helper action disabled.
setup_machine = upstream / "core" / "flows" / "src" / "main" / "kotlin" / "tv" / "cleanyour" / "core" / "flows" / "SetupMachine.kt"
flows_source = upstream / "core" / "flows" / "src" / "main" / "kotlin" / "tv" / "cleanyour" / "core" / "flows" / "Flows.kt"
setup_screens = upstream / "feature" / "setup" / "src" / "main" / "kotlin" / "tv" / "cleanyour" / "feature" / "setup" / "SetupScreens.kt"

replace_once(
    flows_source,
    "data class HomeHelper(val on: Boolean) : SetupIntent   // RFC-HELPER-1 (test build): the Customise switch \"Home button helper\", on only after its consent",
    "data class HomeHelper(val on: Boolean) : SetupIntent   // RFC-HELPER-1 (test build): the Customise switch \"Home button helper\", on only after its consent\n    data class CleanOnly(val on: Boolean) : SetupIntent   // 0.9.35: explicitly opt out of launcher and HOME changes",
)
replace_once(setup_machine, "    val helperOn: Boolean = false,", "    val helperOn: Boolean = false,\n    val cleanOnly: Boolean = false,")
replace_once(setup_machine, "    private var tv: Tv? = null", "    private var tv: Tv? = null\n    private var cleanOnly = false")
replace_once(
    setup_machine,
    "            is SetupIntent.HomeHelper -> if (deps.homeHelper) editDraft { it.copy(homeHelper = event.on) }",
    "            is SetupIntent.HomeHelper -> if (deps.homeHelper) editDraft { it.copy(homeHelper = event.on) }\n            is SetupIntent.CleanOnly -> if (after is SetupState.Found && !mutableExtras.value.starting && cleanOnly != event.on) {\n                cleanOnly = event.on\n                mutableExtras.update { it.copy(cleanOnly = cleanOnly, helperOn = false, helperOffer = false) }\n                scope.launch { tv?.let { replan(it) } }\n            }",
)
replace_once(setup_machine, "        val helper = helperStock != null && draft.homeHelper", "        val helper = helperStock != null && draft.homeHelper && !cleanOnly")
replace_once(
    setup_machine,
    "            installLauncher = possible || helper, takeover = possible, homeHelper = helper,",
    "            installLauncher = !cleanOnly && (possible || helper), takeover = !cleanOnly && possible, homeHelper = !cleanOnly && helper,",
)
replace_once(
    setup_machine,
    "        setupHome = look?.let { SetupHome(it.digest, drafts.choiceOf(sheet, saved, kept, cards, chosen, dim, dockChosen = draft.dock != null), photo?.takeIf { sendsNew }, saved, it.bytes) }",
    "        setupHome = if (cleanOnly) null else look?.let { SetupHome(it.digest, drafts.choiceOf(sheet, saved, kept, cards, chosen, dim, dockChosen = draft.dock != null), photo?.takeIf { sendsNew }, saved, it.bytes) }",
)
replace_once(setup_machine, "        val showOnTv = draft.level == Level.SIMPLE || draft.curtain", "        val showOnTv = !cleanOnly && (draft.level == Level.SIMPLE || draft.curtain)")
replace_once(
    setup_machine,
    "PlanRequest.Setup(res, choices.copy(curtain = showOnTv), launcher, draft.level, look?.config, look?.wallpaper, curtain)",
    "PlanRequest.Setup(res, choices.copy(curtain = showOnTv), launcher, draft.level, look?.config.takeUnless { cleanOnly }, look?.wallpaper.takeUnless { cleanOnly }, curtain)",
)
replace_once(
    setup_machine,
    "helperOffer = helperStock != null, helperOn = helper, helperWhyNot = helperWhyNot,",
    "helperOffer = helperStock != null && !cleanOnly, helperOn = helper, helperWhyNot = helperWhyNot, cleanOnly = cleanOnly,",
)

# S3: expose the choice, and make the primary action say what this plan will actually do.
replace_once(
    setup_screens,
    "    val kept = extras.homeKept as? HomeKept.Blocked ?: if (!summary.homeScreenPossible && extras.homePossible) HomeKept.Blocked(HomeBlock.NONE, extras.fireTv, extras.brand) else null",
    "    val kept = if (extras.cleanOnly) null else extras.homeKept as? HomeKept.Blocked ?: if (!summary.homeScreenPossible && extras.homePossible) HomeKept.Blocked(HomeBlock.NONE, extras.fireTv, extras.brand) else null",
)
replace_once(
    setup_screens,
    "    val nothingToDo = kept != null && off == 0 && !extras.helperOn",
    "    val nothingToDo = (extras.cleanOnly && off == 0) || (kept != null && off == 0 && !extras.helperOn)",
)
replace_once(
    setup_screens,
    "PrimaryButton(stringResource(if (off == 0) R.string.found_switch_home else R.string.found_make_clean), { actions.intent(SetupIntent.MakeItClean) }, loading = extras.starting)",
    "PrimaryButton(stringResource(if (extras.cleanOnly) R.string.found_clean_only_action else if (off == 0) R.string.found_switch_home else R.string.found_make_clean), { actions.intent(SetupIntent.MakeItClean) }, loading = extras.starting)\n                        if (extras.homePossible || extras.cleanOnly) TertiaryButton(stringResource(if (extras.cleanOnly) R.string.found_full_setup else R.string.found_clean_only), { actions.intent(SetupIntent.CleanOnly(!extras.cleanOnly)) })",
)
replace_once(
    setup_screens,
    "                        SecondaryButton(stringResource(R.string.action_done), { actions.intent(SetupIntent.LeaveAsIs) })\n                        if (extras.helperOffer || choices) TertiaryButton(stringResource(R.string.found_customise), { actions.intent(SetupIntent.Open(Sheet.CUSTOMISE)) })",
    "                        SecondaryButton(stringResource(R.string.action_done), { actions.intent(SetupIntent.LeaveAsIs) })\n                        if (extras.cleanOnly) TertiaryButton(stringResource(R.string.found_full_setup), { actions.intent(SetupIntent.CleanOnly(false)) })\n                        if (extras.helperOffer || choices) TertiaryButton(stringResource(R.string.found_customise), { actions.intent(SetupIntent.Open(Sheet.CUSTOMISE)) })",
)
replace_once(
    setup_screens,
    "                        PrimaryButton(stringResource(R.string.action_done), { actions.intent(SetupIntent.LeaveAsIs) }, guardObscured = false)\n                        if (extras.helperOffer || choices) TertiaryButton(stringResource(R.string.found_customise), { actions.intent(SetupIntent.Open(Sheet.CUSTOMISE)) })",
    "                        PrimaryButton(stringResource(R.string.action_done), { actions.intent(SetupIntent.LeaveAsIs) }, guardObscured = false)\n                        if (extras.cleanOnly) TertiaryButton(stringResource(R.string.found_full_setup), { actions.intent(SetupIntent.CleanOnly(false)) })\n                        if (extras.helperOffer || choices) TertiaryButton(stringResource(R.string.found_customise), { actions.intent(SetupIntent.Open(Sheet.CUSTOMISE)) })",
)
write_russian_strings(
    "feature/setup/src/main/res/values-ru/strings.xml",
    """<resources>
    <string name="found_clean_only">Только очистка</string>
    <string name="found_full_setup">Очистка и настройка лаунчера</string>
    <string name="found_clean_only_action">Очистить телевизор</string>
</resources>"""
)

# Ensure these entries exist in the actual Android resource XML (do not rely only on regex merge).
import xml.etree.ElementTree as _ET
_ru_file = upstream / "feature/setup/src/main/res/values-ru/strings.xml"
_ru_file.parent.mkdir(parents=True, exist_ok=True)
if _ru_file.exists():
    _ru_tree = _ET.parse(_ru_file)
    _ru_root = _ru_tree.getroot()
else:
    _ru_root = _ET.Element("resources")
for _name, _value in (
    ("found_clean_only", "Только очистка"),
    ("found_full_setup", "Очистка и настройка лаунчера"),
    ("found_clean_only_action", "Очистить телевизор"),
):
    _node = _ru_root.find(f"./string[@name='{_name}']")
    if _node is None:
        _node = _ET.SubElement(_ru_root, "string", {"name": _name})
    _node.text = _value
_ET.ElementTree(_ru_root).write(_ru_file, encoding="utf-8", xml_declaration=True)

# Kotlin references R.string.* at compile time. Keep these IDs in the default
# values resource set as well; values-ru alone may be filtered from some builds.
_default_file = upstream / "feature/setup/src/main/res/values/strings.xml"
_default_file.parent.mkdir(parents=True, exist_ok=True)
if _default_file.exists():
    _default_tree = _ET.parse(_default_file)
    _default_root = _default_tree.getroot()
else:
    _default_root = _ET.Element("resources")
for _name, _value in (
    ("found_clean_only", "Только очистка"),
    ("found_full_setup", "Очистка и настройка лаунчера"),
    ("found_clean_only_action", "Очистить телевизор"),
):
    _node = _default_root.find(f"./string[@name='{_name}']")
    if _node is None:
        _node = _ET.SubElement(_default_root, "string", {"name": _name})
    _node.text = _value
_ET.ElementTree(_default_root).write(_default_file, encoding="utf-8", xml_declaration=True)

print("Clean-only setup mode patch applied.")


# Enable online refresh of the signed add-on catalog. The bundled index remains the
# offline seed; every downloaded generation still passes the compiled minisign root,
# active-key signature, expiry and anti-rollback checks in :core:trust/:core:registry.
app_graph = upstream / "app" / "src" / "main" / "kotlin" / "tv" / "cleanyour" / "app" / "AppGraph.kt"
replace_once(
    app_graph,
    "import tv.cleanyour.core.registry.IndexRegistry",
    "import tv.cleanyour.core.registry.IndexRegistry\nimport tv.cleanyour.core.registry.IndexSources",
)
replace_once(
    app_graph,
    "sources = null, TrustRoots.indexVerifier()",
    'sources = IndexSources.under("https://raw.githubusercontent.com/udhaykumarbala/cleanyourtv-app/main/android/platform/src/main/assets/bundled"), TrustRoots.indexVerifier()',
)

# The raw GitHub host is used only for the four signed catalog documents. The
# fetcher still validates HTTPS and every redirect hop against this exact allowlist.
download_hosts = upstream / "core" / "trust" / "src" / "main" / "kotlin" / "tv" / "cleanyour" / "core" / "trust" / "DownloadHosts.kt"
replace_once(
    download_hosts,
    '        "api.github.com",',
    '        "api.github.com",\n        "raw.githubusercontent.com",',
)
full_network_config = upstream / "app" / "src" / "full" / "res" / "xml" / "network_security_config.xml"
replace_once(
    full_network_config,
    '        <domain includeSubdomains="false">api.github.com</domain>',
    '        <domain includeSubdomains="false">api.github.com</domain>\n        <domain includeSubdomains="false">raw.githubusercontent.com</domain>',
)

# Keep a manual refresh action visible even while the current signed index is fresh.
# A failed refresh never replaces the last verified catalog; installs remain gated by
# the registry's installableIndex() policy.
modules_screens = upstream / "feature" / "modules" / "src" / "main" / "kotlin" / "tv" / "cleanyour" / "feature" / "modules" / "ModulesScreens.kt"
replace_once(
    modules_screens,
    '''        is IndexView.Ready -> if (index.stale) Banner(stringResource(R.string.index_stale_warning), BannerTone.WARNING, Modifier.padding(top = 16.dp),
            actionLabel = stringResource(R.string.addmodule_refresh), onAction = { onIntent(ModulesIntent.Refresh) })''',
    '''        is IndexView.Ready -> if (index.stale) Banner(stringResource(R.string.index_stale_warning), BannerTone.WARNING, Modifier.padding(top = 16.dp),
            actionLabel = stringResource(R.string.addmodule_refresh), onAction = { onIntent(ModulesIntent.Refresh) })
            else TertiaryButton(stringResource(R.string.addmodule_refresh), { onIntent(ModulesIntent.Refresh) }, Modifier.padding(top = 16.dp))''',
)

# Avoid queuing multiple refresh requests when the user taps repeatedly.
modules_machine = upstream / "core" / "flows" / "src" / "main" / "kotlin" / "tv" / "cleanyour" / "core" / "flows" / "ModulesMachine.kt"
replace_once(
    modules_machine,
    "    private var detectJob: Job? = null",
    "    private var detectJob: Job? = null\n    private var refreshJob: Job? = null",
)
replace_once(
    modules_machine,
    "            ModulesIntent.Refresh -> if (addOns) scope.launch { attempt { d.registry.refresh() } }",
    "            ModulesIntent.Refresh -> if (addOns && refreshJob?.isActive != true) refreshJob = scope.launch { attempt { d.registry.refresh() } }",
)


# Make manual catalog refresh observable instead of silently leaving the screen unchanged.
modules_machine = upstream / "core" / "flows" / "src" / "main" / "kotlin" / "tv" / "cleanyour" / "core" / "flows" / "ModulesMachine.kt"
replace_once(
    modules_machine,
    "    private data class Step(val step: InstallStep?)",
    "    private data class Step(val step: InstallStep?)\n    private data class Refreshing(val value: Boolean)",
)
replace_once(
    modules_machine,
    "    val index: IndexView = IndexView.Loading,",
    "    val index: IndexView = IndexView.Loading,\n    val refreshing: Boolean = false,",
)
replace_once(
    modules_machine,
    "        is Step -> state.copy(install = event.step)",
    "        is Step -> state.copy(install = event.step)\n        is Refreshing -> state.copy(refreshing = event.value)",
)
replace_once(
    modules_machine,
    "            ModulesIntent.Refresh -> if (addOns && refreshJob?.isActive != true) refreshJob = scope.launch { attempt { d.registry.refresh() } }",
    """            ModulesIntent.Refresh -> if (addOns && refreshJob?.isActive != true) {
                send(Refreshing(true))
                refreshJob = scope.launch {
                    try { attempt { d.registry.refresh() } }
                    finally { send(Refreshing(false)) }
                }
            }""",
)
modules_screens = upstream / "feature" / "modules" / "src" / "main" / "kotlin" / "tv" / "cleanyour" / "feature" / "modules" / "ModulesScreens.kt"
replace_once(
    modules_screens,
    "private fun IndexNotice(index: IndexView, onIntent: (ModulesIntent) -> Unit) {\n    when (index) {",
    "private fun IndexNotice(index: IndexView, refreshing: Boolean, onIntent: (ModulesIntent) -> Unit) {\n    if (refreshing) LiveLine(stringResource(R.string.addmodule_loading)) else when (index) {",
)
replace_once(
    modules_screens,
    "IndexNotice(state.index, onIntent)",
    "IndexNotice(state.index, state.refreshing, onIntent)",
)
