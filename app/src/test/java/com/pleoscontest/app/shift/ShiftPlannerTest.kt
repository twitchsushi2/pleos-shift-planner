package com.pleoscontest.app.shift

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ShiftPlannerTest {
    private val planner = ShiftPlanner()
    private val morning = LocalDateTime.of(2026, 10, 3, 8, 0)

    private val a = Driver("a", "지훈")
    private val b = Driver("b", "민지")
    private val c = Driver("c", "준호")

    /** 25km마다 휴게소가 있는 길. 0.75분/km(시속 80km). */
    private fun route(totalKm: Double, etaMinutes: Int = (totalKm * 0.75).toInt()) = Route(
        totalKm = totalKm,
        etaMinutes = etaMinutes,
        restAreas = (25 until totalKm.toInt() step 25).map { RestArea("r$it", "R$it", it.toDouble()) },
    )

    @Test
    fun `한도 안에 도착하면 교대 없이 한 구간이다`() {
        val plan = planner.plan(route(100.0), listOf(a, b), morning)
        assertEquals(1, plan.segments.size)
        assertNull(plan.segments.single().endStop)
        assertEquals(100.0, plan.segments.single().toKm, 1e-9)
    }

    @Test
    fun `두 명이면 120분 안에서 번갈아 운전하고 목적지에서 끝난다`() {
        val r = route(400.0)
        val plan = planner.plan(r, listOf(a, b), morning)

        assertTrue(plan.segments.all { it.minutes <= 120 && !it.overLimit })
        assertEquals(400.0, plan.segments.last().toKm, 1e-9)
        assertEquals(listOf("a", "b", "a"), plan.segments.map { it.driver.id })
        assertTrue(plan.shiftStops.all { it in r.restAreas })
        // 120분(160km) 안의 가장 먼 휴게소에서 교대한다.
        assertEquals(listOf(150.0, 300.0), plan.shiftStops.map { it.km })
    }

    @Test
    fun `교대할 때 쉬는 시간만큼 다음 구간 출발이 늦어진다`() {
        val plan = planner.plan(route(400.0), listOf(a, b), morning)
        assertEquals(plan.segments[0].end.plusMinutes(15), plan.segments[1].start)
    }

    @Test
    fun `초보는 60분까지만 맡는다`() {
        val novice = b.copy(novice = true)
        val plan = planner.plan(route(400.0), listOf(a, novice), morning)
        assertTrue(plan.segments.filter { it.driver.id == "b" }.all { it.minutes <= 60 })
        assertTrue(plan.segments.any { it.driver.id == "b" })
    }

    @Test
    fun `야간 회피 운전자에게는 밤 구간을 주지 않는다`() {
        val nightOwlAvoider = a.copy(avoidNight = true)
        val evening = LocalDateTime.of(2026, 10, 3, 20, 0)
        val plan = planner.plan(route(600.0), listOf(nightOwlAvoider, b, c), evening, firstDriver = b)

        assertEquals(0, plan.nightMinutesBy("a"))
        assertTrue(plan.segments.sumOf { it.nightMinutes } > 0)
    }

    @Test
    fun `세 명이면 운전 시간이 한쪽으로 쏠리지 않는다`() {
        val plan = planner.plan(route(900.0), listOf(a, b, c), morning)
        val minutes = listOf(a, b, c).map { plan.drivenMinutesBy(it.id) }
        assertTrue("분담: $minutes", minutes.max() - minutes.min() <= 120)
    }

    @Test
    fun `혼자면 같은 사람이 휴게소에서 쉬었다 이어 간다`() {
        val plan = planner.plan(route(400.0), listOf(a), morning)
        assertTrue(plan.segments.all { it.driver.id == "a" })
        // 혼자면 90분(120km) 안의 가장 먼 휴게소에서 15분 쉰다.
        assertEquals(listOf(100.0, 200.0, 300.0), plan.shiftStops.map { it.km })
        assertTrue(plan.segments.all { it.minutes in 60..90 && !it.overLimit })
    }

    @Test
    fun `혼자면 휴게소가 드물어도 90분 안에서 쉰다`() {
        val r = Route(300.0, 225, (30 until 300 step 30).map { RestArea("r$it", "R$it", it.toDouble()) })
        val plan = planner.plan(r, listOf(a), morning)
        assertEquals(listOf(120.0, 240.0), plan.shiftStops.map { it.km })
        assertTrue(plan.segments.all { it.minutes <= 90 && !it.overLimit })
    }

    @Test
    fun `혼자면 90분을 넘기면 한도를 넘긴 것이다`() {
        val tracker = TripTracker()
        val state = TripState(now = morning, driver = a, minutesSinceShift = 95.0)
        assertTrue(tracker.isOverLimit(state, solo = true))
        assertFalse(tracker.isOverLimit(state, solo = false))
    }

    @Test
    fun `한도 안에 휴게소가 없으면 가장 가까운 곳에서 교대하고 경고한다`() {
        val sparse = Route(400.0, 300, listOf(RestArea("far", "먼휴게소", 200.0), RestArea("far2", "더먼휴게소", 300.0)))
        val first = planner.plan(sparse, listOf(a, b), morning).segments.first()
        assertEquals("far", first.endStop?.id)
        assertTrue(first.overLimit)
    }

    @Test
    fun `출발하자마자 교대하는 휴게소는 고르지 않는다`() {
        val r = Route(300.0, 225, listOf(RestArea("near", "가까운휴게소", 10.0), RestArea("mid", "중간휴게소", 150.0)))
        assertEquals("mid", planner.plan(r, listOf(a, b), morning).shiftStops.first().id)
    }

    @Test
    fun `정체로 늦어지면 재계획을 제안하고 교대 지점을 앞당긴다`() {
        val r = route(400.0)
        val plan = planner.plan(r, listOf(a, b), morning)
        val progress = Progress(km = 100.0, now = morning.plusMinutes(75), driver = a, minutesSinceShift = 75)

        // 막히지 않으면 남은 300km에 225분.
        assertFalse(planner.shouldReplan(r, plan, progress, remainingEtaMinutes = 225))

        // 정체로 400분이 됐다: 지금 속도면 150km 휴게소에 닿기 전에 120분을 넘긴다.
        assertTrue(planner.shouldReplan(r, plan, progress, remainingEtaMinutes = 400))
        val replanned = planner.replan(r, listOf(a, b), progress, remainingEtaMinutes = 400)
        assertEquals(150.0, plan.shiftStops.first().km, 1e-9)
        assertEquals(125.0, replanned.shiftStops.first().km, 1e-9)
        assertEquals("a", replanned.segments.first().driver.id)
        assertTrue(replanned.segments.first().let { 75 + it.minutes <= 120 })
    }

    @Test
    fun `다음 교대 안내 문장`() {
        val r = route(400.0)
        val plan = planner.plan(r, listOf(a, b), morning)
        val progress = Progress(km = 100.0, now = morning.plusMinutes(75), driver = a)

        val next = ShiftAnnouncer.next(plan, progress, r.minutesPerKm)
        assertEquals("다음 교대: R150, 38분 후", ShiftAnnouncer.panelText(next))
        assertEquals("38분 뒤 R150에서 민지 님으로 교대합니다.", ShiftAnnouncer.speech(next, a))

        val last = Progress(km = 350.0, now = morning.plusMinutes(280), driver = a)
        assertEquals("남은 교대 없음", ShiftAnnouncer.panelText(ShiftAnnouncer.next(plan, last, r.minutesPerKm)))
    }
}
