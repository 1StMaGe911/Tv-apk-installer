package ru.tvapk.installer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class TvAppsHistoryEntry(val atMs: Long, val action: String, val detail: String)

object TvAppsHistory {
    private const val PREFS = "tv_apps_operation_history"
    private const val MAX_ENTRIES = 500

    @Synchronized
    fun record(context: Context, tvId: String, action: String, detail: String) {
        if (tvId.isBlank() || detail.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "tv_" + tvId
        val current = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrDefault(JSONArray())
        val updated = JSONArray()
        for (index in (current.length() - MAX_ENTRIES + 1).coerceAtLeast(0) until current.length()) {
            current.optJSONObject(index)?.let(updated::put)
        }
        updated.put(JSONObject().put("atMs", System.currentTimeMillis()).put("action", action).put("detail", detail))
        prefs.edit().putString(key, updated.toString()).apply()
    }

    @Synchronized
    fun read(context: Context, tvId: String): List<TvAppsHistoryEntry> {
        if (tvId.isBlank()) return emptyList()
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("tv_" + tvId, "[]")
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val atMs = item.optLong("atMs", 0L)
                val action = item.optString("action")
                val detail = item.optString("detail").ifBlank { item.optString("packageName") }
                if (atMs > 0L && action.isNotBlank() && detail.isNotBlank()) add(TvAppsHistoryEntry(atMs, action, detail))
            }
        }.sortedByDescending { it.atMs }
    }
}
