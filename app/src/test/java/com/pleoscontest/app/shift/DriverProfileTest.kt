package com.pleoscontest.app.shift

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverProfileTest {
    @Test
    fun `입력한 취향만 안내 문구에 들어간다`() {
        assertEquals("음악 재즈 · 실내 22도", DriverProfile("a", music = " 재즈 ", temperature = "22").tasteText)
        assertEquals("", DriverProfile("a").tasteText)
    }

    @Test
    fun `차에 넣을 수 없는 온도는 쓰지 않는다`() {
        assertEquals(22f, DriverProfile("a", temperature = "22").temperatureCelsius)
        assertEquals(null, DriverProfile("a", temperature = "99").temperatureCelsius)
        assertEquals(null, DriverProfile("a", temperature = "").temperatureCelsius)
    }

    @Test
    fun `프로필을 고르면 이름과 운전 조건이 채워진다`() {
        val filled = DriverProfile("a", name = "민", novice = true, avoidNight = true).fill(Driver("2", ""))
        assertEquals("2", filled.id)
        assertEquals("민", filled.name)
        assertEquals("a", filled.profileId)
        assertTrue(filled.novice && filled.avoidNight)
    }
}
