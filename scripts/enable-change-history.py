#!/usr/bin/env python3
"""Integrate a read-only per-TV change history backed by the existing operation journal."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
up = root / "vendor" / "cleanyourtv-app"

def replace_once(path, old, new):
    s = path.read_text(encoding="utf-8")
    if new in s:
        return
    n = s.count(old)
    if n != 1:
        raise SystemExit(f"Expected one match in {path}: {old!r}; found {n}")
    path.write_text(s.replace(old, new), encoding="utf-8")

manage = up / "feature/manage/src/main/kotlin/tv/cleanyour/feature/manage/ManageScreens.kt"
flows = up / "core/flows/src/main/kotlin/tv/cleanyour/core/flows/ManageMachine.kt"
app = up / "app/src/main/kotlin/tv/cleanyour/app/AppRoot.kt"

replace_once(flows,
'''    RESTORE_POINT("restore"), TV_SETTINGS("tvsettings"),''',
'''    RESTORE_POINT("restore"), TV_SETTINGS("tvsettings"), CHANGE_HISTORY("changehistory"),''')

replace_once(manage,
'''onTvApps: () -> Unit = {}, onInstallHistory: () -> Unit = {}) {''',
'''onTvApps: () -> Unit = {}, onInstallHistory: () -> Unit = {}, onChangeHistory: () -> Unit = {}) {''')

replace_once(manage,
'''            ListRow("История установок", subtitle = "Результаты установки APK, дата, телевизор и ошибки", onClick = onInstallHistory)''',
'''            ListRow("История установок", subtitle = "Результаты установки APK, дата, телевизор и ошибки", onClick = onInstallHistory)
            ListRow("Журнал изменений", subtitle = "Что Clean Your TV менял на этом телевизоре", onClick = onChangeHistory)''')

replace_once(app,
'''import ru.tvapk.installer.InstallHistoryScreen''',
'''import ru.tvapk.installer.InstallHistoryScreen
import ru.tvapk.installer.ChangeHistoryScreen''')
replace_once(app,
'''    @Serializable data object InstallHistory : Dest''',
'''    @Serializable data class ChangeHistory(val tvId: String) : Dest
    @Serializable data object InstallHistory : Dest''')
replace_once(app,
'''        "installhistory" -> Dest.InstallHistory''',
'''        "installhistory" -> Dest.InstallHistory
        "changehistory" -> id?.let { Dest.ChangeHistory(it) }''')
replace_once(app,
'''ManageScreen(state, m::dispatch, onInstallApk = { go("installapk") }, onTvApps = { go("tvapps") }, onInstallHistory = { go("installhistory") })''',
'''ManageScreen(state, m::dispatch, onInstallApk = { go("installapk") }, onTvApps = { go("tvapps") }, onInstallHistory = { go("installhistory") }, onChangeHistory = { state.current?.id?.let { go("changehistory/${it.value}") } })''')
replace_once(app,
'''                entry<Dest.InstallHistory> {''',
'''                entry<Dest.ChangeHistory> { key ->
                    ChangeHistoryScreen(tvId = key.tvId, ledger = graph.ledger, onBack = { go("back") })
                }
                entry<Dest.InstallHistory> {''')

screen = up / "app/src/main/kotlin/ru/tvapk/installer/ChangeHistoryScreen.kt"
screen.parent.mkdir(parents=True, exist_ok=True)
screen.write_text("""package ru.tvapk.installer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.cleanyour.core.flows.RunLedger
import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.model.Op
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun ChangeRecord.title(): String = when (val value = op) {
    is Op.Disable -> "Отключено: ${value.pkg.value}"
    is Op.Enable -> "Включено: ${value.pkg.value}"
    is Op.DefaultState -> "Сброшено состояние: ${value.pkg.value}"
    is Op.Uninstall -> "Удалено приложение: ${value.pkg.value}"
    is Op.Install -> "Установлено приложение: ${value.pkg.value}"
    is Op.InstallStream -> "Установлено приложение: ${value.pkg.value}"
    is Op.SettingsPut -> "Изменена настройка: ${value.key.key}"
    is Op.SetHome -> "Изменён главный экран: ${value.component.flat}"
    is Op.AccessibilityAdd -> "Добавлена служба специальных возможностей: ${value.component.flat}"
    is Op.AccessibilityRemove -> "Удалена служба специальных возможностей: ${value.component.flat}"
    is Op.PushAsset -> "Записан файл конфигурации"
    is Op.RemoveFile -> "Удалён файл конфигурации"
    is Op.Mkdir -> "Создана папка"
    is Op.RestartHome -> "Перезапущен главный экран"
    is Op.StartActivity -> "Запущено приложение: ${value.component.flat}"
    is Op.ArmHomeWatchdog, is Op.ConfirmHome, is Op.KillWatchdog, is Op.ArmHelperWatchdog -> "Служебная операция безопасности"
    is Op.Reboot -> "Перезагрузка телевизора"
    is Op.DebugOff -> "Отключена отладка"
    is Op.CurtainCue -> "Обновлён экран процесса"
    Op.PressHome -> "Нажата кнопка Home"
    is Op.Read, is Op.VerifyHome, is Op.Settle -> "Проверка"
}

