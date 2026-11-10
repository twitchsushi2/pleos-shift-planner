package com.pleoscontest.app.shift

import kotlin.math.roundToInt

/** 주행 중 패널 한 줄과 음성 안내에 쓰는 "다음 교대" 정보. */
data class NextShift(val stop: RestArea, val minutesLeft: Int, val nextDriver: Driver?)

/**
 * 계획과 지금 위치로 다음 교대를 고르고 안내 문장을 만든다.
 * 문장 틀은 고정이다. LLM을 붙이더라도 이 값(장소·시간·사람)을 바꾸지 않고 말투만 다듬게 한다.
 */
object ShiftAnnouncer {

    fun next(plan: ShiftPlan, progress: Progress, minutesPerKm: Double): NextShift? {
        val index = plan.segments.indexOfFirst { it.endStop != null && it.toKm > progress.km + 1e-6 }
        if (index < 0) return null
        val segment = plan.segments[index]
        return NextShift(
            stop = segment.endStop!!,
            minutesLeft = ((segment.toKm - progress.km) * minutesPerKm).roundToInt(),
            nextDriver = plan.segments.getOrNull(index + 1)?.driver,
        )
    }

    /** 주행 중 패널 한 줄. 예) "다음 교대: 안성휴게소, 38분 후" */
    fun panelText(next: NextShift?, solo: Boolean = false): String {
        val word = if (solo) "휴식" else "교대"
        return if (next == null) "남은 $word 없음" else "다음 $word: ${next.stop.name}, ${next.minutesLeft}분 후"
    }

    /** 음성 안내. 혼자 가는 길이면 교대 대신 휴식으로 말한다. */
    fun speech(next: NextShift?, current: Driver): String = when {
        next == null -> "목적지까지 교대 없이 갑니다."
        next.nextDriver == null || next.nextDriver.id == current.id ->
            "${next.minutesLeft}분 뒤 ${next.stop.name}에서 쉬었다 갑니다."
        else -> "${next.minutesLeft}분 뒤 ${next.stop.name}에서 ${next.nextDriver.name} 님으로 교대합니다."
    }
}
