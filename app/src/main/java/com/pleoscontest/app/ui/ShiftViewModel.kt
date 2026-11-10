package com.pleoscontest.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.pleoscontest.app.shift.ChangeItem
import com.pleoscontest.app.shift.ChangeKind
import com.pleoscontest.app.shift.DriverChanges
import com.pleoscontest.app.shift.DriverProfile
import com.pleoscontest.app.shift.MusicLinks
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pleoscontest.app.BuildConfig
import com.pleoscontest.app.MainActivity
import com.pleoscontest.app.navi.NaviEvents
import com.pleoscontest.app.navi.NaviGateway
import com.pleoscontest.app.navi.NaviLive
import com.pleoscontest.app.navi.NaviOverlay
import com.pleoscontest.app.vehicle.CabinClimate
import com.pleoscontest.app.shift.Driver
import com.pleoscontest.app.shift.Josa
import com.pleoscontest.app.shift.NaviReroute
import com.pleoscontest.app.shift.NaviRouteStops
import com.pleoscontest.app.shift.NextShift
import com.pleoscontest.app.shift.Place
import com.pleoscontest.app.shift.Route
import com.pleoscontest.app.shift.RouteCatalog
import com.pleoscontest.app.shift.ShiftAnnouncer
import com.pleoscontest.app.shift.ShiftPlan
import com.pleoscontest.app.shift.ShiftPlanner
import com.pleoscontest.app.shift.TripState
import com.pleoscontest.app.shift.TripTracker
import com.pleoscontest.app.shift.VoiceReply
import com.pleoscontest.app.shift.VoiceReplyParser
import com.pleoscontest.app.shift.withTruckStops
import com.pleoscontest.app.vehicle.Gear
import com.pleoscontest.app.vehicle.VehicleDataSources
import com.pleoscontest.app.vehicle.VehicleState
import com.pleoscontest.app.voice.VoiceGuide
import com.pleoscontest.app.voice.VoiceListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** 1시간이 안 되면 분만 쓴다. */
fun durationText(minutes: Int): String = when {
    minutes < 60 -> "${minutes}분"
    minutes % 60 == 0 -> "${minutes / 60}시간"
    else -> "${minutes / 60}시간 ${minutes % 60}분"
}

enum class AppScreen { SETUP, SETTINGS, DRIVING, SHIFT, SUMMARY }

/** 출발 전 입력 순서. */
/** 운전자가 내비에서 직접 시작한 장거리 안내. 이 목적지로 교대 계획을 만들자고 제안한다. */
data class NaviSuggestion(val place: Place, val distanceKm: Int, val minutes: Int)

/** 교대를 마친 뒤 새 운전자의 플레이리스트를 틀지 묻는 중. [failed]면 열 수 있는 음악 앱이 없었다. */
data class MusicAsk(val driverName: String, val link: String, val label: String, val failed: Boolean = false)

/**
 * 운전자가 바뀔 때 챙길 것. 출발 전과 교대 때 보여 준다.
 * 차에서 바꿀 것은 [checked]로 고르고(바꿔야 하는 것은 미리 체크), 직접 할 것은 마치면 [done]에 체크한다.
 */
data class DriverChangeList(
    val driver: Driver,
    val items: List<ChangeItem> = emptyList(),
    val checked: Set<ChangeKind> = emptySet(),
    /** 차에서 바꿀 것은 바꾼 결과(성공/실패), 직접 할 것은 마쳤으면 true. */
    val done: Map<ChangeKind, Boolean> = emptyMap(),
    val loading: Boolean = true,
    val applying: Boolean = false,
    /** 플레이리스트를 열지 못했을 때 알려 줄 말. */
    val musicMessage: String? = null,
)

/** 이만큼 안이면 계획한 휴게소에서 교대하는 것으로 본다. */
private const val NEAR_STOP_KM = 3.0

enum class SetupStep { WELCOME, ROUTE, DRIVERS, REVIEW }

/** 한 차에서 번갈아 운전할 수 있는 사람 수. */
const val MAX_DRIVERS = 4

data class ShiftUiState(
    val vehicle: VehicleState = VehicleState(),
    val sourceName: String = "",
    val origin: String = RouteCatalog.CURRENT_LOCATION,
    /** 내비가 알려 준 차의 위치. 내비가 없으면 null. */
    val currentPlace: Place? = null,
    val destination: String = "",
    /** 내비 검색으로 고른 목적지. 목록의 도시가 아니라 주소나 지명일 수 있다. */
    val searchedPlace: Place? = null,
    /** 앱 안 검색으로 고른 출발지. */
    val searchedOrigin: Place? = null,
    /** 지금 계획에 쓰는 경로. 입력 중인 경로는 [enteredRoute]다. */
    /** 출발 전에는 비어 있는 자리 표시용이다. 확인 화면에 들어갈 때 실제 경로로 바뀐다. */
    val route: Route = Route(1.0, 1, emptyList()),
    val drivers: List<Driver> = listOf(Driver("1", "")),
    val step: SetupStep = SetupStep.WELCOME,
    val plan: ShiftPlan? = null,
    val trip: TripState? = null,
    val next: NextShift? = null,
    /** 운전자가 수락한 교대. 그 휴게소에 도착해 교대를 마칠 때까지 다음 교대가 바뀌지 않게 붙들어 둔다. 내비 연동 뒤에는 이때 경유지를 등록한다. */
    val acceptedShift: NextShift? = null,
    /** 내비가 알려 준 현재 위치 기준 경로 미리보기. 내비가 없으면 null. */
    val naviPreview: String? = null,
    /** 내비가 실제로 안내할 경로의 거리(km). 운전자가 경로 후보 중 고른 것이면 그 거리다. 같은 거리의 길을 골라 휴게소를 맞춘다. */
    val naviRouteKm: Int? = null,
    /** 내비가 안내하는 경로 그대로의 휴게소로 만든 경로. 있으면 앱이 찾은 길 대신 쓴다. */
    val naviRoute: Route? = null,
    /** 교대 휴게소가 내비 경유지로 잡혔다. */
    val waypointSet: Boolean = false,
    /** 정체로 다시 계산한 계획. 있으면 주행 중 패널이 "바꾸기 / 그대로"를 묻는다. */
    val proposal: ShiftPlan? = null,
    val arrived: Boolean = false,
    val finished: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 설정 화면을 열었다. 상세 설정은 정차 중에만 보이도록 기어를 넣으면 주행 화면이 먼저다. */
    val settingsOpen: Boolean = false,
    /** 설정에 등록해 둔 운전자. */
    val profiles: List<DriverProfile> = emptyList(),
    /** 설정에서 고치는 중인 운전자. 새로 추가할 때는 아직 [profiles]에 없다. */
    val editingProfile: DriverProfile? = null,
    val changes: DriverChangeList? = null,
    val musicAsk: MusicAsk? = null,
    val naviSuggestion: NaviSuggestion? = null,
    /** 혼자 가다 쉬는 중이면 쉰 시간(분). */
    val restedMinutes: Int? = null,
    /** 내비에서 가져온 남은 거리·시간, 다음 회전, 정체, 제한속도. */
    val naviLive: NaviLive? = null,
    /** 안내 뒤 운전자의 대답을 듣는 중이다. */
    val listening: Boolean = false,
    /** 지금 운전자가 연속 운전 한도를 넘겼다. */
    val overLimit: Boolean = false,
    /** 앱이 내비 옆에 나란히 놓여 있다. 회전 안내는 옆의 내비가 보여 주니 앱은 교대에만 집중한다. */
    val besideNavi: Boolean = false,
    /** 이 차에 Pleos 내비가 있어 목적지를 내비에서 찍는다. */
    val naviAvailable: Boolean = false,
    /** 차량 속도를 읽을 권한이 있다. 없으면 속도가 늘 0이라 간 거리와 정체를 셀 수 없다. */
    val speedAllowed: Boolean = true,
    /** 화물차 휴게소도 교대·휴식 장소로 쓴다. 설정에서 켠다. */
    val truckStops: Boolean = false,
) {
    val usesCurrentLocation: Boolean get() = origin.trim() == RouteCatalog.CURRENT_LOCATION
    val originLabel: String get() = if (usesCurrentLocation) "현재 위치" else origin
    /** 혼자 가면 교대가 아니라 쉬는 곳을 알려 준다. */
    val solo: Boolean get() = drivers.size == 1
    val word: String get() = if (solo) "휴식" else "교대"
    val wordWithObject: String get() = if (solo) "휴식을" else "교대를"
    fun profileOf(driver: Driver): DriverProfile? = profiles.firstOrNull { it.id == driver.profileId }
    /** 이번 교대로 운전대를 잡을 사람. 혼자 가면 지금 운전자다. */
    val upcomingDriver: Driver? get() = trip?.let { next?.nextDriver ?: it.driver }
    /** 출발 전 첫 운전자. 혼자 가도 맞출 것을 묻는다. */
    val firstDriver: Driver? get() = plan?.segments?.firstOrNull()?.driver
    /** 교대하는 곳 이름. 계획한 휴게소 근처가 아니면 지금 자리라고만 한다. */
    val shiftPlaceName: String
        get() = next?.stop?.takeIf { stop -> trip != null && abs(stop.km - trip.km) <= NEAR_STOP_KM }?.name ?: "지금 있는 곳"
    val namesFilled: Boolean get() = drivers.all { it.name.isNotBlank() }
    val canSwap: Boolean get() = !usesCurrentLocation && originPlace != null && destinationPlace != null
    val destinationPlace: Place? get() = searchedPlace?.takeIf { it.name == destination.trim() } ?: RouteCatalog.find(destination)
    val originPlace: Place?
        get() = if (usesCurrentLocation) currentPlace else searchedOrigin?.takeIf { it.name == origin.trim() } ?: RouteCatalog.find(origin)
    val enteredRoute: Route?
        get() {
            val to = destinationPlace ?: return null
            val from = originPlace ?: return null
            // 내비 경로는 현재 위치에서 출발한다. 다른 출발지면 쓰지 않는다.
            if (usesCurrentLocation) naviRoute?.let { return it.withTruckStops(truckStops) }
            return RouteCatalog.route(from, to, naviRouteKm?.takeIf { usesCurrentLocation }?.toDouble())?.withTruckStops(truckStops)
        }
    val canPlan: Boolean get() = enteredRoute != null && namesFilled
    val accepted: Boolean get() = acceptedShift != null
    val driving: Boolean get() = vehicle.gear != Gear.PARK && vehicle.gear != Gear.UNKNOWN

    val screen: AppScreen
        get() = when {
            finished && !driving -> AppScreen.SUMMARY
            arrived && !driving -> AppScreen.SHIFT
            trip != null || driving -> AppScreen.DRIVING
            settingsOpen -> AppScreen.SETTINGS
            else -> AppScreen.SETUP
        }
}

