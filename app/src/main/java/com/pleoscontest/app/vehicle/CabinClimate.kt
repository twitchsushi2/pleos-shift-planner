package com.pleoscontest.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.pleoscontest.app.shift.CabinState
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 운전석 공조(실내 온도, 시트 열선, 핸들 열선)를 읽고 바꾸고, 메모리 시트 위치를 불러온다.
 * 권한: 읽기 pleos.car.permission.CAR_CLIMATE·CAR_SEATS, 바꾸기 CONTROL_CAR_CLIMATE·CONTROL_CAR_SEATS.
 * 바꾸기는 운전자가 체크해서 승인한 항목만 부른다. Pleos Connect가 아니면 아무것도 바꾸지 않고 실패를 알린다.
 */
class CabinClimate(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val vehicle: Vehicle? =
        if (PleosVehicleDataSource.isAvailable(context)) {
            runCatching { Vehicle(context.applicationContext).also { it.initialize() } }
                .onFailure { Log.w(TAG, "Vehicle SDK 초기화 실패", it) }
                .getOrNull()
        } else {
            null
        }

    /** 지금 운전석 상태. 값마다 따로 읽고, 늦거나 실패한 값은 null로 둔다. */
    suspend fun read(): CabinState {
        val v = vehicle ?: return CabinState()
        return CabinState(
            temperature = readValue { ok, fail -> v.getHVAC().getHvacTemperature(DRIVER, TemperatureUnit.CELSIUS, ok, fail) },
            seatHeat = readValue { ok, fail -> v.getSeat().getHvacSeatTemperature(DRIVER, ok, fail) },
            wheelHeat = readValue { ok, fail -> v.getSteeringWheel().getHvacSteeringWheelHeatState(ok, fail) },
        )
    }

    /** 결과는 SDK 스레드에서 올 수 있다. 설정 직후 읽은 값은 옛 값일 수 있어 성공 콜백만 믿는다. */
    fun setDriverTemperature(celsius: Float, done: (Boolean) -> Unit) =
        write("온도", done) { v, ok, fail -> v.getHVAC().setHvacTemperature(DRIVER, celsius, TemperatureUnit.CELSIUS, { _, _ -> ok() }, fail) }

    fun setSeatHeat(level: Int, done: (Boolean) -> Unit) =
        write("시트 열선", done) { v, ok, fail -> v.getSeat().setHvacSeatTemperature(DRIVER, level, { _, _ -> ok() }, fail) }

    fun setWheelHeat(on: Boolean, done: (Boolean) -> Unit) =
        write("핸들 열선", done) { v, ok, fail -> v.getSteeringWheel().setHvacSteeringWheelHeat(if (on) 1 else 0, { ok() }, fail) }

    /**
     * 차의 메모리 시트 [slot]번 위치를 불러온다. 시트가 움직이므로 정차 중(교대 화면, 출발 전 계획)에만 부른다.
     * 성공 콜백이 없는 API라, 시간 안에 실패가 오지 않으면 된 것으로 본다.
     */
    fun selectSeatMemory(slot: Int, done: (Boolean) -> Unit) {
        val v = vehicle ?: return done(false)
        var settled = false
        val finish = { ok: Boolean -> if (!settled) { settled = true; done(ok) } }
        runCatching {
            v.getSeat().selectSeatMemory(DRIVER, slot) {
                Log.w(TAG, "메모리 시트 ${slot}번 불러오기 실패", it)
                main.post { finish(false) }
            }
        }.onFailure {
            Log.w(TAG, "메모리 시트 ${slot}번 불러오기 실패", it)
            return finish(false)
        }
        main.postDelayed({ finish(true) }, MEMORY_SETTLE_MS)
    }

    fun release() {
        vehicle?.release()
    }

    private suspend fun <T> readValue(call: ((T) -> Unit, (Exception) -> Unit) -> Unit): T? = withTimeoutOrNull(READ_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            runCatching {
                call({ if (continuation.isActive) continuation.resume(it) }) {
                    Log.w(TAG, "읽기 실패", it)
                    if (continuation.isActive) continuation.resume(null)
                }
            }.onFailure {
                Log.w(TAG, "읽기 실패", it)
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    private fun write(what: String, done: (Boolean) -> Unit, call: (Vehicle, () -> Unit, (Exception) -> Unit) -> Unit) {
        val v = vehicle ?: return done(false)
        val fail = { e: Exception ->
            Log.w(TAG, "$what 설정 실패", e)
            done(false)
        }
        runCatching { call(v, { done(true) }, fail) }.onFailure { fail(it as? Exception ?: Exception(it)) }
    }

    private companion object {
        const val TAG = "CabinClimate"
        const val READ_TIMEOUT_MS = 2000L
        /** 메모리 시트 요청 뒤 실패를 기다리는 시간. */
        const val MEMORY_SETTLE_MS = 1500L
        val DRIVER = SeatArea(SeatValue.ROW_1_LEFT)
    }
}
