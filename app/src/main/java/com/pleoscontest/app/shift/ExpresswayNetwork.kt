package com.pleoscontest.app.shift

import java.io.InputStream
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/** 고를 수 있는 도시. 좌표는 내비 목적지로 넘기고, 가장 가까운 고속도로 지점을 찾는 데도 쓴다. */
data class Place(val name: String, val lat: Double, val lng: Double)

/**
 * 전국 고속도로망. tools/gen_expressway.py가 만든 파일을 읽어 경로를 찾는다.
 * 내비 SDK는 경로의 도로 선을 주지 않아서, 어떤 휴게소를 지나는지 알려면 앱이 직접 경로를 찾아야 한다.
 * 구간마다 방향이 있고 휴게소는 자기 차로 구간에 붙어 있으므로, 경로 위 휴게소는 저절로 진행 방향의 것만 나온다.
 */
class ExpresswayNetwork private constructor(
    val places: List<Place>,
    private val lats: DoubleArray,
    private val lngs: DoubleArray,
    private val edges: List<Edge>,
    private val outgoing: Array<MutableList<Int>>,
    private val stopsByEdge: Map<Int, List<EdgeStop>>,
    private val shapes: Map<Int, List<GeoPoint>>,
    /** 지도 미리보기의 해안선. */
    val coast: List<List<GeoPoint>>,
) {
    /** 지도 미리보기의 고속도로. 램프는 뺀다. */
    val roads: List<List<GeoPoint>> by lazy {
        edges.indices.filter { !edges[it].ramp }.mapNotNull { shapes[it] }
    }

    private class Edge(val from: Int, val to: Int, val meters: Double, val ramp: Boolean)
    private class EdgeStop(val atMeters: Double, val area: RestArea)

    /** 이름이 같은 휴게소. 양방향에 하나씩 있는 경우가 많다. */
    fun restAreasNamed(name: String): List<RestArea> {
        val key = NaviRouteStops.key(name)
        return stopsByEdge.values.flatten().map { it.area }.filter { NaviRouteStops.key(it.name) == key }
    }

    /** 휴게소 이름(비교용 [NaviRouteStops.key]) → 그 휴게소가 붙은 (구간, 구간 시작에서의 거리 m). */
    private val stopsByKey: Map<String, List<Pair<Int, Double>>> by lazy {
        stopsByEdge.flatMap { (edge, list) -> list.map { NaviRouteStops.key(it.area.name) to (edge to it.atMeters) } }
            .groupBy({ it.first }, { it.second })
    }

    /** [names]의 휴게소를 차례로 지나는 길의 선과, 이름을 찾은 휴게소([names]의 순번)가 선 위 몇 km에 있는지. */
    class Threaded(val line: List<GeoPoint>, val stopKm: Map<Int, Double>)

    /**
     * 내비가 알려 준 휴게소를 차례로 지나는 고속도로 선을 만든다. 내비 SDK는 경로 모양을 주지 않지만, 휴게소 순서를 알면
     * 그 사이를 도로망의 가장 짧은 길로 이어 실제 경로와 거의 같은 선을 얻는다. 지도에서 선과 휴게소 점이 어긋나지 않게 한다.
     * 같은 이름이 양방향에 있으면 앞 지점에서 이어지는 쪽(반대 차로는 한참 돌아야 한다)을 고른다. 내장 자료에 없는 이름은 건너뛴다.
     */
    fun thread(from: Place?, to: Place?, names: List<String>): Threaded? {
        val line = ArrayList<GeoPoint>()
        var lineKm = 0.0
        val stopKm = HashMap<Int, Double>()
        fun append(edgeIndex: Int): Double {
            val start = lineKm
            for (p in shapes[edgeIndex].orEmpty()) {
                line.lastOrNull()?.let { lineKm += segmentKm(it, p) }
                line += p
            }
            return start
        }
        var current = from?.let(::nearestVertex)
        var lastEdge = -1
        var lastEdgeStart = 0.0
        names.forEachIndexed { index, name ->
            val candidates = stopsByKey[NaviRouteStops.key(name)] ?: return@forEachIndexed
            candidates.firstOrNull { it.first == lastEdge }?.let { (edge, at) ->
                stopKm[index] = lastEdgeStart + shapeKm(edge) * (at / edges[edge].meters)
                return@forEachIndexed
            }
            val here = current
            val (edge, at, path) = if (here == null) {
                Triple(candidates.first().first, candidates.first().second, emptyList())
            } else {
                candidates.mapNotNull { (edge, at) -> shortestPath(here, edges[edge].from)?.let { Triple(edge, at, it) } }
                    .minByOrNull { (_, _, path) -> path.sumOf { edges[it].meters } } ?: return@forEachIndexed
            }
            path.forEach { append(it) }
            lastEdgeStart = append(edge)
            lastEdge = edge
            stopKm[index] = lastEdgeStart + shapeKm(edge) * (at / edges[edge].meters)
            current = edges[edge].to
        }
        val here = current
        if (to != null && here != null) shortestPath(here, nearestVertex(to))?.forEach { append(it) }
        return if (line.size < 2) null else Threaded(line, stopKm)
    }

    private fun shapeKm(edgeIndex: Int): Double = shapes[edgeIndex].orEmpty().zipWithNext { a, b -> segmentKm(a, b) }.sum()

    private fun segmentKm(a: GeoPoint, b: GeoPoint): Double {
        val dy = (a.lat - b.lat) * KM_PER_DEGREE
        val dx = (a.lng - b.lng) * KM_PER_DEGREE * cos(Math.toRadians(a.lat))
        return sqrt(dx * dx + dy * dy)
    }

    /** 앱이 찾은 길 하나와, 고속도로까지 오가는 일반도로를 더해 어림한 전체 운전 거리. 내비 거리와 견줄 때 쓴다. */
    data class Candidate(val route: Route, val drivingKm: Double)

    /**
     * 출발지에서 목적지까지 가는 길과 그 위의 휴게소. 길이 없으면 null.
     * [naviKm]이 없으면 가장 짧은 길이다. 있으면 내비가 고른 경로의 거리로, 갈 만한 길 몇 개 중 그 거리에 가장 가까운 길을 고른다.
     * 내비 SDK는 경로 모양을 주지 않아 거리가 유일한 단서다. 그래서 가장 짧은 길보다 확실히 더 가까울 때만 바꾼다.
     */
    fun route(from: Place, to: Place, minutesPerKm: Double, naviKm: Double? = null): Route? {
        if (naviKm == null) return shortestPath(nearestVertex(from), nearestVertex(to))?.let { toRoute(it, minutesPerKm) }
        val candidates = alternatives(from, to, minutesPerKm)
        val shortest = candidates.firstOrNull() ?: return null
        val closest = candidates.minBy { abs(it.drivingKm - naviKm) }
        val gain = abs(shortest.drivingKm - naviKm) - abs(closest.drivingKm - naviKm)
        return if (gain > MATCH_MARGIN_KM) closest.route else shortest.route
    }

    /**
     * 갈 만한 길 몇 개. 첫째가 가장 짧은 길이다.
     * 찾은 길의 구간을 조금씩 불리하게 만들고 다시 찾는 방식이라, 대부분 겹치는 길은 하나로 친다.
     */
    fun alternatives(from: Place, to: Place, minutesPerKm: Double, count: Int = ALTERNATIVES): List<Candidate> {
        val source = nearestVertex(from)
        val target = nearestVertex(to)
        val approachKm = (crowKm(from, source) + crowKm(to, target)) * APPROACH_DETOUR
        val penalty = HashMap<Int, Double>()
        val found = mutableListOf<List<Int>>()
        repeat(count * 2) {
            if (found.size >= count) return@repeat
            val path = shortestPath(source, target, penalty) ?: return@repeat
            path.forEach { penalty[it] = (penalty[it] ?: 1.0) * ALTERNATIVE_PENALTY }
            if (found.none { sharedShare(it, path) > SAME_ROUTE_SHARE }) found += path
        }
        return found.mapNotNull { path -> toRoute(path, minutesPerKm)?.let { Candidate(it, it.totalKm + approachKm) } }
    }

    /** [path]의 길이 중 [other]와 겹치는 비율. */
    private fun sharedShare(other: List<Int>, path: List<Int>): Double {
        val shared = other.toSet()
        val total = path.sumOf { edges[it].meters }
        return if (total <= 0) 1.0 else path.filter { it in shared }.sumOf { edges[it].meters } / total
    }

    private fun crowKm(place: Place, vertex: Int): Double {
        val dy = (lats[vertex] - place.lat) * KM_PER_DEGREE
        val dx = (lngs[vertex] - place.lng) * KM_PER_DEGREE * cos(Math.toRadians(place.lat))
        return sqrt(dx * dx + dy * dy)
    }

    private fun toRoute(path: List<Int>, minutesPerKm: Double): Route? {
        var meters = 0.0
        val stops = mutableListOf<RestArea>()
        for (edgeIndex in path) {
            stopsByEdge[edgeIndex]?.forEach { stops += it.area.copy(km = (meters + it.atMeters) / 1000) }
            meters += edges[edgeIndex].meters
        }
        if (meters <= 0) return null
        val km = meters / 1000
        val line = path.flatMap { shapes[it].orEmpty() }
        return Route(km, Math.round(km * minutesPerKm).toInt(), stops.sortedBy { it.km }, line)
    }

    private fun nearestVertex(place: Place): Int {
        val scale = cos(Math.toRadians(place.lat))
        return lats.indices.minBy { i ->
            val dy = lats[i] - place.lat
            val dx = (lngs[i] - place.lng) * scale
            dx * dx + dy * dy
        }
    }

    private fun shortestPath(source: Int, target: Int, penalty: Map<Int, Double> = emptyMap()): List<Int>? {
        val cost = DoubleArray(lats.size) { Double.MAX_VALUE }
        val cameBy = IntArray(lats.size) { -1 }
        val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        cost[source] = 0.0
        queue += 0.0 to source
        while (queue.isNotEmpty()) {
            val (spent, vertex) = queue.poll()!!
            if (vertex == target) break
            if (spent > cost[vertex]) continue
            for (edgeIndex in outgoing[vertex]) {
                val edge = edges[edgeIndex]
                val next = spent + edge.meters * (if (edge.ramp) RAMP_PENALTY else 1.0) * (penalty[edgeIndex] ?: 1.0)
                if (next < cost[edge.to]) {
                    cost[edge.to] = next
                    cameBy[edge.to] = edgeIndex
                    queue += next to edge.to
                }
            }
        }
        if (source != target && cameBy[target] < 0) return null
        val path = ArrayDeque<Int>()
        var vertex = target
        while (vertex != source) {
            val edgeIndex = cameBy[vertex]
            path.addFirst(edgeIndex)
            vertex = edges[edgeIndex].from
        }
        return path
    }

    companion object {
        /** 분기점을 돌아 나가는 길은 본선보다 불리하게 친다. 시내를 가로지르듯 램프를 갈아타는 경로를 막는다. */
        private const val RAMP_PENALTY = 1.3
        /** 다른 길을 찾을 때 이미 찾은 길의 구간에 곱하는 값. */
        private const val ALTERNATIVE_PENALTY = 1.4
        private const val ALTERNATIVES = 4
        /** 이만큼 넘게 겹치면 같은 길로 본다. */
        private const val SAME_ROUTE_SHARE = 0.8
        /** 고속도로까지 오가는 일반도로는 곧은 거리보다 이만큼 길다고 어림한다. */
        private const val APPROACH_DETOUR = 1.3
        /** 선 위의 [km] 지점. 선 밖이면 끝점. */
        fun pointAt(line: List<GeoPoint>, km: Double): GeoPoint {
            var left = km
            for (i in 0 until line.lastIndex) {
                val a = line[i]
                val b = line[i + 1]
                val dy = (b.lat - a.lat) * KM_PER_DEGREE
                val dx = (b.lng - a.lng) * KM_PER_DEGREE * cos(Math.toRadians(a.lat))
                val length = sqrt(dx * dx + dy * dy)
                if (left <= length && length > 0) {
                    val t = left / length
                    return GeoPoint(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
                }
                left -= length
            }
            return if (km <= 0) line.first() else line.last()
        }

        /** 가장 짧은 길보다 내비 거리에 이만큼 넘게 더 가까워야 다른 길로 바꾼다. */
        private const val MATCH_MARGIN_KM = 5.0
        private const val KM_PER_DEGREE = 111.32

        fun load(input: InputStream): ExpresswayNetwork {
            val lats = ArrayList<Double>()
            val lngs = ArrayList<Double>()
            val edges = ArrayList<Edge>()
            val places = ArrayList<Place>()
            val stops = HashMap<Int, MutableList<EdgeStop>>()
            val shapes = HashMap<Int, List<GeoPoint>>()
            val coast = ArrayList<List<GeoPoint>>()
            input.bufferedReader().forEachLine { line ->
                val f = line.split('\t')
                when (f[0]) {
                    "V" -> { lats += f[1].toDouble(); lngs += f[2].toDouble() }
                    "E" -> edges += Edge(f[1].toInt(), f[2].toInt(), f[3].toDouble(), f[4] == "L")
                    "R" -> {
                        val facilities = f.getOrNull(6).orEmpty().split(',').filter { it.isNotEmpty() }
                        val area = RestArea("ra-${f[1]}-${f[2]}", f[3], 0.0, f[4].toDouble(), f[5].toDouble(), facilities, truck = f.getOrNull(7) == "T")
                        stops.getOrPut(f[1].toInt()) { mutableListOf() } += EdgeStop(f[2].toDouble(), area)
                    }
                    "P" -> places += Place(f[1], f[2].toDouble(), f[3].toDouble())
                    "G" -> shapes[f[1].toInt()] = parsePoints(f[2])
                    "C" -> coast += parsePoints(f[1])
                }
            }
            val outgoing = Array(lats.size) { mutableListOf<Int>() }
            edges.forEachIndexed { index, edge -> outgoing[edge.from] += index }
            return ExpresswayNetwork(places, lats.toDoubleArray(), lngs.toDoubleArray(), edges, outgoing, stops, shapes, coast)
        }

        private fun parsePoints(text: String): List<GeoPoint> = text.split(' ').map {
            val (lat, lng) = it.split(',')
            GeoPoint(lat.toDouble(), lng.toDouble())
        }
    }
}
