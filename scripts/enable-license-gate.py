#!/usr/bin/env python3
"""Inject the Supabase device-bound license gate into the pinned Clean Your TV source."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
upstream = root / "vendor" / "cleanyourtv-app"
activity = upstream / "app/src/main/kotlin/tv/cleanyour/app/MainActivity.kt"
target = upstream / "app/src/main/kotlin/tv/cleanyour/app/LicenseGate.kt"

source = r'''package tv.cleanyour.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val LICENSE_URL = "https://ttrxesailsvsesfddnco.supabase.co"
private const val SUPABASE_PUBLISHABLE_KEY = "sb_publishable_KIErUnjR_auUzqK69fe4RQ_ls98eoo1"
private const val LICENSE_GRACE_MS = 30L * 24L * 60L * 60L * 1000L
private const val LICENSE_PREFS = "cytv_license"

@Composable
internal fun LicenseGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ready by remember { mutableStateOf(false) }
    var keyText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun verifyStored(): Boolean = withContext(Dispatchers.IO) {
        val saved = LicenseStore.readKey(context) ?: return@withContext false
        val last = LicenseStore.lastCheck(context)
        val now = System.currentTimeMillis()
        if (last > 0L && now >= last && now - last < LICENSE_GRACE_MS) return@withContext true
        try {
            val ok = LicenseApi.call("check-license", saved, LicenseStore.deviceId(context))
            if (ok) LicenseStore.markChecked(context)
            ok
        } catch (_: Exception) {
            false
        }
    }

    LaunchedEffect(Unit) {
        ready = verifyStored()
        busy = false
    }

    if (ready) {
        content()
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("Clean Your TV", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("Для использования приложения введите лицензионный ключ.")
            Spacer(Modifier.height(18.dp))
            if (busy) {
                CircularProgressIndicator()
            } else {
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '-' }.take(32) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Лицензионный ключ") },
                    placeholder = { Text("CYTV-XXXX-XXXX-XXXX-XXXX") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters)
                )
                Spacer(Modifier.height(10.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = {
                        if (keyText.isBlank() || busy) return@Button
                        busy = true
                        error = null
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                try {
                                    val device = LicenseStore.deviceId(context)
                                    val activated = LicenseApi.call("activate", keyText.trim(), device)
                                    if (activated) {
                                        LicenseStore.saveKey(context, keyText.trim())
                                        LicenseStore.markChecked(context)
                                    }
                                    activated
                                } catch (_: Exception) { false }
                            }
                            ready = ok
                            if (!ok) error = "Ключ не принят. Проверьте его или подключение к интернету."
                            busy = false
                        }
                    },
                    enabled = !busy && keyText.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Активировать") }
                Spacer(Modifier.height(18.dp))
                Text("Нет ключа? Для покупки обратитесь в Telegram:")
                Text(
                    "@St_Mage",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp).clickable(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/St_Mage"))) }
                    })
                )
            }
        }
    }
}

private object LicenseApi {
    fun call(endpoint: String, key: String, deviceId: String): Boolean {
        val connection = (URL("$LICENSE_URL/functions/v1/$endpoint").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            connection.setRequestProperty("Authorization", "Bearer $SUPABASE_PUBLISHABLE_KEY")
            val body = JSONObject().put("key", key).put("deviceId", deviceId).toString()
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() } ?: return false
            return JSONObject(response).optBoolean("ok", false)
        } finally {
            connection.disconnect()
        }
    }
}

private object LicenseStore {
    private const val ALIAS = "cleanyourtv_license_key_v1"
    private fun prefs(context: Context) = context.getSharedPreferences(LICENSE_PREFS, Context.MODE_PRIVATE)

    fun deviceId(context: Context): String {
        val p = prefs(context)
        val existing = p.getString("device_id", null)
        if (existing != null) return existing
        val created = UUID.randomUUID().toString()
        p.edit().putString("device_id", created).apply()
        return created
    }

    fun lastCheck(context: Context): Long = prefs(context).getLong("last_check", 0L)
    fun markChecked(context: Context) { prefs(context).edit().putLong("last_check", System.currentTimeMillis()).apply() }

    fun saveKey(context: Context, key: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(key.toByteArray(StandardCharsets.UTF_8))
        val packed = ByteBuffer.allocate(4 + cipher.iv.size + encrypted.size)
            .putInt(cipher.iv.size).put(cipher.iv).put(encrypted).array()
        prefs(context).edit().putString("key_cipher", Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun readKey(context: Context): String? = runCatching {
        val encoded = prefs(context).getString("key_cipher", null) ?: return null
        val packed = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP))
        val ivSize = packed.int
        require(ivSize in 12..16)
        val iv = ByteArray(ivSize).also { packed.get(it) }
        val encrypted = ByteArray(packed.remaining()).also { packed.get(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }.getOrNull()

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
'''

if not target.exists():
    target.write_text(source, encoding="utf-8")
else:
    current = target.read_text(encoding="utf-8")
    if current != source:
        raise SystemExit(f"Refusing to overwrite existing {target}")

main = activity.read_text(encoding="utf-8")
old = "else AppRoot(graph, s, onExit = { finish() })"
new = "else LicenseGate { AppRoot(graph, s, onExit = { finish() }) }"
if new not in main:
    if main.count(old) != 1:
        raise SystemExit("Could not safely locate MainActivity app entry point")
    activity.write_text(main.replace(old, new), encoding="utf-8")
print("Supabase license gate and Telegram purchase contact injected.")
