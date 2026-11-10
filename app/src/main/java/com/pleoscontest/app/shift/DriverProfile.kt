package com.pleoscontest.app.shift

/**
 * 설정에 미리 등록해 두는 운전자. 출발 전에 고르면 이름과 운전 조건이 채워진다.
 * 취향은 교대할 때 다음 운전자에게 맞춰 안내하고, 음악·공조 연동의 입력값이 된다.
 */
data class DriverProfile(
    val id: String,
    val name: String = "",
    val novice: Boolean = false,
    val avoidNight: Boolean = false,
    /** 좋아하는 장르나 가수. */
    val music: String = "",
    /** 멜론·유튜브 뮤직 등의 플레이리스트 링크. 교대 때 그 음악 앱으로 열어 준다. */
    val playlistUrl: String = "",
    /** 선호 실내 온도(도). 비워 둘 수 있다. */
    val temperature: String = "",
    /** 시트 열선 단계(0=끔). null이면 상관없음. */
    val seatHeat: Int? = null,
    /** 핸들 열선. null이면 상관없음. */
    val wheelHeat: Boolean? = null,
    /** 차의 운전석 메모리 시트 번호(1·2). 교대 때 앱이 그 번호를 불러 시트와 사이드미러를 맞춘다. null이면 쓰지 않는다. */
    val seatMemory: Int? = null,
    val note: String = "",
) {
    /** 차에 넣을 수 있는 범위의 설정 온도. 비었거나 범위를 벗어나면 null. */
    val temperatureCelsius: Float?
        get() = temperature.trim().toFloatOrNull()?.takeIf { it in MIN_TEMPERATURE..MAX_TEMPERATURE }

    val tasteText: String
        get() = listOf(
            music.trim().let { if (it.isEmpty()) "" else "음악 $it" },
            playlistUrl.trim().takeIf(MusicLinks::isValid)?.let(MusicLinks::label).orEmpty(),
            temperature.trim().let { if (it.isEmpty()) "" else "실내 ${it}도" },
            seatHeat?.let { "시트 열선 ${DriverChanges.seatHeatText(it)}" }.orEmpty(),
            wheelHeat?.let { "핸들 열선 ${DriverChanges.wheelHeatText(it)}" }.orEmpty(),
            seatMemory?.let { "메모리 시트 ${it}번" }.orEmpty(),
            note.trim(),
        ).filter { it.isNotEmpty() }.joinToString(" · ")

    fun fill(driver: Driver) = driver.copy(name = name, novice = novice, avoidNight = avoidNight, profileId = id)

    companion object {
        /** 차량 공조가 받는 온도 범위. */
        const val MIN_TEMPERATURE = 16f
        const val MAX_TEMPERATURE = 32f
    }
}
