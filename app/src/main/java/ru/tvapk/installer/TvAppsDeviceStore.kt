package ru.tvapk.installer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

private const val TV_APPS_SAVED_DEVICES_KEY = "tv_apps_saved_devices"

internal fun loadSavedTvAppsDevices(context: Context): List<TvDevice> = runCatching {
    val raw = context.getSharedPreferences("tv_installer", Context.MODE_PRIVATE)
        .getString(TV_APPS_SAVED_DEVICES_KEY, "[]") ?: "[]"
    val json = JSONArray(raw)
    (0 until json.length()).map { index ->
        val item = json.getJSONObject(index)
        TvDevice(item.getString("name"), item.getString("ip"), item.getInt("port"))
    }.filter { it.ip.isNotBlank() && it.port in 1..65535 }
}.getOrDefault(emptyList())

internal fun mergeSavedTvAppsDevices(context: Context, devices: List<TvDevice>): List<TvDevice> {
    val merged = devices
        .filter { it.ip.isNotBlank() && it.port in 1..65535 }
        .associateBy { "${it.ip}:${it.port}" }
        .values
        .sortedWith(compareBy<TvDevice> { it.name.lowercase() }.thenBy { it.ip }.thenBy { it.port })
    val json = JSONArray()
    merged.forEach { device ->
        json.put(
            JSONObject()
                .put("name", device.name)
                .put("ip", device.ip)
                .put("port", device.port)
        )
    }
    context.getSharedPreferences("tv_installer", Context.MODE_PRIVATE)
        .edit()
        .putString(TV_APPS_SAVED_DEVICES_KEY, json.toString())
        .apply()
    return merged
}
