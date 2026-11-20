package ru.tvapk.installer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

data class InstallHistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val apkName: String,
    val packageName: String?,
    val tvName: String,
    val connection: String,
    val success: Boolean,
    val error: String?
)

object InstallHistoryStore {
    private const val PREFS = "clean_your_tv_install_history"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 200
    private val lock = Any()

    fun load(context: Context): List<InstallHistoryEntry> = synchronized(lock) {
        readUnlocked(context)
    }

    suspend fun record(context: Context, entry: InstallHistoryEntry) {
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                runCatching {
                    val entries = readUnlocked(context).toMutableList()
                    entries.add(0, entry)
                    val json = JSONArray()
                    entries.take(MAX_ENTRIES).forEach { item -> json.put(item.toJson()) }
                    context.applicationContext
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_ENTRIES, json.toString())
                        .commit()
                }
            }
        }
    }

    suspend fun clear(context: Context) {
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                context.applicationContext
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .remove(KEY_ENTRIES)
                    .commit()
            }
        }
    }

    private fun readUnlocked(context: Context): List<InstallHistoryEntry> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        InstallHistoryEntry(
                            id = item.optString("id"),
                            timestamp = item.optLong("timestamp"),
                            apkName = item.optString("apkName", "APK-файл"),
                            packageName = item.optString("packageName").takeIf { it.isNotBlank() && it != "null" },
                            tvName = item.optString("tvName", "Телевизор"),
                            connection = item.optString("connection", "—"),
                            success = item.optBoolean("success"),
                            error = item.optString("error").takeIf { it.isNotBlank() && it != "null" }
                        )
                    )
                }
            }.sortedByDescending { it.timestamp }.take(MAX_ENTRIES)
        }.getOrDefault(emptyList())
    }

    private fun InstallHistoryEntry.toJson() = JSONObject().apply {
        put("id", id)
        put("timestamp", timestamp)
        put("apkName", apkName)
        put("packageName", packageName ?: JSONObject.NULL)
        put("tvName", tvName)
        put("connection", connection)
        put("success", success)
        put("error", error ?: JSONObject.NULL)
    }
}
