package com.pleoscontest.app

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView

/**
 * 운전자 취향(실내 온도, 시트 열선)을 Vehicle SDK로 차에 넣을 수 있는지 확인하는 디버그 전용 화면.
 *   adb shell am start -n com.pleoscontest.app/.HvacCheckActivity --ef temp 22 --ei memory 1
 * 결과는 logcat의 HvacCheck 태그로 본다.
 */
class HvacCheckActivity : Activity() {
    private lateinit var vehicle: Vehicle
    private lateinit var status: TextView
    private val driver = SeatArea(SeatValue.ROW_1_LEFT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 28f; setPadding(48, 48, 48, 48) }
        setContentView(status)
        val target = intent.getFloatExtra("temp", 22f)
        val memory = intent.getIntExtra("memory", 0)
        vehicle = Vehicle(applicationContext)
        vehicle.initialize()
        Handler(Looper.getMainLooper()).postDelayed({ check(target); if (memory > 0) checkMemory(memory) }, 1500)
    }

    private fun check(target: Float) {
        val hvac = vehicle.getHVAC()
        hvac.getHvacTemperature(driver, TemperatureUnit.CELSIUS, { report("지금 설정 온도 $it") }, { report("온도 읽기 실패: $it") })
        hvac.setHvacTemperature(driver, target, TemperatureUnit.CELSIUS, { _, value -> report("온도 설정 성공 $value") }, { report("온도 설정 실패: $it") })
        vehicle.getSeat().setHvacSeatTemperature(driver, 1, { _, value -> report("시트 열선 설정 성공 $value") }, { report("시트 열선 설정 실패: $it") })
        Handler(Looper.getMainLooper()).postDelayed({
            hvac.getHvacTemperature(driver, TemperatureUnit.CELSIUS, { report("다시 읽은 온도 $it") }, { report("온도 다시 읽기 실패: $it") })
        }, 2000)
    }

    /** 시트 기억을 몇 번까지 받는지와 불러오기가 되는지. */
    private fun checkMemory(slot: Int) {
        val seat = vehicle.getSeat()
        seat.checkSeatMemoryCapabilities({ config, bounds -> report("시트 기억 지원 $config $bounds") }, { report("시트 기억 지원 확인 실패: $it") })
        seat.getCurrentSeatForeAftPosition(driver, { report("시트 앞뒤 위치 $it") }, { report("시트 위치 읽기 실패: $it") })
        seat.selectSeatMemory(driver, slot) { report("시트 기억 ${slot}번 불러오기 실패: $it") }
        Handler(Looper.getMainLooper()).postDelayed({
            seat.getCurrentSeatForeAftPosition(driver, { report("불러온 뒤 시트 앞뒤 위치 $it") }, { report("시트 위치 다시 읽기 실패: $it") })
        }, 3000)
    }

    private fun report(text: String) {
        Log.i("HvacCheck", text)
        runOnUiThread { status.append(text + "\n") }
    }

    override fun onDestroy() {
        vehicle.release()
        super.onDestroy()
    }
}
