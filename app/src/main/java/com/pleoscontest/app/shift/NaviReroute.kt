package com.pleoscontest.app.shift

/** 주행 중 내비 경로가 바뀌었을 때 수락해 둔 휴게소를 새 경로에 이어 붙인다. */
object NaviReroute {
    /**
     * 새 경로에서 수락한 휴게소를 찾아 돌려준다. 이름이 같은 곳이 있으면 새 경로의 거리로 바꾼다.
     * 내비 경유지로 넣은 곳([isWaypoint])은 내비가 휴게소 대신 경유지로 안내해 목록에서 빠질 수 있다. 그래도 내비가 들르므로
     * 새 경로에 원래 거리 그대로 끼워 넣고 붙든다. 경유지가 아니고 새 경로에도 없으면 놓친 것이라 null.
     */
    fun keepAccepted(route: Route, accepted: RestArea?, isWaypoint: Boolean): Pair<Route, RestArea?> {
        accepted ?: return route to null
        val key = NaviRouteStops.key(accepted.name)
        route.restAreas.firstOrNull { NaviRouteStops.key(it.name) == key }?.let { return route to it }
        if (!isWaypoint || accepted.km >= route.totalKm) return route to null
        return route.copy(restAreas = (route.restAreas + accepted).sortedBy { it.km }) to accepted
    }
}