/** 차량 상태를 받아 교대 엔진을 돌리고 화면 상태를 만든다. 계산은 모두 shift 패키지에 있고 여기서는 순서만 맞춘다. */
class ShiftViewModel(application: Application) : AndroidViewModel(application), NaviEvents {
    private val planner = ShiftPlanner()
    private val tracker = TripTracker()
    /** 내비가 마지막으로 알려 준 경로의 휴게소. 목적지를 정하기 전에 올 수 있어 기억해 둔다. */
    private var naviStops: NaviCached? = null
    /**
     * 진행 중인 여행의 경로가 이미 내비 경로의 휴게소로 맞춰져 있다. 아니면(앱이 다시 켜져 내장 도로망으로 이어 갔으면)
     * 처음 맞출 때는 운전자가 길을 바꾼 것이 아니니 말하지 않는다.
     */
    private var naviSynced = false
    /** 출발 전에 마지막으로 계획에 쓴 내비 휴게소 목록. 같은 목록이 다시 오면 경로를 다시 만들지 않는다. */
    private var appliedNaviStops: NaviCached? = null
    private val voice = VoiceGuide(application)
    private val navi = NaviGateway(application, this)
    private val overlay = NaviOverlay(application)
    private var appVisible = true
    /** 앱이 내비 옆에 나란히 놓여 있다. 홈 화면이 정한 창 너비로 안다. */
    private var appBeside = false
    /** 앱이 가려진 뒤 손잡이 안내를 아직 띄우지 않았다. */
    private var handleHintPending = false
    /**
     * 운전자가 내비에서 목적지를 찍는 중이다. 그동안 내비 위에 작은 카드를 띄운다.
     * 가려진 앱은 10초쯤 뒤 시스템이 얼려 내비가 보내는 목적지를 못 받는데, 떠 있는 창이 있으면 얼리지 않는다.
     */
    private var pickingInNavi = false
    /** 내비에서 고른 곳. 앱으로 넘어가기 전 잠깐 내비 위에 알린다. */
    private var chosenInNavi: String? = null
    private val listener = VoiceListener(application) { heard -> viewModelScope.launch { onHeard(heard) } }
    private var listenJob: Job? = null

    private val themeSetting = ThemeSetting(application)
    private val profileStore = DriverProfileStore(application)
    private val climate = CabinClimate(application)
    private val tripStore = TripStore(application)
    private val notifier = ShiftNotifier(application)
    /** 같은 목적지로 제안을 되풀이하지 않는다. 내비가 경로를 다시 잡을 때도 안내 시작 신호가 온다. */
    private var suggestedPlace: String? = null
    /** 혼자 쉬기 시작한 때. 15분이 지나면 출발해도 된다고 알린다. */
    private var restStartedAt: LocalDateTime? = null
    private var restDoneSaid = false
    private val _state = MutableStateFlow(ShiftUiState(
            themeMode = themeSetting.load(), truckStops = themeSetting.loadTruckStops(), profiles = profileStore.load(), naviAvailable = navi.available,
        ))
    val state: StateFlow<ShiftUiState> = _state.asStateFlow()

    private var vehicleJob: Job? = null
    private var ticks = 0
    private var spokenStopId: String? = null
    private var dismissedStopId: String? = null
    private var lastProposalAt: LocalDateTime? = null
    /** 수락한 휴게소에 위치를 붙든 뒤에도 더 달린 거리. 쌓이면 들르지 않고 지나친 것이다. */
    private var heldOverflowKm = 0.0

    init {
        tripStore.load()?.let(::resumeTrip)
        restartVehicle()
        viewModelScope.launch {
            while (true) {
                delay(TICK_MILLIS)
                tick(TICK_MILLIS / 1000.0 * BuildConfig.DEMO_TIME_SCALE)
            }
        }
    }

    /** 속도 권한을 받은 뒤 구독을 다시 건다. */
    fun restartVehicle() {
        vehicleJob?.cancel()
        val source = VehicleDataSources.create(getApplication())
        _state.update { it.copy(sourceName = source.name) }
        vehicleJob = viewModelScope.launch {
            source.states().collect { vehicle -> _state.update { it.copy(vehicle = vehicle) } }
        }
    }

