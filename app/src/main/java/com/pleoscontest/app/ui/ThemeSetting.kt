package com.pleoscontest.app.ui

import android.content.Context

enum class ThemeMode(val label: String) {
    SYSTEM("시스템"),
    LIGHT("라이트"),
    DARK("다크"),
}

/** 고른 테마를 기기에 저장한다. 앱을 다시 켜도 유지된다. */
class ThemeSetting(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): ThemeMode =
        ThemeMode.entries.firstOrNull { it.name == prefs.getString(KEY, null) } ?: ThemeMode.SYSTEM

    fun save(mode: ThemeMode) = prefs.edit().putString(KEY, mode.name).apply()

    /** 화물차 휴게소도 교대·휴식 장소로 쓸지. 처음에는 꺼져 있다(승용차 기준). */
    fun loadTruckStops(): Boolean = prefs.getBoolean(KEY_TRUCK, false)

    fun saveTruckStops(on: Boolean) = prefs.edit().putBoolean(KEY_TRUCK, on).apply()

    private companion object {
        const val KEY = "theme"
        const val KEY_TRUCK = "truck_stops"
    }
}
