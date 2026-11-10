package com.pleoscontest.app.shift

/**
 * 이름 뒤에 붙는 조사를 받침에 맞춘다. 목적지·휴게소 이름은 아무 글자로나 끝날 수 있어
 * "인천공항로", "교대을"처럼 틀리지 않게 한다. 한글이 아닌 글자로 끝나면 받침이 없는 쪽으로 본다.
 */
object Josa {
    /** 을/를. 예) "교대를", "휴식을" */
    fun object_(word: String) = word + if (hasBatchim(word)) "을" else "를"

    /** 으로/로. ㄹ 받침은 "로"다. 예) "인천공항으로", "서울로", "휴게소로" */
    fun toward(word: String): String {
        val last = lastHangul(word) ?: return word + "로"
        val batchim = (last - 0xAC00) % 28
        return word + if (batchim == 0 || batchim == RIEUL) "로" else "으로"
    }

    private fun hasBatchim(word: String): Boolean = lastHangul(word)?.let { (it - 0xAC00) % 28 != 0 } ?: false

    /** 괄호 속 방향 표시("(부산방향)")와 뒤쪽 공백을 빼고 마지막 한글 음절. */
    private fun lastHangul(word: String): Int? =
        word.replace(Regex("\\(.*?\\)\\s*$"), "").trimEnd().lastOrNull()?.code?.takeIf { it in 0xAC00..0xD7A3 }

    private const val RIEUL = 8
}
