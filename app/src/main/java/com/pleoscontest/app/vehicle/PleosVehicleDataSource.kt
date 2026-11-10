package com.pleoscontest.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart

/**
 * Pleos Connect의 Vehicle SDK로 기어를 읽는다(권한 pleos.car.permission.CAR_DRIVE).
 * Vehicle SDK 2.3.2에는 차량 속도 API가 없어서, 속도만은 표준 차량 API([CarVehicleDataSource])에서 가져와 합친다.
 */
class PleosVehicleDataSource(private val context: Context) : VehicleDataSource {
    override val name = "Pleos Vehicle SDK(기어) + 차량 API(속도)"

    /**
     * 기어는 Pleos 값을 먼저 쓰고, 모르면(권한이 없거나 구독이 실패하면) 표준 차량 API의 기어를 쓴다.
     * CAR_DRIVE는 App Market 협의가 필요한 권한이라, 막혀도 D에 넣으면 출발을 알아채야 한다.
     */
    override fun states(): Flow<VehicleState> =
        combine(
            pleosGear().onStart { emit(Gear.UNKNOWN) },
            CarVehicleDataSource(context).states().onStart { emit(VehicleState()) },
        ) { gear, car -> VehicleState(speedKmh = car.speedKmh, gear = if (gear != Gear.UNKNOWN) gear else car.gear) }

    private fun pleosGear(): Flow<Gear> = callbackFlow {
        // SDK가 붙지 않으면 기어는 모르는 채로 두고(표준 API 값을 쓴다) 앱은 그대로 돈다.
        val vehicle = runCatching { Vehicle(context).also { it.initialize() } }
            .onFailure { Log.w(TAG, "Vehicle SDK 초기화 실패", it) }
            .getOrNull() ?: run { awaitClose {}; return@callbackFlow }
        val gear = vehicle.getGear()

        val listener = object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) {
                trySend(gear.toGear())
            }

            override fun onFailed(e: Exception) {
                Log.w(TAG, "기어 구독 실패", e)
            }
        }
        gear.registerGearSelection(listener)
        // 구독은 바뀔 때만 알려 주므로 시작할 때 현재 값을 한 번 읽어 둔다.
        gear.getCurrentGearSelectionState({ trySend(it.toGear()) }, { Log.w(TAG, "현재 기어 읽기 실패", it) })

        awaitClose {
            gear.unregisterGearSelection(listener)
            vehicle.release()
        }
    }

    private fun VehicleGear?.toGear(): Gear = when (this) {
        VehicleGear.GEAR_PARK -> Gear.PARK
        VehicleGear.GEAR_REVERSE -> Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
        VehicleGear.GEAR_UNKNOWN, null -> Gear.UNKNOWN
        else -> Gear.DRIVE // GEAR_DRIVE, GEAR_1~9
    }

    companion object {
        private const val TAG = "PleosVehicleDataSource"

        /** Pleos Connect의 차량 서비스가 깔려 있는지. SDK가 바인딩하는 액션으로 확인한다. */
        fun isAvailable(context: Context): Boolean =
            context.packageManager
                .queryIntentServices(Intent("ai.pleos.playground.vehicle.VEHICLE_START"), 0)
                .isNotEmpty()
    }
}
