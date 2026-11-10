package com.pleoscontest.app.vehicle

import android.content.Context
import android.content.pm.PackageManager
import com.pleoscontest.app.BuildConfig

object VehicleDataSources {
    /** -Pvehicle=mock|car|pleos|auto 로 빌드할 때 정한 값을 따른다. auto는 Pleos Connect → pleos, 다른 차량 OS → car, 그 밖 → mock. */
    fun create(context: Context): VehicleDataSource {
        val isCar = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        return when (BuildConfig.VEHICLE_SOURCE) {
            "mock" -> MockVehicleDataSource()
            "car" -> CarVehicleDataSource(context)
            "pleos" -> PleosVehicleDataSource(context)
            else -> when {
                PleosVehicleDataSource.isAvailable(context) -> PleosVehicleDataSource(context)
                isCar -> CarVehicleDataSource(context)
                else -> MockVehicleDataSource()
            }
        }
    }
}
