package com.pleoscontest.app.navi

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteOverviewShowInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.Handler
import android.util.Log
import com.pleoscontest.app.BuildConfig
import com.pleoscontest.app.shift.NaviRouteStops
import com.pleoscontest.app.shift.NaviTurn
import com.pleoscontest.app.shift.Place
import com.pleoscontest.app.shift.RestArea

/** 내비에서 가져온 지금 상태. 내비가 아직 주지 않은 값은 null이다. */
data class NaviLive(
    val destinationKm: Int? = null,
    val destinationMinutes: Int? = null,
    val waypointMinutes: Int? = null,
    val waypointKm: Int? = null,
    val turnMeters: Int? = null,
    val turnText: String? = null,
    val congestionKm: Int? = null,
    val speedLimit: Int? = null,
)

/** 내비가 앱에 알려 주는 일. 내비 스레드에서 불릴 수 있다. */
interface NaviEvents {
    /** 차의 현재 위치. 경로의 출발점이 된다. */
    fun onCurrentLocation(lat: Double, lng: Double)

    /** 남은 거리·시간, 다음 회전, 정체, 제한속도가 바뀌었다. */
    fun onNaviLive(live: NaviLive)

    /** 내비 검색에서 고른 장소. 주소와 지명 모두 여기로 온다. [routeKm]은 운전자가 고른 경로의 거리이고, 모르면 null. */
    fun onPlaceChosen(place: Place, routeKm: Int?)

    /** 운전자가 우리 앱을 거치지 않고 내비에서 직접 안내를 시작했다. */
    fun onNaviDestination(place: Place, distanceKm: Int, minutes: Int)

    /**
     * 내비가 안내 중인 경로가 지나는 휴게소. 경로 시작·재탐색 뒤와 주행 중 회전 안내를 물을 때마다 온다.
     * [destinationName]은 그 경로의 목적지(모르면 null), [routeKm]·[routeMinutes]는 경로를 시작할 때 내비가 알려 준 값이다.
     */
    fun onNaviRouteStops(stops: NaviRouteStops, destinationName: String?, routeKm: Int?, routeMinutes: Int?)

    /** 출발 전 경로 미리보기 결과. */
    fun onTripPreview(distanceKm: Int, minutes: Int)

    /** 교대 휴게소가 내비 경유지로 잡혔다. */
    fun onWaypointSet(name: String, minutes: Int)

    /** 실제 주행에서 경유지에 도착했다. */
    fun onWaypointArrived()

    /** 실제 주행에서 목적지에 도착했다. */
    fun onDestinationArrived()

    fun onNaviError(code: String)
}

/**
 * Pleos 내비에 경로와 교대 휴게소를 넘긴다. 내비가 없는 기기(폰, 구글 AAOS)에서는 아무것도 하지 않는다.
 *
 * 에뮬레이터에서 확인한 내비 동작:
 * - 경로 계산이 끝나기 전에 보낸 경유지는 무시된다. 그래서 경로가 시작될 때까지 들고 있다가 보낸다.
 * - 경유지를 넣으면 내비가 경로를 취소했다가(onRouteCancelled) 다시 계산한다. 이때의 취소는 사용자의 취소가 아니다.
 * - 경유지는 최대 2개지만, 교대는 다음 한 곳만 등록한다(FIRST).
 * - 경로가 없을 때(운전자가 내비 안내를 끈 뒤) 경유지만 넣으면 내비에 경로 탐색 오류 창이 뜬다. 목적지와 함께 요청한다.
 * - 주소·지명 검색 API는 없다. 내비 검색 화면(SearchPlace)에서 고른 결과를 DestinationInfo로 받는다.
 * - 좌표만 보내도 동작한다. POI ID가 없으니 poiId는 비워 보낸다.
 * - 경유지를 지우면 내비가 경로 후보 화면을 앱 위에 띄우고 5초 뒤 스스로 안내를 시작한다. 시작했다는 이벤트는 따로 오지 않는다.
 */
class NaviGateway(context: Context, private val events: NaviEvents) : NaviHelperEventListener {
    private val appContext = context.applicationContext
    private var navi: NaviHelper? = null
    private var connected = false
    private var routing = false
    private var routeActive = false
    private var destination: Place? = null
    private var routeWithWaypoint: RestArea? = null
    private var overviewPending = false
    private var searching = false
    /** 찍는 중 운전자가 고른 경로 후보의 거리. 같은 거리의 길로 휴게소를 고르는 데 쓴다. */
    private var searchRouteKm: Int? = null
    /** 지금 안내하는 경로를 시작할 때 받은 거리·시간. 재탐색하면 더는 맞지 않아 지운다. */
    private var startedRoute: RouteStartInfo? = null
    private var live = NaviLive()
    private var pendingWaypoint: RestArea? = null
    /** 실제 주행에서 경유지에 들렀다. 내비가 이미 지나간 경유지라 지울 필요가 없다. */
    private var waypointArrived = false
    private var pendingRoute: Place? = null
    private var pendingPreview: Place? = null
    /** 운전자가 내비에서 직접 시작한 안내. 목적지 정보가 오면 앱에 알린다. */
    private var driverRoute: RouteStartInfo? = null
    private var destinationRetries = 0
    private val handler = Handler(Looper.getMainLooper())

