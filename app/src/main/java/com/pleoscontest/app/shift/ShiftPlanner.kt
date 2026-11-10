package com.pleoscontest.app.shift

import java.time.LocalDateTime
import kotlin.math.roundToLong

/**
 * 교대 계획을 규칙으로만 계산한다. LLM은 여기서 만든 결과를 말로 바꿀 때만 쓴다(환각 방지).
 *
 * 규칙
 * 1. 한 사람의 연속 운전은 한도(기본 120분, 초보 60분) 안에서 끊는다. 혼자면 90분 안에서 쉬어 간다.
 * 2. 교대 지점은 한도에 닿기 전 가장 먼 휴게소. 너무 이른 교대(기본 30분 미만)는 다른 휴게소가 없을 때만.
 * 3. 한도 안에 휴게소가 없으면 가장 가까운 휴게소에서 교대하고 그 구간을 [Segment.overLimit]로 표시한다.
 * 4. 다음 운전자는 직전 운전자를 뺀 사람 중 (야간 회피 위반 없음 → 지금까지 운전 시간이 적음 → 등록 순) 으로 고른다.
 *    혼자면 같은 사람이 쉬었다가 이어 간다.
 */
class ShiftPlanner(private val rules: ShiftRules = ShiftRules()) {

    fun plan(route: Route, drivers: List<Driver>, departure: LocalDateTime, firstDriver: Driver = drivers.first()): ShiftPlan {
        require(drivers.isNotEmpty()) { "운전자가 한 명 이상 있어야 한다" }
        return planFrom(route, drivers, Progress(km = 0.0, now = departure, driver = firstDriver), route.minutesPerKm)
    }

    /**
     * 주행 중 다시 계산한다. 내비가 알려 준 남은 시간([remainingEtaMinutes])으로 속도를 다시 잡는다.
     * 지금 운전자가 이미 운전한 시간([Progress.minutesSinceShift])은 첫 구간 한도에서 뺀다.
     */
    fun replan(route: Route, drivers: List<Driver>, progress: Progress, remainingEtaMinutes: Int): ShiftPlan {
        val remainingKm = route.totalKm - progress.km
        if (remainingKm <= EPS) return ShiftPlan(emptyList())
        return planFrom(route, drivers, progress, remainingEtaMinutes / remainingKm)
    }

    /**
     * 계획대로라면 다음 교대 지점에 도착하기 전에 한도를 넘기거나, 도착이 [ShiftRules.replanDelayMinutes] 이상 늦어지면 true.
     * 정체로 도착 예정 시간이 밀릴 때 화면에 "교대 지점을 바꿀까요?"를 띄우는 조건이다.
     */
    fun shouldReplan(route: Route, plan: ShiftPlan, progress: Progress, remainingEtaMinutes: Int, solo: Boolean = false): Boolean {
        val current = plan.segments.firstOrNull { it.toKm > progress.km + EPS } ?: return false
        val remainingKm = route.totalKm - progress.km
        if (remainingKm <= EPS) return false
        val pace = remainingEtaMinutes / remainingKm
        val minutesToStop = (current.toKm - progress.km) * pace
        val predictedArrival = progress.now.plusMinutes(minutesToStop.roundToLong())

        val overLimit = progress.minutesSinceShift + minutesToStop > rules.limitFor(progress.driver, solo)
        val delayed = predictedArrival.isAfter(current.end.plusMinutes(rules.replanDelayMinutes.toLong()))
        return (overLimit && !current.overLimit) || delayed
    }

    /**
     * "미루기": 계획한 다음 교대 휴게소를 지나 그다음 휴게소에서 교대한다. 지금 운전자가 한도를 넘기면 그 구간은 overLimit로 표시된다.
     * 다음 휴게소가 없으면 목적지까지 지금 운전자가 간다.
     */
    fun postpone(route: Route, drivers: List<Driver>, plan: ShiftPlan, progress: Progress, minutesPerKm: Double = route.minutesPerKm): ShiftPlan {
        val current = plan.segments.firstOrNull { it.endStop != null && it.toKm > progress.km + EPS } ?: return plan
        val later = route.restAreas.filter { it.km > current.toKm + EPS && it.km < route.totalKm - EPS }.minByOrNull { it.km }
            ?: return planFrom(route.copy(restAreas = emptyList()), drivers, progress, minutesPerKm)
        return planFrom(route, drivers, progress, minutesPerKm, forcedFirstStop = later)
    }

