package com.pleoscontest.app.shift

import java.time.LocalDateTime
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 주행 중 쌓이는 값. 분 단위 계산 오차가 쌓이지 않게 실수로 들고, 엔진에 넘길 때 [toProgress]로 바꾼다.
 * 운전자별 거리·시간·야간 시간은 도착 후 「여행 요약」(분담 기록)에 그대로 쓴다.
 */
data class TripState(
    val km: Double = 0.0,
    val now: LocalDateTime,
    val driver: Driver,
    val minutesSinceShift: Double = 0.0,
    val drivenMinutes: Map<String, Double> = emptyMap(),
    val drivenKm: Map<String, Double> = emptyMap(),
    val nightMinutes: Map<String, Double> = emptyMap(),
    /** 최근 주행 속도(지수 평균). 정체로 느려지면 남은 시간 추정이 늘어난다. 아직 달린 적 없으면 null. */
    val recentSpeedKmh: Double? = null,
) {
    fun toProgress() = Progress(
        km = km,
        now = now,
        driver = driver,
        minutesSinceShift = minutesSinceShift.toInt(),
        drivenMinutes = drivenMinutes.mapValues { it.value.toLong() },
    )
}

/**
 * 차량 속도로 경로 위 위치와 운전 시간을 쌓는다.
 * 실차에서는 내비의 남은 거리로 위치를 보정하면 되지만, 에뮬레이터에는 실제 주행이 없어 속도를 적분해 위치로 쓴다.
 */
class TripTracker(private val rules: ShiftRules = ShiftRules()) {

    /**
     * [seconds]만큼 시간을 보낸다. 기어가 P가 아니면([driving]) 정차 중이어도 운전 시간으로 센다(정체도 피로다).
     * [stopAtKm]이 있으면 그 지점을 넘어가지 않는다. 수락한 교대 휴게소에 들어가 멈추는 것을 흉내 낸다.
     */
    fun advance(state: TripState, speedKmh: Double, driving: Boolean, seconds: Double, stopAtKm: Double? = null): TripState {
        val now = state.now.plusNanos((seconds * 1e9).toLong())
        if (!driving) return state.copy(now = now)

        var km = state.km + max(speedKmh, 0.0) * seconds / 3600
        if (stopAtKm != null && km > stopAtKm) km = max(state.km, stopAtKm)
        val movedKm = km - state.km
        val minutes = seconds / 60
        val id = state.driver.id
        val night = if (rules.isNight(state.now.toLocalTime())) minutes else 0.0
        // 10분 시간 상수로 속도를 고르게 한다. 신호 한 번에 출렁이지 않게.
        // 멈춰 있는 동안(휴게소에서 D인 채 서 있거나 막 출발하려는 때)은 넣지 않는다. 넣으면 평균이 0 쪽으로 떨어져
        // 남은 시간과 교대 횟수가 부풀고, 막히지 않았는데 정체 제안이 뜬다. 가다 서다 하는 정체는 움직일 때의 느린 속도로 잡힌다.
        // 처음에는 막히지 않은 속도에서 시작한다. 주차장을 빠져나가는 느린 속도로 시작하면 출발하자마자 정체로 본다.
        val alpha = min(1.0, minutes / 10)
        val base = state.recentSpeedKmh ?: FREE_FLOW_KMH
        val recent = if (speedKmh < MOVING_KMH) state.recentSpeedKmh else base + alpha * (speedKmh - base)

        return state.copy(
            km = km,
            now = now,
            minutesSinceShift = state.minutesSinceShift + minutes,
            drivenMinutes = state.drivenMinutes + (id to (state.drivenMinutes[id] ?: 0.0) + minutes),
            drivenKm = state.drivenKm + (id to (state.drivenKm[id] ?: 0.0) + movedKm),
            nightMinutes = state.nightMinutes + (id to (state.nightMinutes[id] ?: 0.0) + night),
            recentSpeedKmh = recent,
        )
    }