    /**
     * 앱이 꺼졌다 켜졌을 때 저장해 둔 여행을 이어 간다. 계획은 지금 위치에서 다시 짠다.
     * 경로는 달리던 그대로(저장한 것)를 쓴다. 지금까지 온 거리가 그 경로 기준이라, 내장 도로망으로 새로 찾으면 기준이 어긋난다.
     * 꺼져 있던 시간은 운전한 것으로 더한다. 빠뜨리면 연속 운전 시간이 모자라 교대 알림이 늦어진다.
     */
    private fun resumeTrip(saved: SavedTrip) {
        val route = (saved.route ?: saved.from?.let { RouteCatalog.route(it, saved.to) } ?: return tripStore.clear())
            .withTruckStops(_state.value.truckStops)
        val awayMinutes = (System.currentTimeMillis() - saved.savedAt).coerceAtLeast(0) / 60_000.0 * BuildConfig.DEMO_TIME_SCALE
        val trip = tracker.catchUp(saved.trip, awayMinutes)
        if (trip.km >= route.totalKm - ARRIVE_KM) return tripStore.clear()
        val plan = planner.replan(route, saved.drivers, trip.toProgress(), tracker.estimatedRemainingMinutes(route, trip))
        // 내비가 같은 목적지로 안내 중이면 그 경로의 휴게소를 이 여행에 맞춰 받는다.
        navi.adoptDestination(saved.to)
        naviSynced = saved.routeFromNavi
        TripService.start(getApplication())
        _state.update {
            it.copy(
                route = route, drivers = saved.drivers, plan = plan, trip = trip, step = SetupStep.REVIEW,
                destination = saved.to.name, searchedPlace = saved.to,
            )
        }
        refreshNext()
    }

    // 출발 전 설정

    fun openSettings() = _state.update { it.copy(settingsOpen = true) }

    fun closeSettings() = _state.update { it.copy(settingsOpen = false, editingProfile = null) }

    fun addProfile() = _state.update { it.copy(editingProfile = DriverProfile(UUID.randomUUID().toString())) }

    fun editProfile(profile: DriverProfile) = _state.update { it.copy(editingProfile = profile) }

    fun closeProfile() = _state.update { it.copy(editingProfile = null) }

    fun saveProfile(profile: DriverProfile) {
        _state.update { s ->
            val known = s.profiles.any { it.id == profile.id }
            s.copy(
                profiles = if (known) s.profiles.map { if (it.id == profile.id) profile else it } else s.profiles + profile,
                drivers = s.drivers.map { if (it.profileId == profile.id) profile.fill(it) else it },
                editingProfile = null,
            )
        }
        profileStore.save(_state.value.profiles)
    }

    fun deleteProfile(id: String) {
        _state.update { s ->
            s.copy(
                profiles = s.profiles.filter { it.id != id },
                drivers = s.drivers.map { if (it.profileId == id) it.copy(profileId = null) else it },
                editingProfile = null,
            )
        }
        profileStore.save(_state.value.profiles)
    }

    /** [driver]로 바뀔 때 챙길 것을 만든다. 지금 차 상태를 읽어 다른 것만 미리 체크한다. */
    fun prepareChanges(driver: Driver) {
        if (_state.value.changes?.driver?.id == driver.id) return
        _state.update { it.copy(changes = DriverChangeList(driver)) }
        viewModelScope.launch {
            val cabin = climate.read()
            _state.update { s ->
                val list = s.changes?.takeIf { it.driver.id == driver.id } ?: return@update s
                val items = DriverChanges.items(s.profileOf(driver), cabin)
                s.copy(changes = list.copy(items = items, checked = items.filter { it.kind.auto && it.needed }.map { it.kind }.toSet(), loading = false))
            }
        }
    }

    /** 차에서 바꿀 것은 고르기를, 직접 할 것은 마침 표시를 바꾼다. */
    fun toggleChange(kind: ChangeKind) = _state.update { s ->
        val list = s.changes ?: return@update s
        s.copy(
            changes = if (kind.auto) {
                list.copy(checked = if (kind in list.checked) list.checked - kind else list.checked + kind)
            } else {
                list.copy(done = list.done + (kind to !(list.done[kind] ?: false)))
            },
        )
    }

    /** 운전자가 "틀기"를 누르면 저장한 플레이리스트를 그 음악 앱으로 연다. 음악 앱이 화면 앞으로 나온다. */
    fun openPlaylist() {
        val list = _state.value.changes ?: return
        val link = list.items.firstOrNull { it.kind == ChangeKind.MUSIC }?.link ?: return
        val opened = openLink(link)
        _state.update { s ->
            val current = s.changes?.takeIf { it.driver.id == list.driver.id } ?: return@update s
            s.copy(
                changes = if (opened) {
                    current.copy(done = current.done + (ChangeKind.MUSIC to true), musicMessage = null)
                } else {
                    current.copy(musicMessage = "이 차에서 이 링크를 열 수 있는 음악 앱이 없어요")
                },
            )
        }
    }

    /** 교대 뒤 "틀까요?"에 틀겠다고 답했다. */
    fun playMusic() {
        val ask = _state.value.musicAsk ?: return
        val opened = openLink(ask.link)
        _state.update { it.copy(musicAsk = if (opened) null else ask.copy(failed = true)) }
    }

    fun skipMusic() = _state.update { it.copy(musicAsk = null) }

