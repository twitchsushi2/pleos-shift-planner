package com.pleoscontest.app.shift

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class TripTrackerTest {
    private val tracker = TripTracker()
    private val planner = ShiftPlanner()
    private val a = Driver("a", "지훈")
    private val b = Driver("b", "민지")
    private val start = TripState(now = LocalDateTime.of(2026, 10, 3, 8, 0), driver = a)
    private val route = Route(400.0, 300, (25 until 400 step 25).map { RestArea("r$it", "R$it", it.toDouble()) })

    private fun drive(state: TripState, speed: Double, minutes: Int, stopAtKm: Double? = null): TripState {
        var s = state
        repeat(minutes) { s = tracker.advance(s, speed, driving = true, seconds = 60.0, stopAtKm = stopAtKm) }
        return s
    }

    @Test
    fun `속도를 적분해 위치와 운전 시간을 쌓는다`() {
        val s = drive(start, 80.0, 30)
        assertEquals(40.0, s.km, 1e-6)
        assertEquals(30.0, s.minutesSinceShift, 1e-6)
        assertEquals(40.0, s.drivenKm["a"]!!, 1e-6)
    }

    @Test
    fun `기어 P에서는 시간만 가고 운전 시간은 늘지 않는다`() {
        val s = tracker.advance(start, 0.0, driving = false, seconds = 600.0)
        assertEquals(start.now.plusMinutes(10), s.now)
        assertEquals(0.0, s.minutesSinceShift, 1e-9)
    }

    @Test
    fun `수락한 교대 휴게소를 지나치지 않는다`() {
        val s = drive(start, 100.0, 120, stopAtKm = 150.0)
        assertEquals(150.0, s.km, 1e-9)
    }

    @Test
    fun `교대하면 운전자가 바뀌고 연속 운전 시간이 0이 된다`() {
        val s = tracker.shift(drive(start, 80.0, 60), b)
        assertEquals("b", s.driver.id)
        assertEquals(0.0, s.minutesSinceShift, 1e-9)
        assertEquals(60.0, s.drivenMinutes["a"]!!, 1e-6)
    }

    @Test
    fun `연속 운전 한도를 넘기면 알아채고 교대하면 풀린다`() {
        assertTrue(!tracker.isOverLimit(drive(start, 80.0, 120)))
        val over = drive(start, 80.0, 121)
        assertTrue(tracker.isOverLimit(over))
        assertTrue(!tracker.isOverLimit(tracker.shift(over, b)))
    }

    @Test
    fun `초보는 60분을 넘기면 한도 초과다`() {
        val novice = start.copy(driver = a.copy(novice = true))
        assertTrue(tracker.isOverLimit(drive(novice, 80.0, 61)))
    }

    @Test
    fun `정체로 느려지면 남은 시간 추정이 늘어 재계획이 걸린다`() {
        val plan = planner.plan(route, listOf(a, b), start.now)
        val cruising = drive(start, 80.0, 75) // 100km
        assertEquals(225, tracker.estimatedRemainingMinutes(route, cruising))
        assertTrue(!planner.shouldReplan(route, plan, cruising.toProgress(), tracker.estimatedRemainingMinutes(route, cruising)))

        val jammed = drive(cruising, 20.0, 20)
        assertTrue(tracker.estimatedRemainingMinutes(route, jammed) > 400)
        assertTrue(planner.shouldReplan(route, plan, jammed.toProgress(), tracker.estimatedRemainingMinutes(route, jammed)))
    }

    @Test
    fun `계획보다 조금 느리게 달려도 막히지 않으면 정체로 보지 않는다`() {
        // 계획은 시속 100km인데 80으로 달린다. 정체가 아니라 다시 계획하자고 하지 않는다.
        val fast = route.copy(etaMinutes = (route.totalKm * 60 / 100).toInt())
        val cruising = drive(start, 80.0, 60)
        // 80km를 달렸으니 남은 거리를 계획 속도(100)로 간다: 처음 예정 시간보다 48분 줄어든다.
        assertEquals((fast.etaMinutes - 48).toDouble(), tracker.estimatedRemainingMinutes(fast, cruising).toDouble(), 1.0)
        assertTrue(!tracker.isCongested(cruising))
        assertTrue(tracker.isCongested(drive(cruising, 20.0, 20)))
    }

    @Test
    fun `D인 채 서 있어도 최근 속도가 떨어지지 않는다`() {
        val cruising = drive(start, 100.0, 60)
        val waiting = drive(cruising, 0.0, 15)
        assertEquals(cruising.recentSpeedKmh!!, waiting.recentSpeedKmh!!, 1e-9)
        assertEquals(tracker.estimatedRemainingMinutes(route, cruising), tracker.estimatedRemainingMinutes(route, waiting))
        assertTrue(!tracker.isCongested(waiting))
        // 서 있는 시간도 운전 시간이다.
        assertEquals(75.0, waiting.minutesSinceShift, 1e-6)
    }

    @Test
    fun `최근 속도는 출발 때의 느린 속도가 아니라 막히지 않은 속도에서 시작한다`() {
        val parked = drive(start, 0.0, 3)
        assertEquals(null, parked.recentSpeedKmh)
        // 60에서 시작해 1분 동안 시속 20 쪽으로 10%만 다가간다.
        assertEquals(56.0, drive(parked, 20.0, 1).recentSpeedKmh!!, 1e-9)
    }

    @Test
    fun `미루면 계획한 다음 휴게소를 지나 그다음에서 교대한다`() {
        val plan = planner.plan(route, listOf(a, b), start.now)
        val s = drive(start, 80.0, 75)
        val postponed = planner.postpone(route, listOf(a, b), plan, s.toProgress())
        assertEquals(150.0, plan.shiftStops.first().km, 1e-9)
        assertEquals(175.0, postponed.shiftStops.first().km, 1e-9)
        assertEquals("a", postponed.segments.first().driver.id)
        assertTrue(postponed.segments.first().overLimit)
    }

    @Test
    fun `앱이 꺼져 있던 시간은 운전한 것으로 더하고 위치는 그대로 둔다`() {
        val driving = drive(start, 80.0, 50)
        val resumed = tracker.catchUp(driving, 20.0)
        assertEquals(70.0, resumed.minutesSinceShift, 1e-6)
        assertEquals(70.0, resumed.drivenMinutes["a"]!!, 1e-6)
        assertEquals(driving.km, resumed.km, 1e-9)
        assertEquals(driving.now.plusMinutes(20), resumed.now)
        // 꺼져 있던 동안 한도를 넘겼으면 다시 켜자마자 알 수 있다.
        assertTrue(tracker.isOverLimit(tracker.catchUp(driving, 80.0)))
    }

    @Test
    fun `내비 남은 거리로 위치를 맞추되 1km 단위 오차는 무시한다`() {
        val counted = drive(start, 80.0, 60) // 속도로 80km
        // 내비는 400km 경로에서 330km 남았다고 한다(실제로는 70km 왔다).
        assertEquals(70.0, tracker.syncWithNavi(counted, 400.0, 330.0).km, 1e-9)
        // 1km 남짓 차이는 내비 값의 반올림이라 그대로 둔다.
        assertEquals(80.0, tracker.syncWithNavi(counted, 400.0, 321.0).km, 1e-9)
        // 경로보다 남은 거리가 크게 와도 출발점 밖으로 나가지 않는다.
        assertEquals(0.0, tracker.syncWithNavi(counted, 400.0, 450.0).km, 1e-9)
    }
}
