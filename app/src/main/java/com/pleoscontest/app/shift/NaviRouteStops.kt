package com.pleoscontest.app.shift

/** 내비 회전 안내 한 줄. [meters]는 앞 안내에서 이 안내까지의 거리다. */
data class NaviTurn(val type: String, val meters: Double, val description: String)

/** 내비가 고른 경로가 지나는 휴게소. [km]는 지금 자리에서부터의 거리다. */
data class NaviStop(val name: String, val km: Double)

/**
 * 내비가 실제로 안내하는 경로의 휴게소. 내비 SDK는 경로 모양을 주지 않지만, 회전 안내 목록(getTBTInfo)은 경로 전체를 담고
 * "휴게소 진입" 안내마다 휴게소 이름과 앞 안내에서의 거리를 준다. 이것을 더해 가면 어느 휴게소를 몇 km 뒤에 지나는지 안다.
 * 그래서 운전자가 내비의 경로 후보 중 무엇을 골랐든, 길을 바꿔 다시 탐색했든 그 길의 휴게소로 계획할 수 있다.
 */
data class NaviRouteStops(val turnsKm: Double, val stops: List<NaviStop>) {
    /** 앱 경로에 없는 휴게소를 지나면 다른 길이다. 지나온 휴게소가 목록에서 빠지는 것은 같은 길이다. */
    fun differsFrom(names: Collection<String>): Boolean {
        val known = names.map(::key).toSet()
        return stops.any { key(it.name) !in known }
    }

    companion object {
        const val SERVICE_AREA = "TURN_ENTER_SERVICEAREA"
        const val DROWSY_SHELTER = "TURN_RESTAREA"
        /** 다음 회전 하나만 온 것은 경로 전체가 아니다. */
        private const val MIN_TURNS = 3

        /**
         * 회전 안내 목록에서 휴게소를 뽑는다. 경로 전체 목록이 아니면 null.
         * "휴게소 진입"에는 하이패스센터 같은 곳도 섞여 이름에 휴게소가 든 것만 쓴다.
         * 졸음쉼터(TURN_RESTAREA)도 넣는다. 식당·주유소는 없지만 차를 세우고 운전자를 바꾸거나 잠깐 쉬기에는 충분하다.
         */
        fun parse(turns: List<NaviTurn>): NaviRouteStops? {
            if (turns.size < MIN_TURNS) return null
            var meters = 0.0
            val stops = mutableListOf<NaviStop>()
            turns.forEach { turn ->
                meters += turn.meters
                val name = turn.description.trim()
                val usable = (turn.type == SERVICE_AREA && "휴게소" in name) || (turn.type == DROWSY_SHELTER && name.isNotEmpty())
                if (usable) stops += NaviStop(name, meters / 1000)
            }
            return NaviRouteStops(meters / 1000, stops)
        }

        /** 이름 비교용. 띄어쓰기와 괄호 속 방향 표시를 뺀다. */
        fun key(name: String) = name.replace(Regex("\\(.*?\\)"), "").replace(" ", "").trim()
    }
}
