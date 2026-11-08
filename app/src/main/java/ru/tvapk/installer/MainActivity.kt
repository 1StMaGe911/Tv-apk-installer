package ru.tvapk.installer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Bg = Color(0xFF10131B)
private val Panel = Color(0xFF1B2230)
private val Accent = Color(0xFF69B7FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TvInstallerApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvInstallerApp() {
    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("5555") }
    var status by remember { mutableStateOf("Не подключено") }
    var selectedFile by remember { mutableStateOf("APK-файл не выбран") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedFile = if (uri == null) "APK-файл не выбран" else uri.lastPathSegment ?: "Файл выбран"
    }

    MaterialTheme(colorScheme = darkColorScheme(
        background = Bg, surface = Panel, primary = Accent,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Scaffold(containerColor = Bg) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tv, null, tint = Accent, modifier = Modifier.size(38.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("TV APK Installer", fontSize = 23.sp, color = Color.White)
                        Text("Установка приложений на Android TV", color = Color.LightGray, fontSize = 12.sp)
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Подключение по Wi-Fi ADB", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(value = ip, onValueChange = { ip = it }, label = { Text("IP-адрес телевизора") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Порт ADB (обычно 5555)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text("Состояние: $status", color = Color.LightGray)
                        Button(onClick = { status = "Экран подключения готов; ADB-протокол будет добавлен следующим этапом" }, modifier = Modifier.fillMaxWidth()) {
                            Text("Подключиться")
                        }
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Android, null, tint = Accent)
                            Spacer(Modifier.width(8.dp))
                            Text("Установка APK", style = MaterialTheme.typography.titleMedium)
                        }
                        Text(selectedFile, color = Color.LightGray)
                        OutlinedButton(onClick = { picker.launch("application/vnd.android.package-archive") }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.FolderOpen, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Выбрать APK")
                        }
                        Button(onClick = { status = "Сначала необходимо реализовать и проверить ADB-подключение" }, modifier = Modifier.fillMaxWidth()) {
                            Text("Установить на телевизор")
                        }
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("USB ADB", style = MaterialTheme.typography.titleMedium)
                        Text("USB-режим появится в следующей версии. Нужны USB OTG/Host на телефоне, подходящий кабель и поддержка ADB со стороны телевизора.", color = Color.LightGray)
                    }
                }
                Text("Важно: VIDAA не поддерживает установку Android APK. Приложение предназначено для Android TV / Google TV и совместимых систем.", color = Color(0xFFFFC66D), fontSize = 12.sp)
                Text("Версия 0.1.0 • демонстрационный интерфейс", color = Color.Gray, fontSize = 11.sp)
            }
        }
    }
}
