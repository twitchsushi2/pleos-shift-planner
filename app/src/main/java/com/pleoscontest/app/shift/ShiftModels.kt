package com.pleoscontest.app.shift

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

data class GeoPoint(val lat: Double, val lng: Double)

/** 운전자 한 명. 연속 운전 한도와 선호를 갖는다. */
data class Driver(
    val id: String,
    val name: String,
    /** 설정에 등록한 운전자에서 골랐다면 그 프로필. */
    val profileId: String? = null,
    val maxContinuousMinutes: Int = 120,
    /** 밤(기본 22시~6시)에는 가능하면 운전하지 않는다. */
    val avoidNight: Boolean = false,
    /** 초보 운전자. 연속 운전 한도를 [ShiftRules.noviceMaxMinutes]로 더 줄인다. */
    val novice: Boolean = false,
)

/** 경로 위의 휴게소. [km]는 출발지에서 경로를 따라 잰 거리다. 좌표는 내비 경유지로 넘길 때 쓴다. */
data class RestArea(
    val id: String,
    val name: String,
    val km: Double,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    /** 주유소, 전기차 충전, 식당처럼 이 휴게소에서 쓸 수 있는 시설. */
    val facilities: List<String> = emptyList(),
    /** 화물차 휴게소. 화물차 운전자를 위한 곳이라 설정에서 켰을 때만 교대·휴식 장소로 쓴다. */
    val truck: Boolean = false,
) {
    /** 졸음쉼터. 식당·주유소가 없어 같은 거리면 휴게소를 먼저 고른다. */
    val drowsy: Boolean get() = "졸음쉼터" in name

    val facilityText: String get() = if (facilities.isEmpty()) "편의시설 정보 없음" else facilities.joinToString(" · ")
}

/**
 * 교대 계획에 쓰는 경로 요약. 내비는 경로상 휴게소 목록을 주지 않으므로 [restAreas]는 내장 공공데이터에서 고른다.
 * 남은 시간은 거리에 고르게 퍼져 있다고 본다(구간별 정체 반영은 이후 과제).
 */
data class Route(
    val totalKm: Double,
    val etaMinutes: Int,
    val restAreas: List<RestArea>,
    /** 지도 미리보기에 그릴 경로 모양. */
    val path: List<GeoPoint> = emptyList(),
) {
    init {
        require(totalKm > 0) { "경로 거리는 0보다 커야 한다" }
        require(etaMinutes > 0) { "도착 예정 시간은 0보다 커야 한다" }
    }

    val minutesPerKm: Double get() = etaMinutes / totalKm
}

/** [include]가 아니면 화물차 휴게소를 뺀다. 설정의 "화물차 휴게소도 쓰기"를 따른다. */
fun Route.withTruckStops(include: Boolean): Route = if (include || restAreas.none { it.truck }) this else copy(restAreas = restAreas.filterNot { it.truck })

/** 교대 판단 규칙. */
data class ShiftRules(
    /** 교대할 때 쉬는 시간. 다음 구간 출발 시각에 더한다. */
    val breakMinutes: Int = 15,
    /** 교대 직후 너무 빨리 다시 교대하지 않도록, 이 시간보다 짧은 구간은 되도록 만들지 않는다. */
    val minSegmentMinutes: Int = 30,
    val noviceMaxMinutes: Int = 60,
    /** 혼자 운전할 때 연속 운전 한도. 바꿔 줄 사람이 없어 한 시간 반마다 쉬어 간다. */
    val soloMaxMinutes: Int = 90,
    val nightStart: LocalTime = LocalTime.of(22, 0),
    val nightEnd: LocalTime = LocalTime.of(6, 0),
    /** 계획한 교대 지점 도착이 이만큼 이상 늦어지면 재계획을 제안한다. */
    val replanDelayMinutes: Int = 15,
) {
    fun limitFor(driver: Driver, solo: Boolean = false): Int {
        val base = if (driver.novice) minOf(driver.maxContinuousMinutes, noviceMaxMinutes) else driver.maxContinuousMinutes
        return if (solo) minOf(base, soloMaxMinutes) else base
    }

    fun isNight(time: LocalTime): Boolean =
        if (nightStart <= nightEnd) time >= nightStart && time < nightEnd
        else time >= nightStart || time < nightEnd

    /** [start]부터 [end]까지 중 밤에 해당하는 분. 구간이 몇 시간 단위라 1분씩 세도 충분히 빠르다. */
    fun nightMinutes(start: LocalDateTime, end: LocalDateTime): Int {
        var count = 0
        var t = start.truncatedTo(ChronoUnit.MINUTES)
        while (t < end) {
            if (isNight(t.toLocalTime())) count++
            t = t.plusMinutes(1)
        }
        return count
    }
}

/** 한 운전자가 맡는 구간. [endStop]이 null이면 목적지까지 가는 마지막 구간이다. */
data class Segment(
    val driver: Driver,
    val fromKm: Double,
    val toKm: Double,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val endStop: RestArea?,
    /** 한도 안에 휴게소가 없어 연속 운전 한도를 넘기는 구간. 화면에서 경고한다. */
    val overLimit: Boolean,
    val nightMinutes: Int,
) {
    val minutes: Long get() = ChronoUnit.MINUTES.between(start, end)
}

data class ShiftPlan(val segments: List<Segment>) {
    /** 교대가 일어나는 지점들(마지막 구간 제외). */
    val shiftStops: List<RestArea> get() = segments.mapNotNull { it.endStop }

    fun drivenMinutesBy(driverId: String): Long =
        segments.filter { it.driver.id == driverId }.sumOf { it.minutes }

    fun nightMinutesBy(driverId: String): Int =
        segments.filter { it.driver.id == driverId }.sumOf { it.nightMinutes }
}

/** 주행 중 지금 상태. 재계획과 다음 교대 안내에 쓴다. */
data class Progress(
    val km: Double,
    val now: LocalDateTime,
    val driver: Driver,
    /** 지금 운전자가 마지막 교대 뒤로 운전한 분. */
    val minutesSinceShift: Int = 0,
    /** 지금까지 운전자별로 운전한 분. 남은 구간을 공평하게 나누는 데 쓴다. */
    val drivenMinutes: Map<String, Long> = emptyMap(),
)
