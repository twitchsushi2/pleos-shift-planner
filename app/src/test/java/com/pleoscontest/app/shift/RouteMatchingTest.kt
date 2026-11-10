package com.pleoscontest.app.shift

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteMatchingTest {
    /**
     * A에서 B로 가는 곧은 길(100km, 직행휴게소)과 C를 돌아가는 길(110km, 우회휴게소)이 있는 작은 도로망.
     * 내비가 어느 길을 골랐는지는 거리로만 알 수 있다.
     */
    private val network = ExpresswayNetwork.load(
        listOf(
            "V\t37.0\t127.0",
            "V\t37.0\t128.0",
            "V\t37.3\t127.5",
            "E\t0\t1\t100000\tM",
            "E\t0\t2\t55000\tM",
            "E\t2\t1\t55000\tM",
            "R\t0\t50000\t직행휴게소\t37.0\t127.5\t",
            "R\t1\t30000\t우회휴게소\t37.15\t127.25\t",
            "G\t0\t37.0,127.0 37.0,128.0",
            "G\t1\t37.0,127.0 37.3,127.5",
            "G\t2\t37.3,127.5 37.0,128.0",
        ).joinToString("\n").byteInputStream(),
    )
    private val a = Place("A", 37.0, 127.0)
    private val b = Place("B", 37.0, 128.0)

    @Test
    fun `내비 거리가 없으면 가장 짧은 길이다`() {
        val route = network.route(a, b, 0.75)!!
        assertEquals(100.0, route.totalKm, 1e-9)
        assertEquals(listOf("직행휴게소"), route.restAreas.map { it.name })
    }

    @Test
    fun `내비가 돌아가는 경로를 골랐으면 그 길의 휴게소로 계획한다`() {
        val route = network.route(a, b, 0.75, naviKm = 111.0)!!
        assertEquals(110.0, route.totalKm, 1e-9)
        assertEquals(listOf("우회휴게소"), route.restAreas.map { it.name })
    }

    @Test
    fun `거리 차이가 작으면 가장 짧은 길을 그대로 쓴다`() {
        assertEquals(100.0, network.route(a, b, 0.75, naviKm = 104.0)!!.totalKm, 1e-9)
    }

    @Test
    fun `갈 만한 길을 여러 개 찾는다`() {
        assertEquals(listOf(100.0, 110.0), network.alternatives(a, b, 0.75).map { it.route.totalKm })
    }

    @Test
    fun `내비 회전 안내에서 고른 경로의 휴게소와 거리를 뽑는다`() {
        val turns = listOf(
            NaviTurn("TURN_LEFT", 80.0, "기업지원허브 방면"),
            NaviTurn("TURN_ENTER_IC_R", 1700.0, "판교IC - 부산 방면"),
            NaviTurn(NaviRouteStops.SERVICE_AREA, 2600.0, "하이패스센터"),
            NaviTurn(NaviRouteStops.SERVICE_AREA, 15620.0, "기흥휴게소"),
            NaviTurn("TURN_RESTAREA", 9300.0, "오산졸음쉼터"),
            NaviTurn(NaviRouteStops.SERVICE_AREA, 28700.0, "안성맞춤휴게소(부산방향)"),
        )
        val stops = NaviRouteStops.parse(turns)!!
        // 하이패스센터는 빼고 졸음쉼터는 넣는다.
        assertEquals(listOf("기흥휴게소", "오산졸음쉼터", "안성맞춤휴게소(부산방향)"), stops.stops.map { it.name })
        assertEquals(20.0, stops.stops[0].km, 1e-9)
        assertEquals(29.3, stops.stops[1].km, 1e-9)
        assertEquals(58.0, stops.stops[2].km, 1e-9)
        assertEquals(58.0, stops.turnsKm, 1e-9)
        // 다음 회전 하나만 온 것은 경로 전체가 아니다.
        assertEquals(null, NaviRouteStops.parse(turns.take(1)))
    }

    @Test
    fun `앱 경로에 없는 휴게소가 나와야 다른 길로 본다`() {
        val stops = NaviRouteStops(100.0, listOf(NaviStop("충주휴게소", 30.0), NaviStop("괴산휴게소", 60.0)))
        // 지나온 휴게소가 빠진 것은 같은 길이다.
        assertFalse(stops.differsFrom(listOf("여주휴게소", "충주 휴게소", "괴산휴게소(부산방향)")))
        assertTrue(stops.differsFrom(listOf("여주휴게소", "충주휴게소", "문경새재휴게소")))
    }

    @Test
    fun `졸음쉼터보다 휴게소를 먼저 고르되 너무 일찍이면 졸음쉼터에 선다`() {
        val drivers = listOf(Driver("a", "지훈"), Driver("b", "민지"))
        val start = java.time.LocalDateTime.of(2026, 11, 6, 9, 0)
        // 시속 80(분당 0.75km 역수 1.333km). 2시간 한도면 160km 앞까지 갈 수 있다.
        fun firstStop(vararg stops: Pair<String, Double>) = ShiftPlanner()
            .plan(Route(300.0, 225, stops.map { (name, km) -> RestArea(name, name, km) }), drivers, start)
            .shiftStops.first().name
        // 졸음쉼터(155km)보다 15분 앞(135km)의 휴게소에 선다.
        assertEquals("가휴게소", firstStop("가휴게소" to 135.0, "나졸음쉼터" to 155.0))
        // 휴게소가 45분이나 앞(95km)이면 졸음쉼터까지 간다.
        assertEquals("나졸음쉼터", firstStop("가휴게소" to 95.0, "나졸음쉼터" to 155.0))
    }

    @Test
    fun `내비 경로의 휴게소와 졸음쉼터 점은 모두 경로 선 위에 놓인다`() {
        // 판교에서 영동선을 타고 덕평·여주를 지나 중부내륙선으로 내려가는 내비 경로(에뮬레이터 회전 안내).
        val navi = NaviRouteStops(
            160.0,
            listOf(
                NaviStop("용인졸음쉼터", 18.0), NaviStop("용인휴게소", 29.0), NaviStop("덕평휴게소", 41.0),
                NaviStop("이천졸음쉼터", 49.0), NaviStop("여주휴게소", 58.0), NaviStop("충주휴게소", 95.0), NaviStop("괴산휴게소", 121.0),
            ),
        )
        val pangyo = Place("판교", 37.40, 127.11)
        val route = RouteCatalog.fromNavi(navi, 160.0, 120, pangyo, Place("문경", 36.59, 128.19), startKm = 100.0)!!
        assertEquals(listOf(118.0, 129.0, 141.0, 149.0, 158.0, 195.0, 221.0), route.restAreas.map { it.km })
        assertTrue(route.path.size > 10)
        route.restAreas.forEach { stop ->
            val off = route.path.zipWithNext().minOf { (a, b) -> segmentDistanceKm(GeoPoint(stop.lat, stop.lng), a, b) }
            assertTrue("${stop.name} 선에서 ${off}km", off < 0.05)
        }
        assertTrue(route.restAreas.first { it.name == "이천졸음쉼터" }.drowsy)
    }

    private fun segmentDistanceKm(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val k = 111.32
        val c = kotlin.math.cos(Math.toRadians(p.lat))
        val ax = (a.lng - p.lng) * k * c; val ay = (a.lat - p.lat) * k
        val bx = (b.lng - p.lng) * k * c; val by = (b.lat - p.lat) * k
        val dx = bx - ax; val dy = by - ay
        val l = dx * dx + dy * dy
        val t = if (l == 0.0) 0.0 else ((-ax * dx - ay * dy) / l).coerceIn(0.0, 1.0)
        return kotlin.math.hypot(ax + t * dx, ay + t * dy)
    }

    @Test
    fun `경로가 바뀌어도 수락한 휴게소를 이어 붙든다`() {
        val route = Route(200.0, 150, listOf(RestArea("x", "망향휴게소", 120.0), RestArea("y", "옥산휴게소", 150.0)))
        // 새 경로에도 있으면 새 거리로 바꾼다.
        assertEquals(120.0, NaviReroute.keepAccepted(route, RestArea("old", "망향휴게소(부산방향)", 118.0), isWaypoint = false).second!!.km, 1e-9)
        // 경유지로 넣은 곳은 목록에서 빠져도 끼워 넣고 붙든다.
        val waypoint = RestArea("w", "천안호두휴게소", 135.0)
        val (withWaypoint, kept) = NaviReroute.keepAccepted(route, waypoint, isWaypoint = true)
        assertEquals(waypoint, kept)
        assertEquals(listOf("망향휴게소", "천안호두휴게소", "옥산휴게소"), withWaypoint.restAreas.map { it.name })
        // 경유지가 아니고 새 경로에 없으면 놓친 것이다.
        assertEquals(null, NaviReroute.keepAccepted(route, waypoint, isWaypoint = false).second)
    }
}
