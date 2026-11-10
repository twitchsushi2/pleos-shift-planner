package com.pleoscontest.app.shift

enum class VoiceReply { YES, NO, LATER }

/**
 * 짧은 음성 응답을 규칙으로 해석한다. 교대 판단에 LLM을 쓰지 않는다는 원칙대로, 모르는 말은 무시하고 버튼에 맡긴다.
 * 인식기는 어미를 틀리곤 해서("미뤄 줘" → "미뤄지고") 앞부분으로 찾는다.
 */
object VoiceReplyParser {
    private val LATER = listOf("미뤄", "미루", "미룰", "다음휴게소", "다음에", "나중에")
    private val NO = listOf("그대로", "아니", "싫어", "안돼", "안할", "하지마")
    private val YES = listOf("좋아", "그래", "수락", "알았", "오케이", "바꿔", "바꾸", "바꿀", "할게", "그럴게")
    private val SHORT_YES = setOf("응", "어", "네", "예", "넵", "엉")

    fun parse(text: String): VoiceReply? {
        val said = text.filter { it.isLetterOrDigit() }
        return when {
            said.isEmpty() -> null
            // "다음 휴게소에서 바꿀래"에는 바꿀이 들어 있어 미루기를 먼저 본다.
            LATER.any { it in said } -> VoiceReply.LATER
            NO.any { it in said } -> VoiceReply.NO
            YES.any { it in said } -> VoiceReply.YES
            // 한 글자 대답은 다른 말에 쉽게 섞여 있어 그 말만 했을 때 인정한다.
            said in SHORT_YES -> VoiceReply.YES
            else -> null
        }
    }
}
