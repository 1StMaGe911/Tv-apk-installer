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
    target = upstream / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content.strip() + "\n", encoding="utf-8")

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
