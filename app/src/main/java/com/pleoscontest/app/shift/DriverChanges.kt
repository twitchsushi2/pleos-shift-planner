package com.pleoscontest.app.shift

import kotlin.math.abs

/** 차에서 지금 읽은 운전석 공조 상태. 읽지 못한 값은 null. */
data class CabinState(val temperature: Float? = null, val seatHeat: Int? = null, val wheelHeat: Int? = null)

/** 운전자가 바뀔 때 챙길 것. [auto]면 앱이 차에 바로 넣고, 아니면 운전자가 직접 맞춘다. */
enum class ChangeKind(val auto: Boolean) {
    TEMPERATURE(true),
    SEAT_HEAT(true),
    WHEEL_HEAT(true),
    SEAT_MEMORY(true),
    MIRRORS(false),
    SEAT_POSITION(false),
    MUSIC(false),
}

/** [needed]는 지금 차 상태와 새 운전자 설정이 달라 바꿔야 하는 항목이다. 화면에서 미리 체크해 둔다. */
data class ChangeItem(val kind: ChangeKind, val title: String, val detail: String, val needed: Boolean, val link: String? = null)

object DriverChanges {
    val SEAT_HEAT_LEVELS = 0..3
    /** 메모리 시트 번호. Pleos Connect 차량은 운전석 기억을 1·2번까지 받는다(checkSeatMemoryCapabilities). */
    val SEAT_MEMORY_SLOTS = 1..2

    fun seatHeatText(level: Int?): String = when (level) {
        null -> "모름"
        0 -> "끔"
        else -> "${level}단"
    }

    fun wheelHeatText(on: Boolean?): String = when (on) {
        null -> "모름"
        true -> "켬"
        false -> "끔"
    }

    /**
     * 새 운전자 설정과 지금 차 상태를 비교해 바꿀 것을 만든다.
     * 지금 값을 읽지 못했으면 바꾸는 쪽으로 체크해 둔다. 미러는 운전자가 바뀌면 늘 다시 맞춰야 한다.
     */
    fun items(profile: DriverProfile?, cabin: CabinState): List<ChangeItem> = buildList {
        profile?.temperatureCelsius?.let { target ->
            val now = cabin.temperature
            add(
                ChangeItem(
                    ChangeKind.TEMPERATURE, "실내 온도",
                    if (now == null) "${degree(target)}도로" else "${degree(now)}도 → ${degree(target)}도",
                    needed = now == null || abs(now - target) >= 0.5f,
                ),
            )
        }
        profile?.seatHeat?.let { target ->
            add(
                ChangeItem(
                    ChangeKind.SEAT_HEAT, "시트 열선", "${seatHeatText(cabin.seatHeat)} → ${seatHeatText(target)}",
                    needed = cabin.seatHeat != target,
                ),
            )
        }
        profile?.wheelHeat?.let { target ->
            val now = cabin.wheelHeat?.let { it > 0 }
            add(
                ChangeItem(
                    ChangeKind.WHEEL_HEAT, "핸들 열선", "${wheelHeatText(now)} → ${wheelHeatText(target)}",
                    needed = now != target,
                ),
            )
        }
        // 지금 어느 번호 위치인지는 읽을 수 없어 늘 불러오는 쪽으로 체크한다. 같은 번호면 시트가 움직이지 않을 뿐이다.
        profile?.seatMemory?.let { slot ->
            add(ChangeItem(ChangeKind.SEAT_MEMORY, "메모리 시트", "${slot}번 불러오기 · 시트와 사이드미러", needed = true))
        }
        // 차의 메모리 시트는 사이드미러 각도도 함께 기억한다. 룸미러는 수동이라 차에도 SDK에도 맞출 방법이 없다.
        if (profile?.seatMemory != null) {
            add(ChangeItem(ChangeKind.MIRRORS, "룸미러", "새 운전자 눈높이에 맞춰 주세요", needed = true))
        } else {
            add(ChangeItem(ChangeKind.MIRRORS, "사이드미러·룸미러", "새 운전자 눈높이에 맞춰 주세요", needed = true))
        }
        profile?.note?.trim()?.takeIf { it.isNotEmpty() }?.let { add(ChangeItem(ChangeKind.SEAT_POSITION, "메모", it, needed = true)) }
        if (profile != null) {
            val playlist = profile.playlistUrl.trim().takeIf(MusicLinks::isValid)
            val detail = listOfNotNull(profile.music.trim().takeIf { it.isNotEmpty() }, playlist?.let(MusicLinks::label)).joinToString(" · ")
            if (detail.isNotEmpty()) add(ChangeItem(ChangeKind.MUSIC, "음악", detail, needed = true, link = playlist))
        }
    }

    private fun degree(value: Float) = if (value % 1f == 0f) value.toInt().toString() else "%.1f".format(value)
}