    private fun planFrom(
        route: Route, drivers: List<Driver>, start: Progress, minutesPerKm: Double, forcedFirstStop: RestArea? = null,
    ): ShiftPlan {
        val stops = route.restAreas.filter { it.km > start.km + EPS && it.km < route.totalKm - EPS }.sortedBy { it.km }
        val driven = drivers.associate { it.id to (start.drivenMinutes[it.id] ?: 0L) }.toMutableMap()
        val segments = mutableListOf<Segment>()
        val solo = drivers.size == 1

        var km = start.km
        var time = start.now
        var driver = start.driver
        var used = start.minutesSinceShift

        while (km < route.totalKm - EPS) {
            val limit = rules.limitFor(driver, solo)
            val reachKm = km + (limit - used).coerceAtLeast(0) / minutesPerKm
            val ahead = stops.filter { it.km > km + EPS }

            if ((reachKm >= route.totalKm - EPS && (forcedFirstStop == null || segments.isNotEmpty())) || ahead.isEmpty()) {
                segments += segment(driver, km, route.totalKm, time, minutesPerKm, endStop = null, used = used, limit = limit)
                break
            }

            val earliestKm = km + (rules.minSegmentMinutes - used).coerceAtLeast(0) / minutesPerKm
            val reachable = ahead.filter { it.km <= reachKm + EPS }
            val window = reachable.filter { it.km >= earliestKm - EPS }
            val latest = window.maxByOrNull { it.km }
            // 졸음쉼터보다 휴게소가 낫다. 가장 늦게 설 수 있는 곳보다 조금(PREFER_SERVICE_MINUTES) 일찍이면 휴게소에 선다.
            val service = window.filter { !it.drowsy }.maxByOrNull { it.km }
                ?.takeIf { latest == null || (latest.km - it.km) * minutesPerKm <= PREFER_SERVICE_MINUTES }
            val stop = forcedFirstStop?.takeIf { segments.isEmpty() }
                ?: service
                ?: latest
                ?: reachable.maxByOrNull { it.km }
                ?: ahead.first() // 한도 안에 휴게소가 없다. 가장 가까운 곳까지 가고 overLimit로 표시된다.

            val seg = segment(driver, km, stop.km, time, minutesPerKm, endStop = stop, used = used, limit = limit)
            segments += seg
            driven[driver.id] = (driven[driver.id] ?: 0L) + seg.minutes

            km = stop.km
            time = seg.end.plusMinutes(rules.breakMinutes.toLong())
            used = 0
            driver = nextDriver(previous = driver, drivers = drivers, driven = driven, departure = time)
        }
        return ShiftPlan(segments)
    }

    private fun segment(
        driver: Driver, fromKm: Double, toKm: Double, start: LocalDateTime, minutesPerKm: Double,
        endStop: RestArea?, used: Int, limit: Int,
    ): Segment {
        val minutes = ((toKm - fromKm) * minutesPerKm).roundToLong()
        val end = start.plusMinutes(minutes)
        return Segment(
            driver = driver, fromKm = fromKm, toKm = toKm, start = start, end = end, endStop = endStop,
            overLimit = used + minutes > limit,
            // 시작 시각을 분 단위로 내려 세므로 1분 넘칠 수 있다. 구간 길이를 넘지 않게 한다.
            nightMinutes = rules.nightMinutes(start, end).coerceAtMost(minutes.toInt()),
        )
    }

    private fun nextDriver(previous: Driver, drivers: List<Driver>, driven: Map<String, Long>, departure: LocalDateTime): Driver {
        val candidates = drivers.filter { it.id != previous.id }.ifEmpty { return previous }
        return candidates.withIndex().minWith(
            compareBy<IndexedValue<Driver>>(
                { (_, d) ->
                    // 이 사람이 한도만큼 운전한다고 보고, 그동안 밤이 끼면 야간 회피 위반으로 친다.
                    val night = rules.nightMinutes(departure, departure.plusMinutes(rules.limitFor(d).toLong()))
                    if (d.avoidNight && night > 0) 1 else 0
                },
                { (_, d) -> driven[d.id] ?: 0L },
                { it.index },
            ),
        ).value
    }

    private companion object {
        /** 휴게소가 졸음쉼터보다 이 시간(분) 안쪽으로 앞에 있으면 휴게소에 선다. */
        const val PREFER_SERVICE_MINUTES = 25
        const val EPS = 1e-6
    }
}
