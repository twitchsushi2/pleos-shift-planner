package com.pleoscontest.app

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView

/**
 * 에뮬레이터 내비가 경로·경유지 요청에 어떻게 답하는지 보는 디버그 전용 화면.
 *   adb shell am start -n com.pleoscontest.app/.NaviCheckActivity
 *   (--ez observe true: 회전 안내만 본다, --es option FREE: 경로 옵션을 바꾼다)
 * 결과는 logcat의 NaviCheck 태그로 본다.
 */
class NaviCheckActivity : Activity(), NaviHelperEventListener {
    private lateinit var navi: NaviHelper
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var waypointSent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 20f; setPadding(32, 32, 32, 32) }
        setContentView(status)
        navi = NaviHelper(applicationContext)
        navi.initialize(
            {
                report("연결됨")
                navi.addListener(this)
                navi.getRouteStateInfo()
                navi.getCurrentLocationInfo()
                // --ez observe true: 지금 안내 중인 경로를 바꾸지 않고 정보만 본다.
                // --es option FREE: 안내 중인 경로의 옵션을 바꿔 운전자가 다른 길을 고른 상황을 흉내 낸다.
                intent.getStringExtra("option")?.let { option ->
                    report("경로 옵션 바꾸기 $option")
                    navi.changeRouteOption(RouteOption.valueOf(option))
                    return@initialize
                }
                if (intent.getBooleanExtra("observe", false)) {
                    navi.getTBTInfo()
                    navi.getDestinationInfo()
                    return@initialize
                }
                navi.requestTrip(TripRequest(latitude = BUSAN.first, longitude = BUSAN.second, poiId = "", poiName = "부산", poiSubId = ""))
                handler.postDelayed({ report("경로 요청"); navi.requestRoute(RouteInfo(latitude = BUSAN.first, longitude = BUSAN.second, poiName = "부산", poiId = "", address = "부산", poiSubId = "")) }, 3000)
            },
            { report("연결 실패: $it") },
        )
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        navi.removeListener(this)
        navi.release()
        super.onDestroy()
    }

    private fun report(message: String) {
        Log.i("NaviCheck", message)
        runOnUiThread { status.append(message + "\n") }
    }

    /** 경로 계산 중에 보낸 경유지는 무시되므로 경로가 시작된 뒤에 한 번만 보낸다. */
    override fun onRouteStarted(info: RouteStartInfo) {
        report("경로 시작: $info")
        if (waypointSent) return
        waypointSent = true
        handler.postDelayed({
            report("경유지 요청")
            navi.addWaypoint(RequestWaypointInfo(latitude = JUKAM.first, longitude = JUKAM.second, poiName = "죽암휴게소", waypointIndex = WaypointIndex.FIRST, poiId = "", address = "죽암휴게소", poiSubId = ""))
        }, 3000)
        handler.postDelayed({ navi.getWaypointInfo(); navi.getRouteStateInfo() }, 20000)
    }

    override fun onRoutingCompleted() = report("경로 계산 완료")
    override fun onRouteEnded() = report("경로 종료")
    override fun onRouteCancelled() = report("경로 취소")
    override fun onReRouteEnded() = report("재탐색 끝")
    override fun onWaypointChanged(info: WaypointChangedInfo) = report("경유지 변경: $info")
    override fun onWayPointArrived(info: WaypointArrivedInfo) = report("경유지 도착: $info")
    override fun onError(code: NaviErrorCode) = report("오류: $code")
    override fun onCurrentLocationInfo(info: CurrentLocationInfo) = report("현재 위치: $info")
    override fun onRouteStateInfo(info: RouteStateInfo) = report("경로 상태: $info")
    override fun onWayPointInfo(list: List<WaypointInfo>) = report("경유지 목록: $list")
    override fun onDrivingInfo(info: DrivingInfo) = report("주행 정보: $info")
    override fun onNavigationStatus(status: NavigationStatus) = report("내비 상태: $status")
    override fun onTBTInfo(list: List<TBTInfo>) = report("회전 안내 ${list.size}개: " + list.joinToString(" / ") { "${it.type} ${it.distance.toInt()}m ${it.description}" })
    override fun onTripInfo(info: TripInfo) = report("경로 요약: 거리 ${info.distance} 시간 ${info.eta}")

    private companion object {
        val BUSAN = 35.1796 to 129.0756
        val JUKAM = 36.4925 to 127.4620
    }
}
