package com.pleoscontest.app.vehicle

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 주차 → 출발해 가속 → 정속 → 감속해 정차 → 주차를 계속 되풀이한다.
 * [scenario]를 바꿔 끼우면 특정 상황(급정거, 후진 주차 등)만 따로 시험할 수 있다.
 */
class MockVehicleDataSource(
    private val scenario: List<VehicleState> = defaultScenario(),
    private val tickMillis: Long = 500,
    private val loop: Boolean = true,
) : VehicleDataSource {
    override val name = "목(시뮬레이션)"

    override fun states(): Flow<VehicleState> = flow {
        do {
            for (state in scenario) {
                emit(state)
                delay(tickMillis)
            }
        } while (loop)
    }

    companion object {
        fun defaultScenario(): List<VehicleState> = buildList {
            repeat(4) { add(VehicleState(0f, Gear.PARK)) }
            add(VehicleState(0f, Gear.DRIVE))
            for (v in 0..60 step 5) add(VehicleState(v.toFloat(), Gear.DRIVE))
            repeat(10) { add(VehicleState(60f, Gear.DRIVE)) }
            for (v in 60 downTo 0 step 10) add(VehicleState(v.toFloat(), Gear.DRIVE))
            add(VehicleState(0f, Gear.PARK))
        }
    }
}
