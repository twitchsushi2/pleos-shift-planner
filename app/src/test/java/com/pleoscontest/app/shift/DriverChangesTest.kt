package com.pleoscontest.app.shift

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverChangesTest {
    private val min = DriverProfile("m", name = "민", temperature = "22", seatHeat = 2, wheelHeat = true, music = "재즈", note = "시트 앞으로")

    private fun List<ChangeItem>.of(kind: ChangeKind) = first { it.kind == kind }

    @Test
    fun `지금 차 상태와 다른 것만 미리 체크한다`() {
        val items = DriverChanges.items(min, CabinState(temperature = 22f, seatHeat = 0, wheelHeat = 1))
        assertFalse(items.of(ChangeKind.TEMPERATURE).needed)
        assertTrue(items.of(ChangeKind.SEAT_HEAT).needed)
        assertFalse(items.of(ChangeKind.WHEEL_HEAT).needed)
        assertEquals("끔 → 2단", items.of(ChangeKind.SEAT_HEAT).detail)
    }

    @Test
    fun `차 상태를 읽지 못하면 바꾸는 쪽으로 체크한다`() {
        val items = DriverChanges.items(min, CabinState())
        assertTrue(items.filter { it.kind.auto }.all { it.needed })
        assertEquals("22도로", items.of(ChangeKind.TEMPERATURE).detail)
    }

    @Test
    fun `설정이 없는 운전자도 미러는 맞추게 한다`() {
        val items = DriverChanges.items(null, CabinState())
        assertEquals(listOf(ChangeKind.MIRRORS), items.map { it.kind })
    }

    @Test
    fun `플레이리스트가 있으면 음악 항목에서 열 수 있다`() {
        val withList = min.copy(playlistUrl = "https://www.melon.com/mymusic/playlist/mymusicplaylistview_inform.htm?plylstSeq=1")
        val music = DriverChanges.items(withList, CabinState()).of(ChangeKind.MUSIC)
        assertEquals("재즈 · 멜론 플레이리스트", music.detail)
        assertEquals(withList.playlistUrl, music.link)
        assertEquals(null, DriverChanges.items(min, CabinState()).of(ChangeKind.MUSIC).link)
    }

    @Test
    fun `직접 할 일에 메모와 음악이 들어간다`() {
        val kinds = DriverChanges.items(min, CabinState()).filterNot { it.kind.auto }.map { it.kind }
        assertEquals(listOf(ChangeKind.MIRRORS, ChangeKind.SEAT_POSITION, ChangeKind.MUSIC), kinds)
    }

    @Test
    fun `메모리 시트 번호가 있으면 차에서 바로 불러오고 미러는 룸미러만 남긴다`() {
        val items = DriverChanges.items(min.copy(seatMemory = 2), CabinState())
        val memory = items.of(ChangeKind.SEAT_MEMORY)
        assertTrue(memory.kind.auto)
        assertTrue(memory.needed)
        assertEquals("2번 불러오기 · 시트와 사이드미러", memory.detail)
        assertEquals("룸미러", items.of(ChangeKind.MIRRORS).title)
        assertEquals("사이드미러·룸미러", DriverChanges.items(min, CabinState()).of(ChangeKind.MIRRORS).title)
        assertTrue(DriverChanges.items(min, CabinState()).none { it.kind == ChangeKind.SEAT_MEMORY })
    }
}
