package com.pleoscontest.app.shift

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class RouteCatalogTest {
    private fun route(from: String, to: String) = RouteCatalog.route(from, to)!!
    private fun names(from: String, to: String) = route(from, to).restAreas.map { it.name }

    @Test
    fun `주요 노선 거리가 실제와 비슷하다`() {
        val expected = mapOf(
            ("서울" to "부산") to 390.0..430.0,
            ("서울" to "강릉") to 225.0..265.0,
            ("서울" to "목포") to 340.0..390.0,
            ("광주" to "부산") to 225.0..265.0,
        )
        expected.forEach { (pair, range) ->
            val km = route(pair.first, pair.second).totalKm
            assertTrue("$pair = $km", km in range)
        }
    }

    @Test
    fun `지나는 고속도로의 휴게소가 나온다`() {
        assertTrue("평창휴게소" in names("서울", "강릉"))
        assertTrue("진주휴게소" in names("광주", "부산"))
    }

    @Test
    fun `진행 방향의 휴게소만 나온다`() {
        // 경부고속도로 망향휴게소는 부산 방향에만, 천안삼거리휴게소는 서울 방향에만 있다.
        assertTrue("망향휴게소" in names("수원", "대전") && "망향휴게소" !in names("대전", "수원"))
        assertTrue("천안삼거리휴게소" in names("대전", "수원") && "천안삼거리휴게소" !in names("수원", "대전"))
    }

    @Test
    fun `모든 도시끼리 길이 있다`() {
        for (from in RouteCatalog.places) for (to in RouteCatalog.places) {
            if (from != to) assertNotNull("${from.name}→${to.name}", RouteCatalog.route(from.name, to.name))
        }
    }

    @Test
    fun `휴게소는 출발지에서 가까운 순서이고 경로 안에 있다`() {
        for ((from, to) in listOf("서울" to "부산", "부산" to "서울", "인천" to "강릉", "목포" to "대구")) {
            val r = route(from, to)
            val km = r.restAreas.map { it.km }
            assertTrue(km.isNotEmpty())
            assertEquals(km.sorted(), km)
            assertTrue(km.all { it >= 0 && it <= r.totalKm })
            assertTrue(r.restAreas.all { it.lat in 33.0..38.7 && it.lng in 125.5..130.0 })
        }
    }

    @Test
    fun `장거리 주요 노선은 연속 운전 한도를 넘지 않게 나뉜다`() {
        val planner = ShiftPlanner()
        val drivers = listOf(Driver("a", "지훈"), Driver("b", "민지"))
        val start = LocalDateTime.of(2026, 10, 3, 8, 0)
        for ((from, to) in listOf("서울" to "부산", "부산" to "서울", "서울" to "목포", "서울" to "여수", "인천" to "포항", "속초" to "부산")) {
            val plan = planner.plan(route(from, to), drivers, start)
            assertFalse("$from→$to", plan.segments.any { it.overLimit })
            assertTrue(plan.shiftStops.isNotEmpty())
        }
    }

    @Test
    fun `목록에 없는 현재 위치에서도 경로를 찾는다`() {
        val here = Place(RouteCatalog.CURRENT_LOCATION, 37.4127, 127.0947) // 성남
        val r = RouteCatalog.route(here, RouteCatalog.find("강릉")!!)!!
        assertTrue("거리 ${r.totalKm}", r.totalKm in 170.0..240.0)
        assertTrue("평창휴게소" in r.restAreas.map { it.name })
    }

    @Test
    fun `경로 모양과 지도 자료가 있다`() {
        val r = route("서울", "강릉")
        assertTrue(r.path.size > 10)
        assertTrue(RouteCatalog.mapRoads.size > 100)
        assertTrue(RouteCatalog.mapCoast.isNotEmpty())
    }

    @Test
    fun `휴게소에 편의시설 정보가 있다`() {
        val stops = route("서울", "부산").restAreas
        assertTrue(stops.any { "주유소" in it.facilities })
        assertTrue(stops.any { "식당" in it.facilities || "편의점" in it.facilities })
    }

    @Test
    fun `목록에 없거나 같은 장소면 경로를 만들지 않는다`() {
        assertNull(RouteCatalog.route("서울", "평양"))
        assertNull(RouteCatalog.route("서울", "서울"))
    }

    @Test
    fun `입력한 글자로 장소를 찾는다`() {
        assertEquals(listOf("대전", "대구"), RouteCatalog.matching("대").map { it.name })
        assertEquals(RouteCatalog.places, RouteCatalog.matching(""))
    }

    @Test
    fun `서울에서 부산은 내비가 고른 거리에 맞는 다른 길로 바꿀 수 있다`() {
        val seoul = RouteCatalog.find("서울")!!
        val busan = RouteCatalog.find("부산")!!
        val candidates = RouteCatalog.alternatives(seoul, busan)
        // 중부내륙, 경부, 중부, 서해안처럼 서로 다른 고속도로로 가는 길이 나온다.
        assertTrue(candidates.size >= 3)
        assertEquals(candidates.size, candidates.map { it.route.restAreas.firstOrNull()?.name to it.route.totalKm.toInt() }.toSet().size)
        // 내비 거리는 도심에서 고속도로까지 가는 길을 포함한다. 둘째 길의 거리를 주면 그 길의 휴게소가 나온다.
        val second = candidates[1]
        val chosen = RouteCatalog.route(seoul, busan, naviKm = second.drivingKm + 2)!!
        assertEquals(second.route.restAreas.map { it.name }, chosen.restAreas.map { it.name })
        assertEquals(candidates[0].route.totalKm, RouteCatalog.route(seoul, busan, naviKm = candidates[0].drivingKm - 3)!!.totalKm, 1e-9)
    }

    @Test
    fun `모든 휴게소에 실제 이름이 있다`() {
        // 예전 생성 스크립트가 "영주부산방향휴게소"의 "영주"까지 지워 "휴게소"만 남은 곳이 있었다.
        val names = RouteCatalog.places.flatMap { from ->
            RouteCatalog.places.mapNotNull { to -> RouteCatalog.route(from, to) }
        }.flatMap { route -> route.restAreas.map { it.name } }.toSet()
        assertFalse("휴게소" in names)
        assertTrue(names.containsAll(listOf("영주휴게소", "원주휴게소")))
        // 한국도로공사 공식 이름으로 맞췄다(tools/apply_official_rest_areas.py). 후원사·옛 이름은 남지 않는다.
        assertTrue(names.containsAll(listOf("단양팔경휴게소", "덕평휴게소", "와촌휴게소", "금산인삼랜드휴게소", "백양사휴게소")))
        assertTrue(names.none { it in setOf("바이오시스와촌휴게소", "덕평자연휴게소", "문산소문리휴게소") })
    }

    @Test
    fun `공식 목록에서 새로 넣은 휴게소는 양방향 모두 진행 방향에 하나씩 있다`() {
        fun count(from: String, to: String, name: String) = route(from, to).restAreas.count { it.name == name }
        val cities = RouteCatalog.places.map { it.name }
        listOf(
            Triple("대전", "통영", "금산인삼랜드휴게소"),
            Triple("광주", "순천", "주암휴게소"),
            Triple("정읍", "광주", "백양사휴게소"),
        ).filter { (a, b, _) -> a in cities && b in cities }.forEach { (a, b, name) ->
            println("$a→$b ${count(a, b, name)} / $b→$a ${count(b, a, name)}")
            assertEquals("$a→$b $name", 1, count(a, b, name))
            assertEquals("$b→$a $name", 1, count(b, a, name))
        }
    }

    @Test
    fun `화물차 휴게소는 설정에서 켰을 때만 경로에 남는다`() {
        // 경부선 서울→부산에는 화물차 휴게소(입장거봉포도·청주·신탄진 등)가 있다.
        val seoul = RouteCatalog.find("서울")!!
        val busan = RouteCatalog.find("부산")!!
        val trucks = RouteCatalog.alternatives(seoul, busan).flatMap { it.route.restAreas }.filter { it.truck }
        assertTrue(trucks.isNotEmpty())
        assertTrue(trucks.all { it.name.endsWith("화물차휴게소") })
        val route = RouteCatalog.alternatives(seoul, busan).first { c -> c.route.restAreas.any { it.truck } }.route
        assertTrue(route.withTruckStops(false).restAreas.none { it.truck })
        assertEquals(route, route.withTruckStops(true))
    }
}
