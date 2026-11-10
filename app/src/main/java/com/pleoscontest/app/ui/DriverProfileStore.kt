package com.pleoscontest.app.ui

import android.content.Context
import com.pleoscontest.app.shift.DriverProfile
import org.json.JSONArray
import org.json.JSONObject

/** 등록한 운전자를 기기에 저장한다. 앱을 다시 켜도 유지된다. */
class DriverProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): List<DriverProfile> {
        val saved = prefs.getString(KEY, null) ?: return emptyList()
        val array = runCatching { JSONArray(saved) }.getOrNull() ?: return emptyList()
        // 한 명의 자료가 깨져도 나머지는 살린다.
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val o = array.getJSONObject(index)
                DriverProfile(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    novice = o.optBoolean("novice"),
                    avoidNight = o.optBoolean("avoidNight"),
                    music = o.optString("music"),
                    playlistUrl = o.optString("playlistUrl"),
                    temperature = o.optString("temperature"),
                    seatHeat = if (o.has("seatHeat")) o.getInt("seatHeat") else null,
                    wheelHeat = if (o.has("wheelHeat")) o.getBoolean("wheelHeat") else null,
                    seatMemory = if (o.has("seatMemory")) o.getInt("seatMemory") else null,
                    note = o.optString("note"),
                )
            }.getOrNull()
        }
    }

    fun save(profiles: List<DriverProfile>) {
        val array = JSONArray()
        profiles.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id).put("name", it.name)
                    .put("novice", it.novice).put("avoidNight", it.avoidNight)
                    .put("music", it.music).put("playlistUrl", it.playlistUrl).put("temperature", it.temperature).put("note", it.note)
                    .apply {
                        it.seatHeat?.let { level -> put("seatHeat", level) }
                        it.wheelHeat?.let { on -> put("wheelHeat", on) }
                        it.seatMemory?.let { slot -> put("seatMemory", slot) }
                    },
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "driver_profiles"
    }
}
