package com.pleoscontest.app.shift

import kotlin.math.roundToInt

/**
 * 출발지·목적지로 경로를 만든다. 소요 시간은 시속 80km로 어림하고, 실제 시간은 내비 미리보기로 보여 준다.
 * 입력 칸을 고칠 때마다 불리므로 찾은 경로를 기억해 둔다.
 */
object RouteCatalog {
    /** 출발지 칸에 넣는 값. 내비가 알려 준 차의 위치를 뜻한다. */
    const val CURRENT_LOCATION = "현재 위치"
    private const val PLANNED_MINUTES_PER_KM = 0.75
    /** 졸음쉼터는 내장 자료에 없다. 무엇이 없는지 알려 기대하지 않게 한다. */
    private val DROWSY_FACILITIES = listOf("졸음쉼터", "식당·주유소 없음")

    private val network: ExpresswayNetwork by lazy {
        val stream = checkNotNull(RouteCatalog::class.java.getResourceAsStream("/expressway.tsv")) { "고속도로망 파일이 없다" }
        stream.use(ExpresswayNetwork::load)
    }
    private val cache = HashMap<Triple<Place, Place, Int?>, Route?>()

    val places: List<Place> get() = network.places
    val mapRoads: List<List<GeoPoint>> get() = network.roads
    val mapCoast: List<List<GeoPoint>> get() = network.coast

    fun find(name: String): Place? = places.firstOrNull { it.name == name.trim() }

    /** 입력한 글자가 들어간 장소들. 비어 있으면 전부. */
    fun matching(text: String): List<Place> = places.filter { it.name.contains(text.trim()) }

    /** 목록에 없는 장소이거나 출발지와 목적지가 같으면 null. */
    fun route(origin: String, destination: String): Route? {
        val from = find(origin) ?: return null
        val to = find(destination) ?: return null
        return route(from, to)
    }

    /**
     * 내비가 안내하는 경로로 [Route]를 만든다. 휴게소 순서와 거리는 내비의 것이고, 시설은 내장 자료에서 이름으로 찾는다.
     * 경로 선은 그 휴게소들을 차례로 지나도록 내장 도로망에서 잇고, 휴게소 점은 모두 그 선 위에 놓는다.
     * 도로망 계산이 있어 화면 스레드 밖에서 부른다.
     * 주행 중에 다시 만들 때는 [startKm](지금까지 온 거리)부터 이어 세어, 이미 쌓인 운전 기록과 맞춘다.
     */
    fun fromNavi(navi: NaviRouteStops, remainingKm: Double, remainingMinutes: Int?, from: Place?, to: Place?, startKm: Double = 0.0): Route? {
        val km = maxOf(remainingKm, navi.turnsKm)
        if (km <= 0) return null
        // 휴게소를 차례로 지나는 선을 도로망에서 만들고, 휴게소 점을 그 선 위에 놓는다. 선과 점이 어긋나지 않는다.
        val threaded = network.thread(from, to, navi.stops.map { it.name })
        val line = threaded?.line.orEmpty()
        val lineKm = line.zipWithNext { a, b -> roughKm(a, b) }.sum()
        // 내비 거리(km) → 선 위 거리. 이름을 찾은 휴게소를 기준점으로 삼고 그 사이(졸음쉼터 등)는 비례로 나눈다.
        val anchors = buildList {
            if (from != null) add(0.0 to 0.0)
            navi.stops.forEachIndexed { index, stop -> threaded?.stopKm?.get(index)?.let { add(stop.km to it) } }
            if (to != null) add(km to lineKm)
        }.sortedBy { it.first }
        val stops = navi.stops.map { stop ->
            // 같은 이름이 양방향에 있다. 시설은 어느 쪽이든 같아 첫째 것을 쓴다.
            val known = network.restAreasNamed(stop.name).firstOrNull()
            val at = if (line.size >= 2 && anchors.isNotEmpty()) {
                ExpresswayNetwork.pointAt(line, lineKmFor(stop.km, anchors))
            } else {
                known?.let { GeoPoint(it.lat, it.lng) }
            }
            RestArea(
                known?.id ?: "navi-${NaviRouteStops.key(stop.name)}", stop.name, startKm + stop.km,
                at?.lat ?: 0.0, at?.lng ?: 0.0,
                known?.facilities ?: if ("졸음쉼터" in stop.name) DROWSY_FACILITIES else emptyList(),
                truck = known?.truck ?: ("화물차" in stop.name),
            )
        }
        val path = line.ifEmpty {
            listOfNotNull(from?.let { GeoPoint(it.lat, it.lng) }) + stops.filter { it.lat != 0.0 }.map { GeoPoint(it.lat, it.lng) } +
                listOfNotNull(to?.let { GeoPoint(it.lat, it.lng) })
        }
        val minutesPerKm = remainingMinutes?.takeIf { it > 0 }?.let { it / km } ?: PLANNED_MINUTES_PER_KM
        val total = startKm + km
        return Route(total, maxOf(1, Math.round(total * minutesPerKm).toInt()), stops, path)
    }

    /** 내비 거리 [naviKm]를 기준점들([anchors]: 내비 거리 → 선 위 거리) 사이에서 비례로 선 위 거리로 바꾼다. */
    private fun lineKmFor(naviKm: Double, anchors: List<Pair<Double, Double>>): Double {
        val before = anchors.lastOrNull { it.first <= naviKm }
        val after = anchors.firstOrNull { it.first >= naviKm }
        return when {
            before != null && after != null && after.first > before.first ->
                before.second + (naviKm - before.first) / (after.first - before.first) * (after.second - before.second)
            before != null -> before.second + (naviKm - before.first)
            after != null -> maxOf(0.0, after.second - (after.first - naviKm))
            else -> naviKm
        }
    }

    private fun roughKm(a: GeoPoint, b: GeoPoint): Double {
        val dy = (a.lat - b.lat) * 111.32
        val dx = (a.lng - b.lng) * 111.32 * kotlin.math.cos(Math.toRadians(a.lat))
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** 갈 만한 길 몇 개. 첫째가 가장 짧은 길이다. */
    fun alternatives(from: Place, to: Place): List<ExpresswayNetwork.Candidate> = network.alternatives(from, to, PLANNED_MINUTES_PER_KM)

    /**
     * 출발지가 목록에 없는 지점(현재 위치)일 때도 쓴다.
     * [naviKm]은 내비가 고른 경로의 거리다. 있으면 그 거리에 맞는 길을 골라, 계획한 휴게소가 실제로 가는 길 위에 오게 한다.
     */
    fun route(from: Place, to: Place, naviKm: Double? = null): Route? {
        if (from == to) return null
        return synchronized(cache) {
            cache.getOrPut(Triple(from, to, naviKm?.roundToInt())) { network.route(from, to, PLANNED_MINUTES_PER_KM, naviKm) }
        }
    }
}