    /**
     * 앱이 꺼져 있던 [minutes]만큼 시간을 보낸다. 그동안 차가 섰는지 달렸는지 모르므로 안전한 쪽으로 운전한 것으로 친다.
     * 연속 운전 시간이 모자라게 세어지면 교대 알림이 늦어진다. 위치(km)는 모르니 그대로 두고 내비 경로로 다시 맞춘다.
     */
    fun catchUp(state: TripState, minutes: Double): TripState {
        if (minutes <= 0) return state
        val id = state.driver.id
        val night = rules.nightMinutes(state.now, state.now.plusSeconds((minutes * 60).toLong())).toDouble().coerceAtMost(minutes)
        return state.copy(
            now = state.now.plusSeconds((minutes * 60).toLong()),
            minutesSinceShift = state.minutesSinceShift + minutes,
            drivenMinutes = state.drivenMinutes + (id to (state.drivenMinutes[id] ?: 0.0) + minutes),
            nightMinutes = state.nightMinutes + (id to (state.nightMinutes[id] ?: 0.0) + night),
        )
    }

    /**
     * 내비가 알려 준 목적지까지 남은 거리로 지금까지 온 거리를 맞춘다.
     * 속도를 더해 가는 방식은 계기판 오차(몇 %)가 쌓여 400km면 10km 넘게 어긋난다. 그러면 목적지에 와도 도착을 모르거나
     * 휴게소까지 남은 시간이 틀어진다. 내비 값은 1km 단위라 [SYNC_KM]보다 크게 어긋났을 때만 맞춘다.
     */
    fun syncWithNavi(state: TripState, routeKm: Double, naviRemainingKm: Double): TripState {
        val km = (routeKm - naviRemainingKm).coerceIn(0.0, routeKm)
        return if (kotlin.math.abs(km - state.km) > SYNC_KM) state.copy(km = km) else state
    }

    /** 지금 운전자가 연속 운전 한도를 넘겼는가. 교대를 미루거나 휴게소를 지나치면 생긴다. */
    fun isOverLimit(state: TripState, solo: Boolean = false): Boolean = state.minutesSinceShift > rules.limitFor(state.driver, solo)

    /** 교대 완료. 운전자를 바꾸고 연속 운전 시간을 0으로 돌린다. */
    fun shift(state: TripState, next: Driver): TripState = state.copy(driver = next, minutesSinceShift = 0.0)

    /**
     * 남은 시간 추정. 내비 연동 전까지는 최근 속도로 계산한다. 계획 속도보다 빠르게 달려도 계획 속도로 본다(과속을 계획에 넣지 않는다).
     * 내비를 붙이면 경유지 remainTime·TripInfo.eta로 바꾼다.
     */
    fun estimatedRemainingMinutes(route: Route, state: TripState): Int {
        val remainingKm = route.totalKm - state.km
        if (remainingKm <= 0) return 0
        val plannedSpeed = 60 / route.minutesPerKm
        // 막히지 않고 달리는 속도(60km/h 이상)는 계획 속도로 본다. 고속도로를 80으로 달린다고 정체는 아니다.
        // 그보다 느릴 때만 정체로 보고 남은 시간을 늘린다.
        val speed = state.recentSpeedKmh?.let { if (it >= FREE_FLOW_KMH) plannedSpeed else min(plannedSpeed, max(it, MIN_SPEED_KMH)) } ?: plannedSpeed
        // 거리를 적분하며 생긴 소수점 오차로 1분이 더 올라가지 않게 아주 작은 값을 뺀다.
        return ceil(remainingKm / speed * 60 - 1e-6).toInt()
    }

    /** 최근 속도가 막히지 않고 달리는 속도보다 느리다. 아직 달린 적이 없으면 막히지 않은 것으로 본다. */
    fun isCongested(state: TripState): Boolean = (state.recentSpeedKmh ?: FREE_FLOW_KMH) < FREE_FLOW_KMH

    private companion object {
        /** 완전히 멈춘 정체에서 남은 시간이 무한대로 튀지 않게 하는 바닥값. */
        const val MIN_SPEED_KMH = 5.0
        /** 이보다 빠르면 막히지 않고 달리는 것으로 본다. */
        const val FREE_FLOW_KMH = 60.0
        /** 내비 남은 거리(1km 단위)와 이보다 크게 어긋날 때만 위치를 맞춘다. */
        const val SYNC_KM = 1.5
        /** 이보다 느리면 서 있는 것으로 보고 최근 속도에 넣지 않는다. */
        const val MOVING_KMH = 3.0
    }
}
