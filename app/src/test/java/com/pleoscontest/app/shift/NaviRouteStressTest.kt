package com.pleoscontest.app.shift

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot

/**
 * 전국 도시 쌍마다 내비가 보낼 법한 휴게소 목록(도로망 경로의 휴게소 + 사이사이 졸음쉼터)으로 경로를 만들어 본다.
 * 좌표가 NaN이 되거나, 점이 선에서 벗어나거나, 순서가 뒤집히는 경로를 찾는다.
 */
class NaviRouteStressTest {
    private fun distanceKm(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val k = 111.32
        val c = cos(Math.toRadians(p.lat))
        val ax = (a.lng - p.lng) * k * c; val ay = (a.lat - p.lat) * k
        val bx = (b.lng - p.lng) * k * c; val by = (b.lat - p.lat) * k
        val dx = bx - ax; val dy = by - ay
        val l = dx * dx + dy * dy
        val t = if (l == 0.0) 0.0 else ((-ax * dx - ay * dy) / l).coerceIn(0.0, 1.0)
        return hypot(ax + t * dx, ay + t * dy)
    }

    /** 선 위에서 점이 처음부터 몇 번째 선분 근처에 있는지(순서 비교용). */
    private fun positionOnLine(p: GeoPoint, line: List<GeoPoint>): Int =
        (0 until line.lastIndex).minBy { distanceKm(p, line[it], line[it + 1]) }

    @Test
    fun `전국 경로에서 휴게소 점이 선 위에 차례로 놓이고 좌표가 깨지지 않는다`() {
        val places = RouteCatalog.places
        val problems = mutableListOf<String>()
        var checked = 0
        for (from in places) for (to in places.shuffled(java.util.Random(from.name.hashCode().toLong())).take(6)) {
            val route = RouteCatalog.route(from, to) ?: continue
            if (route.restAreas.size < 2) continue
            val stops = route.restAreas.flatMapIndexed { i, r ->
                val drowsy = if (i > 0) listOf(NaviStop("시험${i}졸음쉼터", (route.restAreas[i - 1].km + r.km) / 2)) else emptyList()
                drowsy + NaviStop(r.name, r.km)
            }
            val navi = NaviRouteStops(route.totalKm, stops)
            val built = RouteCatalog.fromNavi(navi, route.totalKm, null, from, to) ?: run { problems += "${from.name}→${to.name} 만들지 못함"; continue }
            checked++
            if (built.restAreas.any { it.lat.isNaN() || it.lng.isNaN() || it.lat == 0.0 }) problems += "${from.name}→${to.name} 좌표 깨짐"
            val line = built.path
            if (line.size < 2) { problems += "${from.name}→${to.name} 선 없음"; continue }
            val off = built.restAreas.maxOf { s -> (0 until line.lastIndex).minOf { distanceKm(GeoPoint(s.lat, s.lng), line[it], line[it + 1]) } }
            if (off > 0.3) problems += "${from.name}→${to.name} 점이 선에서 ${"%.1f".format(off)}km"
            val order = built.restAreas.filter { !it.drowsy }.map { positionOnLine(GeoPoint(it.lat, it.lng), line) }
            if (order.zipWithNext().any { (a, b) -> b < a }) problems += "${from.name}→${to.name} 순서 뒤집힘"
        }
        println("시험한 경로 $checked, 문제 ${problems.size}: ${problems.take(15)}")
        assertTrue(checked > 100)
        assertTrue(problems.joinToString(), problems.isEmpty())
    }
}