    /** 이 차에 Pleos 내비가 있다. 없으면 목적지는 앱 안에서 찾는다. */
    val available: Boolean = appContext.packageManager.queryIntentServices(Intent(NAVI_ACTION), 0).isNotEmpty()

    init {
        if (available) {
            val helper = NaviHelper(appContext)
            navi = helper
            helper.initialize(
                {
                    // 연결 알림도 SDK 스레드에서 올 수 있어, 다른 내비 신호처럼 화면 스레드에서 처리한다.
                    onMain {
                        helper.addListener(this)
                        connected = true
                        helper.getCurrentLocationInfo()
                        // 앱이 다시 켜졌을 때 이미 안내 중인 경로가 있으면 그 휴게소부터 받는다.
                        helper.getTBTInfo()
                        pendingPreview?.let(::preview)
                        pendingRoute?.let(::startRoute)
                    }
                },
                { Log.w(TAG, "내비 연결 실패", it) },
            )
        }
    }

    /** 경로 상태와 회전 안내를 다시 묻는다. 남은 거리·시간(onDrivingInfo)은 내비가 알아서 보내 준다. */
    fun refreshLive() {
        if (!routeActive) return
        navi?.getRouteStateInfo()
        navi?.getTBTInfo()
    }

    private fun publish(change: (NaviLive) -> NaviLive) {
        live = change(live)
        events.onNaviLive(live)
    }

    /** 현재 위치를 다시 묻는다. 답은 [NaviEvents.onCurrentLocation]으로 온다. */
    fun refreshLocation() {
        if (connected) navi?.getCurrentLocationInfo()
    }

    /** 경로를 시작하지 않고 거리와 시간만 묻는다. 내비 기준 값은 현재 위치에서 출발한다. */
    fun preview(place: Place) {
        val helper = navi ?: return
        if (!connected) { pendingPreview = place; return }
        pendingPreview = null
        helper.requestTrip(TripRequest(latitude = place.lat, longitude = place.lng, poiId = "", poiName = place.name, poiSubId = ""))
    }

    fun startRoute(place: Place) {
        val helper = navi ?: return
        if (!connected) { pendingRoute = place; return }
        pendingRoute = null
        if (routeActive && destination == place) return
        destination = place
        routing = true
        helper.requestRoute(routeInfo(place))
    }

    /**
     * 내비 검색 화면을 연다. 주소·지명 검색은 공개 API가 없어 내비 검색을 그대로 쓰고,
     * 운전자가 고르면 내비의 목적지 정보로 이름과 좌표를 받아 온다.
     */
    fun searchPlace(keyword: String) {
        val helper = navi ?: return
        searching = true
        searchRouteKm = null
        destinationRetries = 0
        helper.requestNavigationScreen(NavigationScreenRequest.SearchPlace(keyword))
    }

    /**
     * 내비 검색 첫 화면("어디로 갈까요?" 검색창과 최근 목적지)을 연다. 고르면 검색과 똑같이 목적지 정보로 받는다.
     * 검색어 없이 SearchPlace를 보내면 내비가 거절해서(search place query is invalid) 이 화면을 쓴다.
     */
    fun openSearch() {
        val helper = navi ?: return
        searching = true
        searchRouteKm = null
        destinationRetries = 0
        helper.requestNavigationScreen(NavigationScreenRequest.RecentDestinations)
    }

    /**
     * 고르지 않고 검색을 그만뒀다. 찾는 중 표시가 남으면 나중에 앱이 시작한 안내의 목적지를
     * 운전자가 고른 곳으로 잘못 받아 계획을 지운다.
     */
    fun cancelSearch() {
        searching = false
    }

    /** 진짜 내비 지도에 경로 전체를 띄운다. 경로가 있어야 보여서, 없으면 먼저 시작하고 시작되면 띄운다. */
    fun showOverview(place: Place) {
        if (routeActive && destination == place) {
            requestOverview()
        } else {
            overviewPending = true
            startRoute(place)
        }
    }

    private fun requestOverview() {
        navi?.requestNavigationScreen(NavigationScreenRequest.OverviewRoute(RouteOverviewShowInfo(OVERVIEW_MILLIS)))
    }