    private fun openLink(link: String): Boolean {
        val app = getApplication<Application>()
        return runCatching {
            app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "플레이리스트를 열지 못함", it) }.isSuccess
    }

    /** 운전자가 체크해서 승인한 항목만 차에 넣는다. */
    fun applyChanges() {
        val s = _state.value
        val list = s.changes ?: return
        val profile = s.profileOf(list.driver) ?: return
        val picked = list.checked.filter { it.auto }
        if (picked.isEmpty()) return
        _state.update { it.copy(changes = list.copy(applying = true, done = list.done - picked.toSet())) }
        // 결과는 SDK 스레드에서 올 수 있고, update는 경합하면 람다를 다시 돈다. 남은 수는 람다 밖에서 한 번만 센다.
        val left = AtomicInteger(picked.size)
        val report = { kind: ChangeKind, ok: Boolean ->
            val stillApplying = left.decrementAndGet() > 0
            _state.update { now ->
                val current = now.changes?.takeIf { it.driver.id == list.driver.id } ?: return@update now
                now.copy(changes = current.copy(done = current.done + (kind to ok), applying = stillApplying))
            }
        }
        picked.forEach { kind ->
            when (kind) {
                ChangeKind.TEMPERATURE -> profile.temperatureCelsius?.let { climate.setDriverTemperature(it) { ok -> report(kind, ok) } } ?: report(kind, false)
                ChangeKind.SEAT_HEAT -> profile.seatHeat?.let { climate.setSeatHeat(it) { ok -> report(kind, ok) } } ?: report(kind, false)
                ChangeKind.WHEEL_HEAT -> profile.wheelHeat?.let { climate.setWheelHeat(it) { ok -> report(kind, ok) } } ?: report(kind, false)
                ChangeKind.SEAT_MEMORY -> profile.seatMemory?.let { climate.selectSeatMemory(it) { ok -> report(kind, ok) } } ?: report(kind, false)
                else -> report(kind, false)
            }
        }
    }

    /** 화물차 휴게소도 쓸지. 출발 전 설정에서만 바꾼다. */
    fun setTruckStops(on: Boolean) {
        themeSetting.saveTruckStops(on)
        _state.update { it.copy(truckStops = on) }
    }

    fun setThemeMode(mode: ThemeMode) {
        themeSetting.save(mode)
        _state.update { it.copy(themeMode = mode) }
    }

    fun setOrigin(text: String) = _state.update { it.copy(origin = text, plan = null) }

    fun setDestination(text: String) {
        _state.update { it.copy(destination = text, plan = null, naviPreview = null, naviRouteKm = null, naviRoute = null) }
        _state.value.destinationPlace?.let(navi::preview)
    }

    /** 출발지와 목적지를 맞바꾼다. 현재 위치는 목적지가 될 수 없어 둘 다 정해진 곳일 때만 바꾼다. */
    fun swapPlaces() = _state.update {
        if (it.canSwap) {
            it.copy(
                origin = it.destination, destination = it.origin,
                searchedOrigin = it.destinationPlace, searchedPlace = it.originPlace, plan = null, naviPreview = null, naviRouteKm = null, naviRoute = null,
            )
        } else {
            it
        }
    }

    /** 앱 안 검색 결과에서 고른 곳을 출발지나 목적지로 넣는다. */
    fun choosePlace(asOrigin: Boolean, place: Place) {
        if (asOrigin) {
            _state.update { it.copy(origin = place.name, searchedOrigin = place, plan = null) }
        } else {
            _state.update { it.copy(destination = place.name, searchedPlace = place, plan = null, naviPreview = null, naviRouteKm = null, naviRoute = null) }
            navi.preview(place)
        }
    }

    fun renameDriver(id: String, name: String) = editDriver(id) { it.copy(name = name, profileId = null) }

    fun toggleNovice(id: String) = editDriver(id) { it.copy(novice = !it.novice) }

    fun toggleAvoidNight(id: String) = editDriver(id) { it.copy(avoidNight = !it.avoidNight) }

    /** 빈 운전자 칸을 하나 늘린다. 몇 명인지 따로 묻지 않고 칸 수가 곧 운전자 수다. */
    fun addDriver() = _state.update { s ->
        if (s.drivers.size >= MAX_DRIVERS) s else s.copy(drivers = s.drivers + Driver(nextDriverId(s.drivers), ""), plan = null)
    }

    fun removeDriver(id: String) = _state.update { s ->
        if (s.drivers.size <= 1) s else s.copy(drivers = s.drivers.filterNot { it.id == id }, plan = null)
    }

    /**
     * 등록한 운전자를 넣거나 뺀다. 넣을 때는 비어 있는 칸부터 채우고, 없으면 칸을 늘린다.
     * 혼자 남은 사람을 빼면 칸은 남기고 비운다.
     */
    fun toggleProfile(profile: DriverProfile) = _state.update { s ->
        val picked = s.drivers.firstOrNull { it.profileId == profile.id }
        val drivers = when {
            picked != null && s.drivers.size > 1 -> s.drivers - picked
            picked != null -> listOf(Driver(picked.id, ""))
            else -> {
                val empty = s.drivers.firstOrNull { it.name.isBlank() && it.profileId == null }
                when {
                    empty != null -> s.drivers.map { if (it.id == empty.id) profile.fill(it) else it }
                    s.drivers.size < MAX_DRIVERS -> s.drivers + profile.fill(Driver(nextDriverId(s.drivers), ""))
                    else -> s.drivers
                }
            }
        }
        s.copy(drivers = drivers, plan = null)
    }

    private fun nextDriverId(drivers: List<Driver>) = ((drivers.maxOfOrNull { it.id.toIntOrNull() ?: 0 } ?: 0) + 1).toString()

    /** 고른 경로를 진짜 내비 지도로 본다. 내비는 차가 있는 곳에서 출발하는 경로를 보여 준다. */
    fun showInNavi() {
        _state.value.destinationPlace?.let(navi::showOverview)
    }

    fun searchInNavi(keyword: String) {
        if (keyword.isNotBlank()) navi.searchPlace(keyword.trim())
    }

    /** 목적지를 내비 검색 화면에서 찍는다. 고르면 [onPlaceChosen]으로 받아 앱이 바로 앞에 돌아온다. */
    fun pickInNavi() {
        pickingInNavi = true
        // 내비 위 카드를 띄울 수 없으면 카드 대신 서비스로 앱이 얼지 않게 한다.
        if (!overlay.allowed) TripService.start(getApplication())
        navi.openSearch()
    }

    fun goTo(step: SetupStep) {
        if (step == SetupStep.REVIEW) {
            makePlan()
            _state.update { it.copy(changes = null) }
        }
        // 확인 화면을 떠나면 계획을 지운다. 입력을 고치는 중에 기어를 넣어도 옛 계획으로 출발하지 않는다.
        else _state.update { it.copy(plan = null) }
        _state.update { it.copy(step = step) }
    }

    /** 음성으로 묻고, 말이 끝날 즈음부터 잠깐 듣는다. 안내를 마이크가 듣지 않도록 말하는 시간만큼 기다린다. */
    private fun ask(question: String) {
        voice.speak(question)
        listenJob?.cancel()
        // 앞 질문을 듣는 중이었으면 그 듣기를 먼저 끝낸다. 인식 세션과 오디오 포커스가 겹치지 않게.
        if (_state.value.listening) stopListening()
        listenJob = viewModelScope.launch {
            delay(SPEECH_MARGIN_MILLIS + question.length * SPEECH_MILLIS_PER_CHAR)
            _state.update { it.copy(listening = true) }
            listener.listen()
            delay(LISTEN_MILLIS)
            stopListening()
        }
    }

    private fun stopListening() {
        listener.stop()
        _state.update { it.copy(listening = false) }
    }

    /** 대답을 지금 묻고 있는 일에 맞춰 처리한다. 모르는 말은 무시하고 버튼에 맡긴다. */
    private fun onHeard(text: String) {
        val s = _state.value
        if (!s.listening) return
        val reply = VoiceReplyParser.parse(text) ?: return
        listenJob?.cancel()
        stopListening()
        when {
            s.musicAsk != null && !s.musicAsk.failed -> if (reply == VoiceReply.YES) playMusic() else skipMusic()
            s.proposal != null -> if (reply == VoiceReply.YES) {
                applyProposal()
                _state.value.next?.let { voice.speak("다음 ${_state.value.wordWithObject} ${Josa.toward(it.stop.name)} 바꿨습니다.") }
            } else {
                dismissProposal()
            }
            s.next != null && !s.accepted -> when (reply) {
                VoiceReply.YES -> accept()
                VoiceReply.LATER -> postpone()
                VoiceReply.NO -> Unit
            }
        }
    }

    /** 마이크 대신 녹음을 넣어 대답을 흉내 낸다. 에뮬레이터 시험과 데모 영상에 쓴다. */
    fun feedAnswer(pcm: ByteArray) {
        listenJob?.cancel()
        stopListening()
        _state.update { it.copy(listening = true) }
        listener.feed(pcm)
        listenJob = viewModelScope.launch {
            delay(pcm.size / 32L + LISTEN_MILLIS)
            stopListening()
        }
    }

    /** 앱이 가려지면 내비 위에 작은 안내를 띄우고, 앱이 보이면 거둔다. */
    fun setAppVisible(visible: Boolean) {
        appVisible = visible
        if (!visible) handleHintPending = true
        // 고르지 않고 앱으로 돌아왔으면 찍기를 그만둔 것이다. 찍는 동안만 띄운 서비스도 내린다.
        if (visible && pickingInNavi) {
            pickingInNavi = false
            navi.cancelSearch()
            if (_state.value.trip == null) TripService.stop(getApplication())
        }
        // 앱 화면이 같은 내용을 보여 주고, 그사이 다음 휴게소가 바뀌었을 수 있어 알림을 거둔다.
        if (visible) notifier.cancelShift()
        // 내비에서 다른 경로 후보를 골랐을 수 있다. 경로 시작 신호가 오지 않는 경우에 대비해 돌아올 때마다 경로를 다시 묻는다.
        if (visible) navi.refreshLive()
        updateOverlay()
    }

    private fun updateOverlay() {
        val s = _state.value
        if (pickingInNavi && !appVisible && s.trip == null) {
            val chosen = chosenInNavi
            if (chosen != null) overlay.show("목적지 · $chosen", "교대 플래너로 넘어가요", "잠시 뒤 경로와 휴게소를 보여 드려요")
            else overlay.show("교대 플래너", "목적지를 고르면 돌아가요", "검색하거나 최근 목적지를 눌러 주세요")
            return
        }
        val next = s.next
        if (appVisible || s.trip == null || s.finished || !s.driving || next == null) {
            overlay.hide()
            return
        }
        val who = if (s.solo) "" else next.nextDriver?.let { " · ${it.name}" }.orEmpty()
        val asking = s.acceptedShift == null && spokenStopId == next.stop.id
        overlay.show("다음 ${s.word} · ${next.stop.name}", "${next.minutesLeft}분 후$who", if (asking) "수락할까요? “응” · “미뤄 줘”" else null)
        // 내비가 앱을 다 덮었으면 손잡이를 끌어 옆에 놓으라고 한 번 알린다. 내비가 앞이라 끌면 내비가 가운데, 앱이 오른쪽에 온다.
        if (handleHintPending && !appBeside) overlay.showHandleHint()
        handleHintPending = false
    }

    /** 앱 창이 화면 오른쪽 절반쯤으로 줄어 내비 옆에 놓였다. */
    fun setAppBeside(beside: Boolean) {
        appBeside = beside
        if (_state.value.besideNavi != beside) _state.update { it.copy(besideNavi = beside) }
    }

    /** 화면에 돌아올 때마다 속도 권한을 다시 본다. 설정에서 켜고 돌아오면 구독을 다시 건다. */
    fun setSpeedAllowed(allowed: Boolean) {
        val before = _state.value.speedAllowed
        if (before == allowed) return
        _state.update { it.copy(speedAllowed = allowed) }
        if (allowed) restartVehicle()
    }

    /** 앱이 화면에 돌아올 때 차가 움직였을 수 있으니 다시 묻는다. */
    fun refreshLocation() = navi.refreshLocation()

    fun makePlan() = _state.update {
        val route = it.enteredRoute
        if (route == null || !it.canPlan) it
        else it.copy(route = route, plan = planner.plan(route, it.drivers, LocalDateTime.now()))
    }.also { _state.value.destinationPlace?.let(navi::preview) }

    // 주행 중

    fun accept() {
        heldOverflowKm = 0.0
        notifier.cancelShift()
        _state.update { it.copy(acceptedShift = it.next, waypointSet = false) }
        val s = _state.value
        s.next?.let {
            navi.setWaypoint(it.stop)
            voice.speak(if (s.solo) "${it.stop.name}에서 쉬어 갑니다." else "${it.stop.name}에서 교대합니다.")
        }
    }

    fun postpone() {
        notifier.cancelShift()
        val s = _state.value
        val trip = s.trip ?: return
        val plan = s.plan ?: return
        val updated = planner.postpone(s.route, s.drivers, plan, trip.toProgress(), pace(s.route, trip))
        spokenStopId = null
        _state.update { it.copy(plan = updated, acceptedShift = null) }
        refreshNext()
        _state.value.next?.let { voice.speak("다음 ${_state.value.wordWithObject} ${Josa.toward(it.stop.name)} 미뤘습니다.") }
    }

    fun applyProposal() {
        spokenStopId = null
        _state.update { it.copy(plan = it.proposal ?: it.plan, proposal = null, acceptedShift = null) }
        refreshNext()
    }

    fun dismissProposal() {
        dismissedStopId = _state.value.next?.stop?.id
        _state.update { it.copy(proposal = null) }
    }

    // 교대 정차

    /** 수락하지 않은 곳에 섰어도 지금 자리에서 교대·휴식을 할 수 있게 한다. */
    fun shiftHere() {
        val s = _state.value
        if (s.trip == null || s.driving || s.finished) return
        listenJob?.cancel()
        stopListening()
        _state.update { it.copy(arrived = true, proposal = null) }
    }

    fun completeShift() {
        heldOverflowKm = 0.0
        restStartedAt = null
        restDoneSaid = false
        notifier.cancelShift()
        val s = _state.value
        val trip = s.trip ?: return
        val nextDriver = s.next?.nextDriver ?: trip.driver
        val shifted = tracker.shift(trip, nextDriver)
        // 경로 후보 화면이 교대 화면(체크 목록·음악 질문)을 덮는다. 내비가 안내를 다시 시작하면 앱을 돌려놓는다.
        if (navi.clearWaypoint() && appVisible) returnAfterReroute()
        val plan = planner.replan(s.route, s.drivers, shifted.toProgress(), tracker.estimatedRemainingMinutes(s.route, shifted))
        spokenStopId = null
        dismissedStopId = null
        // 새 운전자의 플레이리스트가 있으면 틀지 묻는다. 교대 화면에서 이미 틀었으면 묻지 않는다.
        val alreadyPlayed = s.changes?.takeIf { it.driver.id == nextDriver.id }?.done?.get(ChangeKind.MUSIC) == true
        val musicAsk = s.profileOf(nextDriver)?.playlistUrl?.trim()
            ?.takeIf { !s.solo && !alreadyPlayed && MusicLinks.isValid(it) }
            ?.let { MusicAsk(nextDriver.name, it, MusicLinks.label(it)) }
        _state.update {
            it.copy(
                trip = shifted, plan = plan, acceptedShift = null, arrived = false, proposal = null, waypointSet = false,
                changes = null, musicAsk = musicAsk, restedMinutes = null,
            )
        }
        tripStore.progress(shifted)
        refreshNext()
        val start = "${nextDriver.name} 님, 출발하세요. " + (_state.value.next?.let { ShiftAnnouncer.speech(it, nextDriver) }.orEmpty())
        if (musicAsk != null) ask("$start ${Josa.object_(musicAsk.label)} 틀까요?") else voice.speak(start)
    }

    fun reset() {
        TripService.stop(getApplication())
        // 내비 안내를 취소하니 그 경로의 휴게소도 더는 맞지 않는다. 다음 출발 때 내비가 새로 알려 준다.
        navi.cancelRoute()
        naviStops = null
        notifier.cancelShift()
        suggestedPlace = null
        restStartedAt = null
        restDoneSaid = false
        tripStore.clear()
        heldOverflowKm = 0.0
        spokenStopId = null
        dismissedStopId = null
        lastProposalAt = null
        // 새 여행은 목적지부터 새로 고른다. 출발지는 차가 있는 곳(현재 위치)으로 돌린다. 같이 가는 사람과 설정은 그대로 둔다.
        _state.update { ShiftUiState(
                vehicle = it.vehicle, sourceName = it.sourceName, drivers = it.drivers, profiles = it.profiles, currentPlace = it.currentPlace,
                themeMode = it.themeMode, naviAvailable = it.naviAvailable, speedAllowed = it.speedAllowed, truckStops = it.truckStops,
            ) }
        // 안내를 취소하면 내비가 앱을 덮는다. 처음 화면이 보이도록 앱을 다시 앞으로 부른다.
        viewModelScope.launch {
            delay(NAVI_CANCEL_SETTLE_MS)
            val app = getApplication<Application>()
            app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun tick(seconds: Double) {
        val s = _state.value
        val plan = s.plan ?: return
        val trip = s.trip
        if (trip == null) {
            if (s.driving) startTrip(plan)
            return
        }
        if (++ticks % NAVI_REFRESH_TICKS == 0) navi.refreshLive()
        if (s.finished) return

        val stopAt = s.acceptedShift?.stop?.km
        val integrated = tracker.advance(
            state = trip,
            speedKmh = s.vehicle.speedKmh.toDouble(),
            driving = s.driving,
            seconds = seconds,
            stopAtKm = stopAt ?: s.route.totalKm,
        )
        // 실제 주행에서는 내비의 남은 거리로 위치를 맞춘다. 경로가 내비 경로일 때만 기준이 같다.
        // 수락한 휴게소에 붙들어 둔 동안은 맞추지 않는다(들렀는지는 내비 경유지 도착 신호로 안다).
        val naviRemaining = s.naviLive?.destinationKm?.toDouble()
        val advanced = if (BuildConfig.DEMO_TIME_SCALE == 1 && naviSynced && stopAt == null && naviRemaining != null) {
            tracker.syncWithNavi(integrated, s.route.totalKm, naviRemaining)
        } else {
            integrated
        }
        // 수락한 휴게소에서는 위치를 붙들어 둔다. 붙든 뒤에도 일정 거리를 더 달렸으면 들르지 않고 지나친 것이다.
        // 데모 배속에서는 한 틱에 가는 거리도 배속만큼 늘어나므로 기준도 같이 늘린다.
        if (stopAt != null && s.driving) {
            heldOverflowKm += max(s.vehicle.speedKmh.toDouble(), 0.0) * seconds / 3600 - (advanced.km - trip.km)
            if (heldOverflowKm > PASS_KM * passScale) {
                // 붙들어 둔 동안 실제로 달린 거리를 위치와 운전자 기록 모두에 넣는다. 빠뜨리면 도착 요약의 분담 거리가 모자란다.
                val id = advanced.driver.id
                passStop(
                    advanced.copy(
                        km = advanced.km + heldOverflowKm,
                        drivenKm = advanced.drivenKm + (id to (advanced.drivenKm[id] ?: 0.0) + heldOverflowKm),
                    ),
                )
                return
            }
        }
        val finished = advanced.km >= s.route.totalKm - ARRIVE_KM
        // 직접 "여기서 교대"를 눌렀으면 정차해 있는 동안 교대 화면을 유지한다.
        val arrived = (stopAt != null && advanced.km >= stopAt - ARRIVE_KM) || (s.arrived && !s.driving)
        val overLimit = tracker.isOverLimit(advanced, s.solo)
        if (overLimit && !s.overLimit && s.driving && !appVisible) {
            notifier.info("연속 운전 한도를 넘었어요", if (s.next != null) "${s.next.stop.name}에서 ${if (s.solo) "쉬어" else "교대해"} 주세요" else "안전한 곳에서 쉬어 가세요", quiet = overlay.allowed)
        }
        if (overLimit && !s.overLimit && s.driving) voice.speak(if (s.next != null) "연속 운전 한도를 넘었습니다. 다음 휴게소에서 ${if (s.solo) "쉬어" else "교대해"} 주세요." else "연속 운전 한도를 넘었습니다. 안전한 곳에서 쉬어 가세요.")
        _state.update { it.copy(trip = advanced, finished = finished, arrived = arrived, overLimit = overLimit) }
        if (finished) {
            tripStore.clear()
            // 여행 기록이 끝났다. "새 여행"을 누르지 않아도 알림과 서비스를 내린다.
            TripService.stop(getApplication())
            voice.speak("목적지에 도착했습니다. 수고하셨습니다.")
            return
        }
        if (ticks % SAVE_TICKS == 0) tripStore.progress(advanced)
        if (arrived && !s.driving) restTick(advanced) else if (restStartedAt != null && s.driving) restStartedAt = null
        if (s.driving && !arrived) followPlan(advanced)
        refreshNext()
        announceIfNear()
        updateOverlay()
    }

    /** 혼자 쉬는 동안 쉰 시간을 세고, 15분이 되면 출발해도 된다고 알린다. */
    /**
     * 데모 배속에서 지나침 기준을 늘리는 배수. 배속만큼 늘리면(60배면 120km) 휴게소에 들어간 뒤 계속 달릴 때 위치가 크게 튄다.
     * 한 틱(1초)에 가는 거리보다는 넉넉하게, 시속 100으로 10초쯤 더 달리면 지나친 것으로 본다.
     */
    private val passScale = (BuildConfig.DEMO_TIME_SCALE / 6).coerceAtLeast(1)

    private fun restTick(trip: TripState) {
        val s = _state.value
        if (!s.solo) return
        val started = restStartedAt ?: trip.now.also { restStartedAt = it }
        val rested = java.time.Duration.between(started, trip.now).toMinutes().toInt()
        _state.update { it.copy(restedMinutes = rested) }
        if (rested >= SOLO_REST_MINUTES && !restDoneSaid) {
            restDoneSaid = true
            voice.speak("${SOLO_REST_MINUTES}분 쉬었습니다. 준비되면 출발하세요.")
            if (!appVisible) notifier.info("${SOLO_REST_MINUTES}분 쉬었어요", "준비되면 출발하세요")
        }
    }

    private fun startTrip(plan: ShiftPlan) {
        // 계획은 확인 화면에 들어간 때 짰다. 한참 뒤에 출발했을 수 있으니 지금 시각으로 다시 짜 도착·야간 계산을 맞춘다.
        val now = LocalDateTime.now()
        val first = plan.segments.first().driver
        val departing = _state.value.let { planner.plan(it.route, it.drivers, now, first) }
        val trip = TripState(now = now, driver = first)
        _state.update { it.copy(trip = trip, plan = departing, changes = null) }
        val s = _state.value
        naviSynced = s.naviRoute != null && s.usesCurrentLocation
        s.destinationPlace?.let { to -> tripStore.start(s.originPlace, to, s.drivers, trip, s.route, naviSynced) }
        TripService.start(getApplication())
        // 남아 있는 검색이 있으면 출발 안내의 목적지를 고른 곳으로 받아 계획을 지운다.
        navi.cancelSearch()
        _state.value.destinationPlace?.let(navi::startRoute)
        refreshNext()
        _state.value.next?.let { voice.speak(ShiftAnnouncer.speech(it, trip.driver)) }
    }

    /** 수락한 휴게소를 들르지 않고 지나쳤다. 붙들어 둔 위치를 풀고 그 자리에서 다시 계획한다. */
    private fun passStop(trip: TripState) {
        val s = _state.value
        val stop = s.acceptedShift?.stop ?: return
        heldOverflowKm = 0.0
        spokenStopId = null
        navi.clearWaypoint()
        _state.update {
            it.copy(
                trip = trip, acceptedShift = null, arrived = false, waypointSet = false, proposal = null,
                plan = planner.replan(s.route, s.drivers, trip.toProgress(), remainingMinutes(s, trip)),
            )
        }
        tripStore.progress(trip)
        refreshNext()
        voice.speak("${Josa.object_(stop.name)} 지나쳤습니다. 다음 ${s.word} 지점을 다시 잡았습니다.")
    }

    /** 교대 휴게소를 지나쳤으면 다시 계산하고, 정체로 늦어지면 새 계획을 제안한다. */
    private fun followPlan(trip: TripState) {
        val s = _state.value
        val plan = s.plan ?: return
        val remaining = remainingMinutes(s, trip)
        val next = s.next

        if (next != null && trip.km > next.stop.km && !s.accepted) {
            spokenStopId = null
            _state.update { it.copy(plan = planner.replan(s.route, s.drivers, trip.toProgress(), remaining), proposal = null) }
            return
        }
        // 제안을 보는 사이 그 휴게소를 지나쳤으면 제안을 거둔다.
        if (s.proposal != null && (s.proposal.shiftStops.firstOrNull()?.km ?: Double.MAX_VALUE) <= trip.km) {
            _state.update { it.copy(proposal = null) }
            return
        }
        if (s.proposal != null || s.accepted || next == null || next.stop.id == dismissedStopId) return
        // 출발이나 교대 직후의 느린 속도는 정체가 아니다. 평균 속도가 잡힐 때까지 기다린다.
        if (trip.minutesSinceShift < MIN_MINUTES_BEFORE_PROPOSAL) return
        // 막히지 않고 달리는 중이면 제안하지 않는다. 계획보다 조금 느린 정도는 다음 교대 때 다시 계획하며 맞춘다.
        if (!tracker.isCongested(trip)) return
        // 이미 한도를 넘겼으면 정체 때문이 아니다. 한도 경고가 따로 나가므로 제안하지 않는다.
        if (tracker.isOverLimit(trip, s.solo)) return
        // 정체가 심해지는 동안 제안이 연달아 뜨지 않게 간격을 둔다.
        if (lastProposalAt?.plusMinutes(PROPOSAL_GAP_MINUTES)?.isAfter(trip.now) == true) return
        if (!planner.shouldReplan(s.route, plan, trip.toProgress(), remaining, s.solo)) return

        val proposal = planner.replan(s.route, s.drivers, trip.toProgress(), remaining)
        val proposedStop = proposal.shiftStops.firstOrNull()
        if (proposedStop == null || proposedStop.id == next.stop.id) {
            _state.update { it.copy(plan = proposal) } // 교대 지점이 그대로면 시간만 고친다.
        } else {
            lastProposalAt = trip.now
            _state.update { it.copy(proposal = proposal) }
            ask("정체로 늦어지고 있습니다. ${s.word} 지점을 ${Josa.toward(proposedStop.name)} 바꿀까요?")
        }
    }

    private fun refreshNext() = _state.update { s ->
        val trip = s.trip ?: return@update s
        val plan = s.plan ?: return@update s
        val held = s.acceptedShift
        val minutesPerKm = pace(s.route, trip)
        s.copy(
            next = if (held == null) ShiftAnnouncer.next(plan, trip.toProgress(), minutesPerKm)
            else held.copy(minutesLeft = ((held.stop.km - trip.km) * minutesPerKm).roundToInt()),
        )
    }

    /** 교대 10분 전에 한 번 소리로 알린다. */
    private fun announceIfNear() {
        val s = _state.value
        val next = s.next ?: return
        val trip = s.trip ?: return
        // 이미 수락한 휴게소는 다시 묻지 않는다.
        if (s.driving && s.acceptedShift == null && next.minutesLeft <= ANNOUNCE_MINUTES && spokenStopId != next.stop.id) {
            spokenStopId = next.stop.id
            ask(ShiftAnnouncer.speech(next, trip.driver) + " 수락할까요?")
            // 내비를 보고 있으면 차 알림으로도 알린다. 알림에서 바로 수락할 수 있다.
            if (!appVisible) {
                notifier.upcoming(
                    "${next.minutesLeft}분 후 ${next.stop.name}",
                    if (s.solo) "15분 쉬어 가세요" else "${next.nextDriver?.name ?: "다음 운전자"} 님과 교대해요",
                    quiet = overlay.allowed,
                )
                updateOverlay()
            }
        }
    }

    private fun returnAfterReroute() = viewModelScope.launch {
        delay(NAVI_REROUTE_SETTLE_MS)
        val app = getApplication<Application>()
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun pace(route: Route, trip: TripState): Double {
        val remainingKm = route.totalKm - trip.km
        return if (remainingKm <= 0) route.minutesPerKm else remainingMinutes(_state.value, trip) / remainingKm
    }

    /**
     * 실제 주행에서는 내비의 남은 시간을 쓴다. 정체가 반영된 값이라 교대 판단에 가장 정확하다.
     * 데모 배속에서는 내비 차가 움직이지 않아 값이 멈춰 있으므로 속도로 어림한다.
     */
    private fun remainingMinutes(s: ShiftUiState, trip: TripState): Int {
        val fromNavi = s.naviLive?.destinationMinutes?.takeIf { BuildConfig.DEMO_TIME_SCALE == 1 && it > 0 }
        return fromNavi ?: tracker.estimatedRemainingMinutes(s.route, trip)
    }

    private fun editDriver(id: String, edit: (Driver) -> Driver) = _state.update { s ->
        s.copy(drivers = s.drivers.map { if (it.id == id) edit(it) else it }, plan = null)
    }

    /** 운전자가 내비에서 장거리 안내를 시작하면, 그 목적지로 교대 계획을 만들자고 제안한다. */
    override fun onNaviDestination(place: Place, distanceKm: Int, minutes: Int) {
        val s = _state.value
        if (s.trip != null || minutes < LONG_TRIP_MINUTES || place.name == suggestedPlace) return
        suggestedPlace = place.name
        _state.update { it.copy(naviSuggestion = NaviSuggestion(place, distanceKm, minutes)) }
        if (!appVisible) notifier.suggestPlan(place.name, distanceKm, minutes)
    }

    /** 제안을 받아들였다. 출발지·목적지는 채워 두고 누가 운전하는지부터 묻는다. */
    fun planFromNavi() {
        val suggestion = _state.value.naviSuggestion ?: return
        notifier.cancelSuggestion()
        _state.update {
            it.copy(
                origin = RouteCatalog.CURRENT_LOCATION, destination = suggestion.place.name, searchedPlace = suggestion.place,
                plan = null, settingsOpen = false, editingProfile = null, step = SetupStep.DRIVERS, naviSuggestion = null,
                naviPreview = "내비 기준 ${suggestion.distanceKm}km · ${durationText(suggestion.minutes)}",
                naviRouteKm = suggestion.distanceKm, naviRoute = null,
            )
        }
        // 내비가 이미 그 목적지로 안내 중이니 받아 둔 휴게소로 계획을 맞춘다.
        viewModelScope.launch { applyNaviStops() }
    }

    fun dismissSuggestion() {
        notifier.cancelSuggestion()
        _state.update { it.copy(naviSuggestion = null) }
    }

    /**
     * 내비 검색이 끝나면 앱으로 돌아와 이어서 입력하게 한다.
     * 말없이 화면이 바뀌면 놀라니, 내비가 앱을 덮고 있으면 고른 곳과 넘어간다는 것을 먼저 알리고 잠시 뒤에 넘어간다.
     */
    override fun onPlaceChosen(place: Place, routeKm: Int?) {
        _state.update { it.copy(destination = place.name, searchedPlace = place, plan = null, naviPreview = null, naviRouteKm = routeKm, naviRoute = null) }
        viewModelScope.launch { applyNaviStops() }
        navi.preview(place)
        viewModelScope.launch {
            if (!appVisible) {
                chosenInNavi = place.name
                updateOverlay()
                voice.speak("${Josa.toward(place.name)} 정했어요. 교대 플래너로 넘어갈게요.")
                delay(RETURN_NOTICE_MILLIS)
            }
            pickingInNavi = false
            chosenInNavi = null
            if (_state.value.trip == null) TripService.stop(getApplication())
            val app = getApplication<Application>()
            app.startActivity(
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
    }

    override fun onNaviLive(live: NaviLive) = _state.update { it.copy(naviLive = live) }

    override fun onCurrentLocation(lat: Double, lng: Double) = _state.update {
        it.copy(currentPlace = Place(RouteCatalog.CURRENT_LOCATION, lat, lng))
    }

    /** 내비가 안내 중인 경로의 휴게소가 왔다. 경로를 시작하거나 다시 탐색할 때마다, 주행 중에는 15초마다 온다. */
    override fun onNaviRouteStops(stops: NaviRouteStops, destinationName: String?, routeKm: Int?, routeMinutes: Int?) {
        naviStops = NaviCached(stops, destinationName, routeKm, routeMinutes)
        viewModelScope.launch { applyNaviStops() }
    }

    /**
     * 내비가 고른 경로의 휴게소로 계획을 맞춘다.
     * 출발 전에는 그 경로를 계획에 쓴다(현재 위치에서 출발할 때만. 내비 경로는 차가 있는 곳에서 시작한다).
     * 주행 중에는 앱 경로에 없는 휴게소가 나오면, 즉 운전자가 다른 길로 가 내비가 다시 탐색했으면 지금 자리부터 다시 계획한다.
     */
    private suspend fun applyNaviStops() {
        val cached = naviStops ?: return
        val s = _state.value
        if (cached.destinationName != null && cached.destinationName != s.destination.trim()) return
        val trip = s.trip
        if (trip == null) {
            // 목적지를 모르는 경로는 앱에서 찍는 중일 때만 받는다(곧 그 목적지로 정해진다).
            // 운전자가 내비에서 직접 시작한 경로면 지금 입력해 둔 목적지와 다를 수 있다.
            if (cached.destinationName == null && !pickingInNavi) return
            if (!s.usesCurrentLocation || s.destinationPlace == null) return
            if (cached == appliedNaviStops && s.naviRoute != null) return
            val km = (cached.routeKm ?: s.naviRouteKm)?.toDouble() ?: cached.stops.turnsKm
            val route = withContext(Dispatchers.Default) {
                RouteCatalog.fromNavi(cached.stops, km, cached.routeMinutes, s.currentPlace, s.destinationPlace)
            } ?: return
            // 계산하는 사이 목적지를 바꿨거나 출발했으면 버린다.
            if (_state.value.destination != s.destination || _state.value.trip != null) return
            appliedNaviStops = cached
            Log.i(TAG, "내비 경로 ${route.totalKm.roundToInt()}km 휴게소 ${route.restAreas.map { "${it.name}@${it.km.roundToInt()}" }}")
            _state.update {
                val minutes = cached.routeMinutes?.let { m -> " · ${durationText(m)}" }.orEmpty()
                it.copy(
                    naviRoute = route,
                    naviPreview = "내비 기준 ${route.totalKm.roundToInt()}km$minutes",
                    plan = it.plan?.let { _ -> planner.plan(route.withTruckStops(it.truckStops), it.drivers, LocalDateTime.now()) },
                    route = if (it.plan != null) route.withTruckStops(it.truckStops) else it.route,
                )
            }
            return
        }
        // 주행 중에는 목적지를 아는 경로만 받는다. 운전자가 내비에서 목적지를 바꾼 직후에는 아직 모른다.
        if (cached.destinationName == null) return
        // 화물차 휴게소를 쓰지 않으면 내비 목록의 화물차 휴게소는 견주지 않는다. 앱 경로에서 뺐으니 늘 "다른 길"로 보인다.
        val compared = if (s.truckStops) cached.stops else cached.stops.copy(stops = cached.stops.stops.filterNot { "화물차" in it.name })
        if (s.finished || s.arrived || !compared.differsFrom(s.route.restAreas.map { it.name })) return
        val remainingKm = s.naviLive?.destinationKm?.toDouble() ?: cached.stops.turnsKm
        val rebuilt = withContext(Dispatchers.Default) {
            RouteCatalog.fromNavi(cached.stops, remainingKm, s.naviLive?.destinationMinutes, s.currentPlace, s.destinationPlace, startKm = trip.km)
        } ?: return
        // 계산하는 사이 여행이 끝났거나 다른 경로로 이미 바꿨으면 버린다.
        val current = _state.value.trip ?: return
        if (_state.value.route != s.route) return
        // 수락해 둔 휴게소가 새 경로에도 있으면 그대로 붙든다. 경유지로 넣어 내비가 다시 탐색한 경우가 그렇다.
        val (route, keptArea) = NaviReroute.keepAccepted(rebuilt.withTruckStops(s.truckStops), s.acceptedShift?.stop, s.waypointSet)
        Log.i(TAG, "내비 경로가 바뀜. 휴게소 ${route.restAreas.map { it.name }}")
        heldOverflowKm = 0.0
        spokenStopId = null
        dismissedStopId = null
        val keptStop = keptArea?.let { area -> s.acceptedShift?.copy(stop = area) }
        val plan = planner.replan(route, s.drivers, current.toProgress(), tracker.estimatedRemainingMinutes(route, current))
        _state.update {
            it.copy(route = route, plan = plan, acceptedShift = keptStop, waypointSet = keptStop != null && it.waypointSet, proposal = null)
        }
        tripStore.route(route)
        refreshNext()
        val announce = naviSynced && keptStop == null
        naviSynced = true
        updateOverlay()
        if (!announce) return
        voice.speak("내비 경로가 바뀌어 이 길의 휴게소로 ${s.word} 계획을 다시 짰어요.")
    }

    /** 현재 위치에서 출발하는 계획이면 어림값 대신 내비의 소요 시간으로 다시 나눈다. 정체도 여기서 반영된다. */
    override fun onTripPreview(distanceKm: Int, minutes: Int) = _state.update {
        // 운전자가 경로 후보에서 고른 거리가 이미 있으면 그것을 믿는다. 미리보기는 내비 추천 경로의 것이다.
        // 내비가 안내하는 경로의 휴게소를 이미 받았으면 그 경로가 정답이다. 미리보기는 내비 추천 경로의 것이라 덮지 않는다.
        if (it.naviRoute != null) return@update it
        val preview = it.copy(naviPreview = "내비 기준 ${distanceKm}km · ${durationText(minutes)}", naviRouteKm = it.naviRouteKm ?: distanceKm)
        if (it.plan == null || it.trip != null || !it.usesCurrentLocation || minutes <= 0) {
            preview
        } else {
            val route = (preview.enteredRoute ?: it.route).copy(etaMinutes = minutes)
            preview.copy(route = route, plan = planner.plan(route, it.drivers, LocalDateTime.now()))
        }
    }

    override fun onWaypointSet(name: String, minutes: Int) = _state.update { it.copy(waypointSet = true) }

    /** 실제 차량에서는 내비가 도착을 알려 준다. 에뮬레이터에서는 속도 적분으로 도착을 판단한다. */
    /** 내비가 목적지 도착을 알렸다. 속도로 센 거리가 모자라도 도착으로 처리한다(다음 틱에서 요약으로 넘어간다). */
    override fun onDestinationArrived() = _state.update { s ->
        val trip = s.trip ?: return@update s
        if (s.finished) s else s.copy(trip = trip.copy(km = s.route.totalKm))
    }

    override fun onWaypointArrived() = _state.update { s ->
        val stop = s.acceptedShift?.stop ?: return@update s
        s.copy(arrived = true, trip = s.trip?.copy(km = stop.km))
    }

    override fun onNaviError(code: String) = voice.speak("내비 연결에 문제가 있습니다.")

    override fun onCleared() {
        climate.release()
        overlay.hide()
        listener.release()
        navi.release()
        voice.release()
    }

    private companion object {
        const val TAG = "ShiftViewModel"
        const val TICK_MILLIS = 1000L
        const val ARRIVE_KM = 0.01
        const val ANNOUNCE_MINUTES = 10
        /** 실제 시간으로 약 15초마다 내비 정보를 다시 묻는다. */
        const val NAVI_REFRESH_TICKS = 15
        const val SAVE_TICKS = 10
        /** 이보다 긴 안내를 내비에서 시작하면 교대 계획을 제안한다. */
        const val LONG_TRIP_MINUTES = 90
        const val SOLO_REST_MINUTES = 15
        /** 수락한 휴게소에서 이만큼 더 달리면 지나친 것으로 본다. 휴게소 안을 도는 거리보다 길게 잡는다. */
        const val PASS_KM = 2.0
        const val NAVI_CANCEL_SETTLE_MS = 800L
        /** 경로 후보 화면의 자동 시작(5초)이 끝날 때까지 기다린다. 그 전에 앱을 띄우면 후보 화면이 남는다. */
        const val NAVI_REROUTE_SETTLE_MS = 6_500L
        /** 내비에서 고른 뒤 앱으로 넘어가기 전에 알리는 시간. 안내 말이 끝날 즈음이다. */
        const val RETURN_NOTICE_MILLIS = 3_500L
        const val LISTEN_MILLIS = 6000L
        const val SPEECH_MARGIN_MILLIS = 800L
        const val SPEECH_MILLIS_PER_CHAR = 170L
        const val PROPOSAL_GAP_MINUTES = 15L
        const val MIN_MINUTES_BEFORE_PROPOSAL = 10
    }
}

/** 내비가 알려 준 경로의 휴게소와, 그 경로의 목적지·거리·시간(경로를 막 시작했을 때만 안다). */
private data class NaviCached(val stops: NaviRouteStops, val destinationName: String?, val routeKm: Int?, val routeMinutes: Int?)
