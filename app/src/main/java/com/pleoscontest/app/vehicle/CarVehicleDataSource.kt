package com.pleoscontest.app.vehicle

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 표준 Android Automotive 차량 속성을 읽는다. 에뮬레이터에서는 tools/vhal.sh로 속도·기어를 바꿔 가며 시험한다.
 * 속도(PERF_VEHICLE_SPEED)는 CAR_SPEED 런타임 권한이 있어야 들어온다. 없으면 기어만 바뀐다.
 */
class CarVehicleDataSource(private val context: Context) : VehicleDataSource {
    override val name = "차량 API(CarPropertyManager)"

    override fun states(): Flow<VehicleState> = callbackFlow {
        val car = Car.createCar(context)
        val properties = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        var state = VehicleState()

        val callback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                // 센서가 값을 못 주면 비었거나 다른 형으로 올 수 있다. 그런 값은 버리고 이전 값을 둔다.
                if (value.status != CarPropertyValue.STATUS_AVAILABLE) return
                state = when (value.propertyId) {
                    // VHAL 속도 단위는 m/s다.
                    VehiclePropertyIds.PERF_VEHICLE_SPEED -> (value.value as? Float)?.let { state.copy(speedKmh = it * 3.6f) } ?: return
                    VehiclePropertyIds.GEAR_SELECTION -> (value.value as? Int)?.let { state.copy(gear = it.toGear()) } ?: return
                    else -> state
                }
                trySend(state)
            }

            override fun onErrorEvent(propertyId: Int, areaId: Int) {
                Log.w(TAG, "차량 속성 오류: ${VehiclePropertyIds.toString(propertyId)}")
            }
        }

        for (id in listOf(VehiclePropertyIds.PERF_VEHICLE_SPEED, VehiclePropertyIds.GEAR_SELECTION)) {
            try {
                @Suppress("DEPRECATION") // subscribePropertyEvents는 API 35부터라 minSdk 34에서는 이걸 쓴다.
                properties.registerCallback(callback, id, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            } catch (e: SecurityException) {
                Log.w(TAG, "권한이 없어 구독하지 못함: ${VehiclePropertyIds.toString(id)}", e)
            }
        }

        awaitClose {
            properties.unregisterCallback(callback)
            car.disconnect()
        }
    }

    private fun Int.toGear(): Gear = when (this) {
        VehicleGear.GEAR_PARK -> Gear.PARK
        VehicleGear.GEAR_REVERSE -> Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
        VehicleGear.GEAR_DRIVE -> Gear.DRIVE
        in VehicleGear.GEAR_FIRST..VehicleGear.GEAR_NINTH -> Gear.DRIVE
        else -> Gear.UNKNOWN
    }

    private companion object {
        const val TAG = "CarVehicleDataSource"
    }
}