    /** 다음 교대 휴게소를 경유지로 넣는다. 경로를 계산하는 중이면 끝난 뒤에 넣는다. */
    fun setWaypoint(stop: RestArea) {
        val helper = navi ?: return
        if (routing) { pendingWaypoint = stop; return }
        pendingWaypoint = null
        waypointArrived = false
        routing = true
        val waypoint = RequestWaypointInfo(latitude = stop.lat, longitude = stop.lng, poiName = stop.name, waypointIndex = WaypointIndex.FIRST, poiId = "", address = stop.name, poiSubId = "")
        val place = destination
        if (!routeActive && place != null) {
            routeWithWaypoint = stop // 이 요청은 경유지 변경이 아니라 경로 시작 이벤트로 끝난다.
            helper.requestRouteWithWaypoints(RouteWithWaypointsRequest(routeInfo(place), listOf(waypoint)))
        } else {
            helper.addWaypoint(waypoint)
        }
    }

    /**
     * 교대 휴게소 경유지를 지운다. 내비가 경로 후보 화면을 띄우는 경우에만 true를 돌려준다.
     * 휴게소에 실제로 들렀으면 내비가 이미 지나간 경유지라 건드리지 않는다.
     */
    fun clearWaypoint(): Boolean {
        pendingWaypoint = null
        if (waypointArrived) {
            waypointArrived = false
            return false
        }
        if (!routeActive || navi == null) return false
        navi?.removeWaypoint(WaypointIndex.FIRST)
        return true
    }

    /**
     * 앱이 다시 켜져 저장한 여행을 이어 간다. 내비가 이미 그 목적지로 안내 중일 테니 목적지를 알고 있는 것으로 둔다.
     * 그래야 회전 안내로 오는 휴게소를 이 여행의 경로로 받는다.
     */
    fun adoptDestination(place: Place) {
        if (destination == null) destination = place
    }

    fun cancelRoute() {
        pendingWaypoint = null
        routing = false
        destination = null
        navi?.cancelRoute()
    }

    fun release() {
        navi?.removeListener(this)
        navi?.release()
        navi = null
    }

    override fun onRouteStarted(info: RouteStartInfo) = onMain {
        startedRoute = info
        Log.i(TAG, "경로 시작 routing=$routing searching=$searching")
        // 우리 앱이 요청한 경로가 아니면 운전자가 내비에서 직접 목적지를 잡은 것이다.
        if (!routing && !searching) {
            driverRoute = info
            // 목적지가 바뀌었을 수 있다. 새 목적지 정보가 올 때까지는 모른다고 둬, 새 경로의 휴게소가 옛 목적지 계획에 섞이지 않게 한다.
            destination = null
            destinationRetries = 0
            navi?.getDestinationInfo()
        }
        routing = false
        routeActive = true
        refreshLive()
        // 경로 후보 화면에서 운전자가 경로를 고르는 동안은 목적지가 비어 온다. 고를 시간을 다시 넉넉히 준다.
        if (searching) {
            searchRouteKm = info.distance / 1000
            destinationRetries = 0
            navi?.getDestinationInfo()
        }
        routeWithWaypoint?.let { events.onWaypointSet(it.name, info.duration / 60) }
        routeWithWaypoint = null
        if (overviewPending) {
            overviewPending = false
            requestOverview()
        }
        pendingWaypoint?.let(::setWaypoint)
    }

    /** 경로 안내 중인지 내비 상태로 판단한다. 운전자가 내비에서 안내를 끄면 FREE_DRIVE가 된다. */
    override fun onNavigationStatus(status: NavigationStatus) = onMain {
        Log.i(TAG, "내비 상태 $status searching=$searching")
        routeActive = status == NavigationStatus.ROUTE_DRIVE
    }

    override fun onWaypointChanged(info: WaypointChangedInfo) = onMain {
        routing = false
        val start = info.routeStartInfo
        events.onWaypointSet(start.name, start.duration / 60)
        pendingWaypoint?.let(::setWaypoint)
    }

    override fun onWayPointArrived(info: WaypointArrivedInfo) = onMain {
        waypointArrived = true
        events.onWaypointArrived()
    }

    override fun onDestinationArrived(info: DestinationArrivedInfo) = onMain { events.onDestinationArrived() }

    override fun onDrivingInfo(info: DrivingInfo) = onMain {
        val waypoint = info.waypoints.firstOrNull()
        publish {
            it.copy(
                // 경로를 다시 계산하는 동안에는 0이 온다. 도착한 것이 아니니 값이 없는 것으로 둔다.
                destinationKm = (info.destination.distance / 1000).takeIf { info.destination.duration > 0 },
                destinationMinutes = (info.destination.duration / 60).takeIf { info.destination.duration > 0 },
                waypointKm = waypoint?.let { w -> w.distance / 1000 },
                waypointMinutes = waypoint?.let { w -> w.duration / 60 },
            )
        }
    }

