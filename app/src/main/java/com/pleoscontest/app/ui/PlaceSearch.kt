package com.pleoscontest.app.ui

import android.util.Log
import com.pleoscontest.app.shift.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * 앱 안에서 장소를 찾는다. Pleos 내비 SDK는 검색 결과를 앱에 돌려주지 않아, 키 없이 쓰는 Photon(OpenStreetMap 자료) 검색을 쓴다.
 * 역·건물·가게 이름은 잘 찾지만 도로명 상세 주소는 약하다. 상세 주소는 "내비에서 검색"으로 찾게 한다.
 * 검색어와 차의 대략적인 위치(가까운 곳을 먼저 보이려고)가 서버로 간다.
 */
object PlaceSearch {
    data class Found(val place: Place, val address: String)

    suspend fun search(query: String, near: Place?): List<Found> = withContext(Dispatchers.IO) {
        val bias = near?.let { String.format(Locale.US, "&lat=%.3f&lon=%.3f", it.lat, it.lng) }.orEmpty()
        val url = "$SERVER/api/?q=${URLEncoder.encode(query.trim(), "UTF-8")}&limit=$LIMIT&bbox=$KOREA$bias"
        runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("User-Agent", USER_AGENT)
                val features = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getJSONArray("features")
                (0 until features.length()).mapNotNull { index ->
                    val feature = features.getJSONObject(index)
                    val p = feature.getJSONObject("properties")
                    val point = feature.getJSONObject("geometry").getJSONArray("coordinates")
                    val road = listOfNotNull(p.text("street"), p.text("housenumber")).joinToString(" ").ifEmpty { null }
                    val name = p.text("name") ?: road ?: return@mapNotNull null
                    val address = listOfNotNull(p.text("city") ?: p.text("county"), p.text("district"), road?.takeIf { it != name }).joinToString(" ")
                    Found(Place(name, point.getDouble(1), point.getDouble(0)), address)
                }.distinctBy { it.place.name to it.address }
            } finally {
                connection.disconnect()
            }
        }.onFailure { Log.w(TAG, "장소 검색 실패", it) }.getOrDefault(emptyList())
    }

    private fun JSONObject.text(key: String): String? = optString(key).trim().takeIf { it.isNotEmpty() }

    private const val TAG = "PlaceSearch"
    private const val SERVER = "https://photon.komoot.io"
    private const val USER_AGENT = "PleosShiftPlanner/0.1 (contest demo)"
    private const val TIMEOUT_MS = 8_000
    private const val LIMIT = 8
    /** 대한민국 범위(서, 남, 동, 북). */
    private const val KOREA = "124.5,33.0,131.9,38.7"
}
