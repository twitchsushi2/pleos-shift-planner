package com.pleoscontest.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pleoscontest.app.R
import com.pleoscontest.app.shift.GeoPoint
import com.pleoscontest.app.shift.Place
import com.pleoscontest.app.shift.Route
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.cos
import kotlin.math.hypot

val START_COLOR = Color(0xFF2E7DF6)
val END_COLOR = Color(0xFFE5484D)

private val KOREA = listOf(GeoPoint(33.1, 125.8), GeoPoint(38.6, 129.6))

/**
 * 경로 미리보기. OpenFreeMap의 벡터 지도(OpenStreetMap 자료, 키·가입 없이 무료)를 MapLibre로 그린다.
 * 내비처럼 두 손가락으로 확대·축소하고 끌어서 옮길 수 있고, 확대하면 골목과 건물 이름까지 보인다.
 * 위아래 카드에 가리지 않도록 [topInset]·[bottomInset]을 뺀 곳에 경로를 맞춘다. 인터넷이 없으면 지도는 비고 경로 선만 남는다.
 *
 * 경로 선은 OSRM에서 받은 실제 도로 모양으로 그린다([RoadRoutes]). 받기 전이나 받지 못했을 때는 내장 고속도로망 선을 쓴다.
 * 내장 도로망은 고속도로뿐이라, 고속도로를 타지 않는 가까운 곳은 경로가 맞지 않는다.
 * 그럴 때는 틀린 길을 보여 주지 않고 출발지와 목적지를 점선으로만 잇는다.
 */
@Composable
fun RouteMap(route: Route?, start: Place?, end: Place?, modifier: Modifier = Modifier, topInset: Dp = 0.dp, bottomInset: Dp = 0.dp) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val routeColor = MaterialTheme.colorScheme.primary.toArgb()
    val startPoint = start?.let { GeoPoint(it.lat, it.lng) }
    val endPoint = end?.let { GeoPoint(it.lat, it.lng) }
    val highwayLine = route?.path?.takeIf { fitsRoads(it, startPoint, endPoint, route.totalKm) }
    val stops = if (highwayLine != null) route.restAreas else emptyList()
    var roads by remember { mutableStateOf<List<GeoPoint>?>(null) }
    LaunchedEffect(startPoint, endPoint, stops) {
        roads = null
        // 고속도로 경로는 앱 도로망의 선을 그대로 쓴다. 휴게소 점이 그 선 위에 있어 어긋나지 않는다.
        // 외부 경로(OSRM)는 휴게소를 들르는 길이 길면 직행 선으로 바꿔 점과 따로 놀았다. 고속도로를 타지 않는 가까운 길만 받는다.
        if (startPoint != null && endPoint != null && highwayLine == null) {
            // 졸음쉼터 좌표는 앞뒤 휴게소로 어림한 것이라 경로를 그 점에 맞춰 꺾지 않는다. 점만 찍는다.
            val via = stops.filter { !it.drowsy && it.lat != 0.0 }.map { GeoPoint(it.lat, it.lng) }
            roads = RoadRoutes.fetch(startPoint, endPoint, via, expectedKm = null)
        }
    }
    val roadLine = roads ?: highwayLine

    val mapView = remember {
        MapLibre.getInstance(context)
        // 화면 전환 애니메이션(밀기·흐려지기)을 지도도 따라가도록 TextureView로 그린다.
        MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true)).apply { onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    // 손으로 옮기거나 확대했으면 도로 경로가 늦게 와도 화면을 되돌리지 않는다.
    val userMoved = remember { mutableStateOf(false) }
    BindLifecycle(mapView)

    val padding = with(density) {
        intArrayOf(32.dp.roundToPx(), (topInset + 24.dp).roundToPx(), 112.dp.roundToPx(), (bottomInset + 24.dp).roundToPx())
    }
    val points = (roadLine.orEmpty() + listOfNotNull(startPoint, endPoint)).ifEmpty { KOREA }
    val fit = { map?.let { fitCamera(it, points, padding) } }

    LaunchedEffect(dark) {
        style = null
        mapView.getMapAsync { m ->
            if (map == null) {
                m.addOnCameraMoveStartedListener { reason ->
                    if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) userMoved.value = true
                }
            }
            map = m
            m.uiSettings.apply {
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                isLogoEnabled = false
                isCompassEnabled = false
                // 출처는 ⓘ 버튼 대신 오른쪽 아래 글자로 직접 적는다.
                isAttributionEnabled = false
            }
            m.setStyle(Style.Builder().fromUri(if (dark) DARK_STYLE else LIGHT_STYLE)) { loaded ->
                useKoreanLabels(loaded)
                addRouteLayers(loaded, routeColor)
                style = loaded
            }
        }
    }
    LaunchedEffect(startPoint, endPoint) { userMoved.value = false }
    LaunchedEffect(style, roadLine, startPoint, endPoint) {
        val loaded = style ?: return@LaunchedEffect
        showRoute(loaded, roadLine, startPoint, endPoint, stops.filter { it.lat != 0.0 }.map { it.name to GeoPoint(it.lat, it.lng) })
        if (!userMoved.value) fit()
    }

    Box(modifier) {
        AndroidView({ mapView }, Modifier.fillMaxSize())
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 20.dp, top = topInset, bottom = bottomInset),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircleButton(R.drawable.ic_add, "확대", {
                userMoved.value = true
                map?.animateCamera(CameraUpdateFactory.zoomIn())
            }, size = 56.dp)
            CircleButton(R.drawable.ic_remove, "축소", {
                userMoved.value = true
                map?.animateCamera(CameraUpdateFactory.zoomOut())
            }, size = 56.dp)
            CircleButton(R.drawable.ic_fit, "경로 전체 보기", {
                userMoved.value = false
                fit()
            }, size = 56.dp)
        }
        Text(
            // 외부 경로 서버(OSRM)는 고속도로를 타지 않는 가까운 길에만 쓴다. 쓴 때만 출처를 적는다.
            if (roads != null) "$ATTRIBUTION · 경로 OSRM" else ATTRIBUTION,
            // 아래 카드와 화면 끝 사이 틈에 둔다.
            Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 3.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** MapView는 화면 수명에 맞춰 시작·정지·해제해야 한다. */
@Composable
private fun BindLifecycle(mapView: MapView) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }
}