    override fun onTBTInfo(list: List<TBTInfo>) = onMain {
        val turn = list.firstOrNull() ?: return@onMain
        publish { it.copy(turnMeters = turn.distance.toInt(), turnText = turn.description) }
        // 회전 안내는 경로 전체가 온다. 그 안의 휴게소가 운전자가 고른 경로의 휴게소다.
        NaviRouteStops.parse(list.map { NaviTurn(it.type.name, it.distance, it.description) })?.let {
            events.onNaviRouteStops(it, destination?.name, startedRoute?.distance?.div(1000), startedRoute?.duration?.div(60))
        }
    }

    /** 운전자가 다른 길로 가 내비가 경로를 다시 찾았다. 새 경로의 휴게소를 받는다. */
    override fun onReRouteEnded() = onMain {
        startedRoute = null
        refreshLive()
    }

    override fun onRouteOptionChanged(option: RouteOption) = onMain {
        startedRoute = null
        refreshLive()
    }

    override fun onRouteStateInfo(info: RouteStateInfo) = onMain {
        val meters = info.trafficStatus?.summary?.totalCongestionDist ?: return@onMain
        publish { it.copy(congestionKm = (meters / 1000).toInt()) }
    }

    override fun onCurrentRoadSpeedLimit(limit: Int) = onMain { publish { it.copy(speedLimit = limit.takeIf { v -> v > 0 }) } }

    override fun onDestinationChanged() = onMain {
        Log.i(TAG, "목적지 바뀜 searching=$searching")
        if (searching) {
            destinationRetries = 0
            navi?.getDestinationInfo()
        }
    }

    override fun onDestinationInfo(info: DestinationInfo) = onMain {
        // 목적지 주소가 들어 있어 출시 빌드에서는 남기지 않는다.
        if (BuildConfig.DEBUG) Log.i(TAG, "목적지 정보: $info")
        // 주소로 고르면 좌표가 표시 좌표 칸에만 올 때가 있다.
        val lat = info.latitude.takeIf { it != 0.0 } ?: info.displayLatitude ?: 0.0
        val lng = info.longitude.takeIf { it != 0.0 } ?: info.displayLongitude ?: 0.0
        if (lat == 0.0 || lng == 0.0) {
            // 안내가 막 시작된 때(경로 후보 화면)에는 목적지가 비어 온다. 안내가 자리 잡을 때까지 몇 번 더 묻는다.
            if ((driverRoute != null || searching) && destinationRetries++ < DESTINATION_RETRIES) {
                handler.postDelayed({ navi?.getDestinationInfo() }, DESTINATION_RETRY_MILLIS)
            }
            return@onMain
        }
        val place = Place(info.name.ifBlank { info.address }, lat, lng)
        if (searching) {
            searching = false
            destination = place
            events.onPlaceChosen(place, searchRouteKm)
            // 목적지를 알게 됐으니 휴게소를 목적지 이름과 함께 다시 받는다.
            refreshLive()
            return@onMain
        }
        val started = driverRoute ?: return@onMain
        driverRoute = null
        destination = place
        events.onNaviDestination(place, started.distance / 1000, started.duration / 60)
        refreshLive()
    }

    override fun onCurrentLocationInfo(info: CurrentLocationInfo) = onMain { events.onCurrentLocation(info.latitude, info.longitude) }

    override fun onTripInfo(info: TripInfo) = onMain { events.onTripPreview(info.distance / 1000, info.eta / 60) }

    override fun onError(code: NaviErrorCode) = onMain {
        routing = false
        routeWithWaypoint = null
        Log.w(TAG, "내비 오류: $code")
        events.onNaviError(code.toString())
    }

    /**
     * 내비 신호는 여러 바인더 스레드에서 온다. 내부 상태(경로 계산 중, 안내 중, 목적지)를 잠금 없이 다루므로
     * 모두 화면 스레드로 옮겨 한 줄로 처리한다. 앱이 부르는 함수도 화면 스레드에서 불린다.
     */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    private fun routeInfo(place: Place) =
        RouteInfo(latitude = place.lat, longitude = place.lng, poiName = place.name, poiId = "", address = place.name, poiSubId = "")

    private companion object {
        const val TAG = "NaviGateway"
        const val OVERVIEW_MILLIS = 15_000
        /** 2초씩 1분. 경로 후보를 비교하며 천천히 골라도 앱이 돌아오기를 포기하지 않는다. */
        const val DESTINATION_RETRIES = 30
        const val DESTINATION_RETRY_MILLIS = 2_000L
        const val NAVI_ACTION = "ai.pleos.playground.intent.action.NAVI_START"
    }
}
