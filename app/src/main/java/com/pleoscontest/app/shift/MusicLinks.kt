package com.pleoscontest.app.shift

import java.net.URI

/**
 * 운전자가 저장한 플레이리스트 링크. 앱은 재생을 직접 조종할 수 없어(Pleos에 미디어 SDK가 없고, 음악 앱은 외부 조종을 막는다)
 * 링크를 그 음악 앱으로 열어 주기만 한다. 바로 재생될지 목록만 열릴지는 음악 앱에 달려 있다.
 */
object MusicLinks {
    private val PLATFORMS = listOf(
        listOf("melon.com", "kko.to", "kko.kakao.com") to "멜론",
        listOf("music.youtube.com", "youtube.com", "youtu.be") to "유튜브 뮤직",
        listOf("music.apple.com") to "애플 뮤직",
        listOf("spotify.com", "spotify.link") to "스포티파이",
        listOf("genie.co.kr") to "지니",
        listOf("music-flo.com", "flomuz.io") to "FLO",
        listOf("bugs.co.kr") to "벅스",
    )

    /** 열 수 있는 링크인지. 웹 주소나 멜론 앱 주소(melonapp://)만 받는다. */
    fun isValid(link: String): Boolean {
        val uri = runCatching { URI(link.trim()) }.getOrNull() ?: return false
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> !uri.host.isNullOrBlank()
            "melonapp" -> true
            else -> false
        }
    }

    /** 링크가 어느 음악 서비스 것인지. 모르면 null. */
    fun platform(link: String): String? {
        val uri = runCatching { URI(link.trim()) }.getOrNull() ?: return null
        if (uri.scheme.equals("melonapp", ignoreCase = true)) return "멜론"
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.")?.removePrefix("m2.") ?: return null
        return PLATFORMS.firstOrNull { (hosts, _) -> hosts.any { host == it || host.endsWith(".$it") } }?.second
    }

    fun label(link: String): String = "${platform(link) ?: "음악"} 플레이리스트"
}