/** 지명은 한글 이름을 먼저 쓴다. 기본 스타일은 로마자와 한글을 함께 적는다. */
private fun useKoreanLabels(style: Style) {
    val korean = Expression.coalesce(Expression.get("name:ko"), Expression.get("name:nonlatin"), Expression.get("name"))
    style.layers.filterIsInstance<SymbolLayer>()
        .filter { "shield" !in it.id }
        .forEach { it.setProperties(PropertyFactory.textField(korean)) }
}

private fun addRouteLayers(style: Style, routeColor: Int) {
    listOf(ROUTE, CONNECTOR, STOPS, START, END).forEach { style.addSource(GeoJsonSource(it)) }
    val round = arrayOf(PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND))
    style.addLayer(
        LineLayer("$CONNECTOR-line", CONNECTOR).withProperties(
            PropertyFactory.lineColor(routeColor), PropertyFactory.lineWidth(3f), PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
        ),
    )
    style.addLayer(LineLayer("$ROUTE-casing", ROUTE).withProperties(PropertyFactory.lineColor(android.graphics.Color.WHITE), PropertyFactory.lineWidth(12f), *round))
    style.addLayer(LineLayer("$ROUTE-line", ROUTE).withProperties(PropertyFactory.lineColor(routeColor), PropertyFactory.lineWidth(8f), *round))
    style.addLayer(
        CircleLayer("$STOPS-dot", STOPS).withProperties(
            PropertyFactory.circleRadius(6f), PropertyFactory.circleColor(android.graphics.Color.WHITE),
            PropertyFactory.circleStrokeColor(routeColor), PropertyFactory.circleStrokeWidth(2.5f),
        ),
    )
    // 휴게소 이름은 어느 정도 확대했을 때만 보인다.
    style.addLayer(
        SymbolLayer("$STOPS-name", STOPS).withProperties(
            PropertyFactory.textField(Expression.get("name")), PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(13f), PropertyFactory.textOffset(arrayOf(0f, 1.3f)), PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textColor(routeColor), PropertyFactory.textHaloColor(android.graphics.Color.WHITE), PropertyFactory.textHaloWidth(1.5f),
        ).apply { minZoom = 8f },
    )
    listOf(START to START_COLOR, END to END_COLOR).forEach { (id, color) ->
        style.addLayer(
            CircleLayer("$id-dot", id).withProperties(
                PropertyFactory.circleRadius(11f), PropertyFactory.circleColor(color.toArgb()),
                PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE), PropertyFactory.circleStrokeWidth(3f),
            ),
        )
    }
}

