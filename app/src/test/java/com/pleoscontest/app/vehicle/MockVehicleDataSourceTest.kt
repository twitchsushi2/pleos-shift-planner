package com.pleoscontest.app.vehicle

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockVehicleDataSourceTest {
    @Test
    fun `기본 시나리오는 주차에서 시작해 주차로 끝난다`() = runTest {
        val states = MockVehicleDataSource(loop = false).states().toList()
        assertTrue(states.first().isParked)
        assertTrue(states.last().isParked)
        assertEquals(60f, states.maxOf { it.speedKmh })
    }

    @Test
    fun `주차 중에는 속도가 0이다`() = runTest {
        val states = MockVehicleDataSource(loop = false).states().toList()
        assertTrue(states.filter { it.isParked }.all { it.speedKmh == 0f })
    }

    @Test
    fun `원하는 시나리오를 끼워 넣을 수 있다`() = runTest {
        val scenario = listOf(VehicleState(30f, Gear.DRIVE), VehicleState(0f, Gear.REVERSE))
        val states = MockVehicleDataSource(scenario, tickMillis = 1, loop = false).states().toList()
        assertEquals(scenario, states)
    }
}
