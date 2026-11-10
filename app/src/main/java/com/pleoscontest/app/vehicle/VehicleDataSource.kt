package com.pleoscontest.app.vehicle

import kotlinx.coroutines.flow.Flow

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

data class VehicleState(
    val speedKmh: Float = 0f,
    val gear: Gear = Gear.UNKNOWN,
) {
    val isParked: Boolean get() = gear == Gear.PARK
}

/**
 * 앱 화면은 차량 데이터가 어디서 오는지 모른 채 이 인터페이스만 본다.
 *
 * - [MockVehicleDataSource]: 정해진 주행 시나리오를 돌린다. 폰 에뮬레이터·단위 테스트용.
 * - [CarVehicleDataSource]: 표준 AAOS CarPropertyManager. Automotive 에뮬레이터에서는 VHAL 값을 주입해 시험한다.
 * - [PleosVehicleDataSource]: Pleos Connect Vehicle SDK. Pleos Connect 에뮬레이터·실차용.
 */
interface VehicleDataSource {
    val name: String
    fun states(): Flow<VehicleState>
}