private fun ChangeRecord.priorLine(): String? {
    val p = prior ?: return null
    val parts = buildList {
        p.packageState?.let { add("Состояние до: $it") }
        p.settingValue?.let { add("Значение до: $it") }
        p.home?.let { add("Главный экран до: ${it.flat}") }
        p.present?.let { add(if (it) "Файл существовал до изменения" else "Файла не было до изменения") }
        if (p.overlay != null) add("Предыдущая конфигурация сохранена")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("\\n")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangeHistoryScreen(tvId: String, ledger: RunLedger, onBack: () -> Unit) {
    var entries by remember(tvId) { mutableStateOf<List<ChangeRecord>>(emptyList()) }
    var localEntries by remember(tvId) { mutableStateOf<List<TvAppsHistoryEntry>>(emptyList()) }
    var loading by remember(tvId) { mutableStateOf(true) }
    var error by remember(tvId) { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }

    val context = LocalContext.current
    LaunchedEffect(tvId, refresh) {
        loading = true
        error = false
        try {
            entries = withContext(Dispatchers.IO) { ledger.changes(TvId(tvId)).sortedByDescending { it.atMs } }
            localEntries = withContext(Dispatchers.IO) { TvAppsHistory.read(context, tvId) }
        } catch (_: Exception) {
            error = true
            entries = emptyList()
        }
        loading = false
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Журнал изменений") },
            navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
            actions = { TextButton(onClick = { refresh++ }) { Text("Обновить") } }
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Записей: ${entries.size + localEntries.size}", style = MaterialTheme.typography.titleSmall)
            when {
                loading -> Text("Загружаем журнал…")
                error -> Text("Не удалось прочитать журнал. Попробуйте обновить.")
                entries.isEmpty() && localEntries.isEmpty() -> Text("Изменений пока нет. Здесь появятся завершённые действия Clean Your TV на этом телевизоре.")
                else -> {
                    localEntries.forEach { entry ->
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("${entry.action}: ${entry.packageName}", style = MaterialTheme.typography.titleSmall)
                                Text(SimpleDateFormat("dd.MM.yyyy · HH:mm:ss", Locale.getDefault()).format(Date(entry.atMs)), style = MaterialTheme.typography.bodySmall)
                                Text("Операция выполнена через управление приложениями телевизора", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    entries.forEach { entry ->
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(entry.title(), style = MaterialTheme.typography.titleSmall)
                            Text(SimpleDateFormat("dd.MM.yyyy · HH:mm:ss", Locale.getDefault()).format(Date(entry.atMs)),
                                style = MaterialTheme.typography.bodySmall)
                            Text("Шаг: ${entry.step + 1}", style = MaterialTheme.typography.bodySmall)
                            entry.priorLine()?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    }
                }
            }
        }
    }
}
""", encoding="utf-8")
print("Read-only TV change history integration applied.")
