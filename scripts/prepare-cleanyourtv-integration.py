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

replace_once(app_gradle, "versionCode = 30", "versionCode = 190")
replace_once(app_gradle, 'versionName = "0.4.8"', 'versionName = "0.9.139"')
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
    "    lateinit var graph: AppGraph\n        private set\n    var startupFailure: Throwable? = null\n        private set\n",
)
replace_once(
    cyt_application,
    """        CytLog.init(File(noBackupFilesDir, "logs"))
        graph = AppGraph(this)
        OperationService.Bus.channelName = getString(R.string.app_channel_setup_name)
        watchRuns()
        graph.sessionEnd.watch(graph.scope)
""",
    """    }

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
    "        super.onCreate(savedInstanceState)\n        val cytApplication = application as CytApplication\n        cytApplication.initializeGraph()\n        if (cytApplication.startupFailure != null) {\n            setContent { TvInstallerApp() }\n            return\n        }\n        graph.credentialGate.attach(this)",
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
print("Clean Your TV integration patched: applicationId ru.tvapk.installer, version 0.9.139 (190).")


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
    target.write_text('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    ' + entries + '\n</resources>\n', encoding="utf-8")

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
    <string name="group_brand">Дополнения {brand}</string>
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
