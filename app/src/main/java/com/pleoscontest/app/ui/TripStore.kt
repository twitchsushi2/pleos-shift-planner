package com.pleoscontest.app.ui

import android.content.Context
import com.pleoscontest.app.shift.Driver
import com.pleoscontest.app.shift.Place
import com.pleoscontest.app.shift.RestArea
import com.pleoscontest.app.shift.Route
import com.pleoscontest.app.shift.TripState
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime

/**
 * 앱이 꺼졌다 켜져도 이어 가도록 저장하는 여행. 계획은 저장하지 않고 이어 갈 때 지금 위치에서 다시 짠다.
 * [route]는 달리던 경로 그대로(내비가 고른 경로면 그 휴게소와 거리)다. 지금까지 온 거리가 이 경로 기준이라 같이 저장한다.
 * [savedAt]은 마지막으로 적은 때(벽시계)다. 꺼져 있던 시간을 운전 시간에 더하는 데 쓴다.
 */
data class SavedTrip(
    val from: Place?, val to: Place, val drivers: List<Driver>, val trip: TripState, val route: Route?, val savedAt: Long,
    /** [route]가 내비가 안내하던 경로 그대로다. 그래야 내비의 남은 거리로 위치를 맞출 수 있다. */
    val routeFromNavi: Boolean = false,
)

/**
 * 진행 중인 여행을 기기에 저장한다. 내비를 오래 앞에 띄워 두면 시스템이 앱을 정리할 수 있어서다.
 * 출발할 때 경로 양 끝과 운전자를, 달리는 동안 진행 상황을 적는다.
 */
class TripStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("trip", Context.MODE_PRIVATE)

    /** [from]은 차의 위치를 아직 모르면 null이다. 경로를 같이 저장하니 출발지가 없어도 이어 갈 수 있다. */
    fun start(from: Place?, to: Place, drivers: List<Driver>, trip: TripState, route: Route, routeFromNavi: Boolean) {
        prefs.edit()
            .putBoolean(KEY_FROM_NAVI, routeFromNavi)
            .putString(KEY_ROUTE, JSONObject().put("from", from?.toJson() ?: JSONObject.NULL).put("to", to.toJson()).toString())
            .putString(KEY_PATH, route.toJson().toString())
            .putString(KEY_DRIVERS, JSONArray().apply { drivers.forEach { put(it.toJson()) } }.toString())
            .putString(KEY_TRIP, trip.toJson().toString())
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    fun progress(trip: TripState) = prefs.edit()
        .putString(KEY_TRIP, trip.toJson().toString())
        .putLong(KEY_SAVED_AT, System.currentTimeMillis())
        .apply()

    /** 주행 중 경로가 바뀌었다(내비가 다시 탐색했다). */
    fun route(route: Route) = prefs.edit().putString(KEY_PATH, route.toJson().toString()).putBoolean(KEY_FROM_NAVI, true).apply()

    fun clear() = prefs.edit().clear().apply()

    /** 마지막으로 적은 지 [MAX_AGE_MILLIS]가 지난 여행은 이어 가지 않는다. 며칠 뒤 앱을 켰을 때 옛 여행이 살아나지 않게. */
    fun load(): SavedTrip? = runCatching {
        val savedAt = prefs.getLong(KEY_SAVED_AT, 0L)
        if (System.currentTimeMillis() - savedAt > MAX_AGE_MILLIS) {
            clear()
            return null
        }
        val route = JSONObject(prefs.getString(KEY_ROUTE, null) ?: return null)
        val driversJson = JSONArray(prefs.getString(KEY_DRIVERS, null) ?: return null)
        val drivers = (0 until driversJson.length()).map { driversJson.getJSONObject(it).toDriver() }
        val trip = JSONObject(prefs.getString(KEY_TRIP, null) ?: return null).toTrip(drivers)
        val path = prefs.getString(KEY_PATH, null)?.let { runCatching { JSONObject(it).toRoute() }.getOrNull() }
        val from = if (route.isNull("from")) null else route.getJSONObject("from").toPlace()
        SavedTrip(from, route.getJSONObject("to").toPlace(), drivers, trip, path, savedAt, path != null && prefs.getBoolean(KEY_FROM_NAVI, false))
    }.getOrNull()

    private fun Place.toJson() = JSONObject().put("name", name).put("lat", lat).put("lng", lng)

    private fun JSONObject.toPlace() = Place(getString("name"), getDouble("lat"), getDouble("lng"))

    private fun Driver.toJson() = JSONObject()
        .put("id", id).put("name", name).put("profileId", profileId ?: "")
        .put("maxContinuousMinutes", maxContinuousMinutes).put("avoidNight", avoidNight).put("novice", novice)

    private fun JSONObject.toDriver() = Driver(
        id = getString("id"),
        name = getString("name"),
        profileId = optString("profileId").ifEmpty { null },
        maxContinuousMinutes = optInt("maxContinuousMinutes", 120),
        avoidNight = optBoolean("avoidNight"),
        novice = optBoolean("novice"),
    )

    private fun TripState.toJson() = JSONObject()
        .put("km", km).put("now", now.toString()).put("driverId", driver.id)
        .put("minutesSinceShift", minutesSinceShift)
        .put("drivenMinutes", JSONObject(drivenMinutes)).put("drivenKm", JSONObject(drivenKm)).put("nightMinutes", JSONObject(nightMinutes))
        .put("recentSpeedKmh", recentSpeedKmh ?: JSONObject.NULL)

    private fun JSONObject.toTrip(drivers: List<Driver>) = TripState(
        km = getDouble("km"),
        now = LocalDateTime.parse(getString("now")),
        driver = drivers.first { it.id == getString("driverId") },
        minutesSinceShift = getDouble("minutesSinceShift"),
        drivenMinutes = getJSONObject("drivenMinutes").toMap(),
        drivenKm = getJSONObject("drivenKm").toMap(),
        nightMinutes = getJSONObject("nightMinutes").toMap(),
        recentSpeedKmh = if (isNull("recentSpeedKmh")) null else getDouble("recentSpeedKmh"),
    )

    // 지도 선(path)은 크고 이어 갈 때 다시 그리면 되므로 저장하지 않는다.
    private fun Route.toJson() = JSONObject()
        .put("totalKm", totalKm).put("etaMinutes", etaMinutes)
        .put("restAreas", JSONArray().apply {
            restAreas.forEach {
                put(
                    JSONObject().put("id", it.id).put("name", it.name).put("km", it.km).put("lat", it.lat).put("lng", it.lng)
                        .put("facilities", JSONArray(it.facilities)).put("truck", it.truck),
                )
            }
        })

    private fun JSONObject.toRoute(): Route {
        val areas = getJSONArray("restAreas")
        return Route(
            getDouble("totalKm"), getInt("etaMinutes"),
            (0 until areas.length()).map { index ->
                val o = areas.getJSONObject(index)
                val facilities = o.optJSONArray("facilities")
                RestArea(
                    o.getString("id"), o.getString("name"), o.getDouble("km"), o.optDouble("lat", 0.0), o.optDouble("lng", 0.0),
                    facilities?.let { f -> (0 until f.length()).map { f.getString(it) } }.orEmpty(),
                    truck = o.optBoolean("truck"),
                )
            },
        )
    }

    private fun JSONObject.toMap(): Map<String, Double> = keys().asSequence().associateWith { getDouble(it) }

    private companion object {
        const val KEY_ROUTE = "route"
        const val KEY_DRIVERS = "drivers"
        const val KEY_TRIP = "trip"
        const val KEY_SAVED_AT = "saved_at"
        const val KEY_PATH = "path"
        const val KEY_FROM_NAVI = "from_navi"
        /** 휴게소에서 길게 쉬어도 넘지 않을 시간. 운전 시간 기록이 10초마다 적히니 달리는 중에는 늘 새것이다. */
        const val MAX_AGE_MILLIS = 6 * 60 * 60 * 1000L
    }
}
