package com.pleoscontest.app.ui

import android.util.Log
import com.pleoscontest.app.shift.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * 지도에 그릴 실제 도로 모양을 OSRM 공개 서버에서 받는다. 키 없이 쓰지만 시연·개발용 서버라 출시 전에는 바꿔야 한다.
 * 출발지·목적지·휴게소 좌표가 서버로 간다. 받지 못하면 null을 돌려주고, 지도는 내장 고속도로 선으로 그린다.
 * 교대 계획(거리·휴게소)은 내장 도로망으로 계산하므로, 계획의 휴게소를 거쳐 가는 길을 받아 선과 휴게소가 어긋나지 않게 한다.
 */
object RoadRoutes {
    private val cache = mutableMapOf<String, List<GeoPoint>>()

    /**
     * [via]를 들르는 도로 경로. 휴게소는 진행 방향 쪽(우측)에서 들르게 한다.
     * 들르는 길이 [expectedKm]보다 지나치게 길면(반대편 휴게소로 돌아 나오는 등) 들르지 않는 길로 대신한다.
     */
    suspend fun fetch(start: GeoPoint, end: GeoPoint, via: List<GeoPoint>, expectedKm: Double?): List<GeoPoint>? {
        val key = (listOf(start) + via + end).joinToString(";") { String.format(Locale.US, "%.5f,%.5f", it.lng, it.lat) }
        synchronized(cache) { cache[key] }?.let { return it }
        return withContext(Dispatchers.IO) {
            val withStops = if (via.isEmpty()) null else request(listOf(start) + via + end, curbForVia = true)
                ?.takeIf { (_, km) -> expectedKm == null || km <= expectedKm * MAX_DETOUR }
            (withStops ?: request(listOf(start, end), curbForVia = false))?.first
                ?.also { synchronized(cache) { cache[key] = it } }
        }
    }

    private fun request(points: List<GeoPoint>, curbForVia: Boolean): Pair<List<GeoPoint>, Double>? = runCatching {
        val coordinates = points.joinToString(";") { String.format(Locale.US, "%.5f,%.5f", it.lng, it.lat) }
        val approaches = if (curbForVia) {
            "&approaches=" + points.indices.joinToString(";") { if (it == 0 || it == points.lastIndex) "unrestricted" else "curb" }
        } else {
            ""
        }
        val connection = URL("$SERVER/route/v1/driving/$coordinates?overview=full&geometries=geojson$approaches").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val route = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getJSONArray("routes").getJSONObject(0)
            val line = route.getJSONObject("geometry").getJSONArray("coordinates")
            val path = (0 until line.length()).map { index ->
                val pair = line.getJSONArray(index)
                GeoPoint(pair.getDouble(1), pair.getDouble(0))
            }
            path to route.getDouble("distance") / 1000
        } finally {
            connection.disconnect()
        }
    }.onFailure { Log.w(TAG, "도로 경로를 받지 못함", it) }
        .getOrNull()
        ?.takeIf { it.first.size > 1 }

    private const val TAG = "RoadRoutes"
    private const val SERVER = "https://router.project-osrm.org"
    private const val USER_AGENT = "PleosShiftPlanner/0.1 (contest demo)"
    private const val TIMEOUT_MS = 10_000
    /** 휴게소를 들르는 길이 내장 경로보다 이만큼 넘게 길면 엉뚱하게 돈 것으로 본다. */
    private const val MAX_DETOUR = 1.15
}
