package ru.tvapk.installer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallHistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val currentLocale = LocalConfiguration.current.locales[0]
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(emptyList<InstallHistoryEntry>()) }
    var loading by remember { mutableStateOf(true) }
    var confirmClear by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(refreshKey) {
        loading = true
        entries = withContext(Dispatchers.IO) { InstallHistoryStore.load(context) }
        loading = false
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить историю?") },
            text = { Text("Все записи об установках будут удалены с телефона.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        InstallHistoryStore.clear(context)
                        refreshKey++
                    }
                }) { Text("Очистить") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("История установок") },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
                actions = {
                    TextButton(onClick = { refreshKey++ }) { Text("Обновить") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Записей: ${entries.size}", style = MaterialTheme.typography.titleSmall)
                TextButton(enabled = entries.isNotEmpty(), onClick = { confirmClear = true }) {
                    Text("Очистить историю")
                }
            }
            when {
                loading -> Text("Загружаем историю…")
                entries.isEmpty() -> Text("История пока пуста. Здесь появятся результаты установок APK по Wi-Fi и USB.")
                else -> entries.forEach { entry ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(
                                (if (entry.success) "✓ " else "✕ ") + entry.apkName,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (entry.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            Text(
                                SimpleDateFormat("dd.MM.yyyy · HH:mm:ss", currentLocale).format(Date(entry.timestamp)),
                                style = MaterialTheme.typography.bodySmall
                            )
                            entry.packageName?.let { Text("Пакет: $it", style = MaterialTheme.typography.bodySmall) }
                            Text("Телевизор: ${entry.tvName}", style = MaterialTheme.typography.bodySmall)
                            Text("Подключение: ${entry.connection}", style = MaterialTheme.typography.bodySmall)
                            if (!entry.success && !entry.error.isNullOrBlank()) {
                                Text("Ошибка: ${entry.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