private fun showRoute(style: Style, roadLine: List<GeoPoint>?, start: GeoPoint?, end: GeoPoint?, stops: List<Pair<String, GeoPoint>>) {
    fun source(id: String) = style.getSourceAs<GeoJsonSource>(id)
    fun line(points: List<GeoPoint>) = Feature.fromGeometry(LineString.fromLngLats(points.map(GeoPoint::toPoint)))
    val connectors = if (roadLine != null) {
        listOfNotNull(start?.let { line(listOf(it, roadLine.first())) }, end?.let { line(listOf(roadLine.last(), it)) })
    } else {
        listOfNotNull(if (start != null && end != null) line(listOf(start, end)) else null)
    }
    source(ROUTE)?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(roadLine?.let(::line))))
    source(CONNECTOR)?.setGeoJson(FeatureCollection.fromFeatures(connectors))
    source(STOPS)?.setGeoJson(
        FeatureCollection.fromFeatures(stops.map { (name, at) -> Feature.fromGeometry(at.toPoint()).apply { addStringProperty("name", name) } }),
    )
    source(START)?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(start?.let { Feature.fromGeometry(it.toPoint()) })))
    source(END)?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(end?.let { Feature.fromGeometry(it.toPoint()) })))
}

private fun fitCamera(map: MapLibreMap, points: List<GeoPoint>, padding: IntArray) {
    val distinct = points.distinct()
    if (distinct.size == 1) {
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(distinct[0].lat, distinct[0].lng), SINGLE_POINT_ZOOM))
        return
    }
    val bounds = LatLngBounds.Builder().apply { distinct.forEach { include(LatLng(it.lat, it.lng)) } }.build()
    map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding[0], padding[1], padding[2], padding[3]))
}

private fun GeoPoint.toPoint(): Point = Point.fromLngLat(lng, lat)

/** 출발지·목적지가 고속도로 경로의 양 끝과 충분히 가까울 때만 고속도로 선을 그린다. */
private fun fitsRoads(path: List<GeoPoint>, start: GeoPoint?, end: GeoPoint?, totalKm: Double): Boolean {
    if (path.isEmpty() || start == null || end == null) return false
    val allowed = totalKm * MAX_CONNECTOR_SHARE
    return roughKm(start, path.first()) <= allowed && roughKm(path.last(), end) <= allowed
}

private fun roughKm(a: GeoPoint, b: GeoPoint): Double {
    val dy = (a.lat - b.lat) * 111.3
    val dx = (a.lng - b.lng) * 111.3 * cos(Math.toRadians(a.lat))
    return hypot(dx, dy)
}

private const val MAX_CONNECTOR_SHARE = 0.3
private const val SINGLE_POINT_ZOOM = 14.0
private const val ATTRIBUTION = "© OpenFreeMap · © OpenMapTiles · © OpenStreetMap contributors"
private const val LIGHT_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"
private const val ROUTE = "route"
private const val CONNECTOR = "connector"
private const val STOPS = "stops"
private const val START = "start"
private const val END = "end"
