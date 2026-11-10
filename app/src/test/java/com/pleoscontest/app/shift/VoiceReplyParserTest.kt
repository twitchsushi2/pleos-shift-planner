package com.pleoscontest.app.shift

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceReplyParserTest {
    private fun assertAll(expected: VoiceReply?, vararg said: String) =
        said.forEach { assertEquals(it, expected, VoiceReplyParser.parse(it)) }

    @Test
    fun `수락하는 말`() = assertAll(VoiceReply.YES, "응", "네", "네.", "좋아", "수락", "바꿔 줘", "그래", "알았어", "오케이")

    @Test
    fun `미루는 말`() = assertAll(VoiceReply.LATER, "미뤄 줘", "미뤄지고", "미룰래", "다음 휴게소에서 바꿀래", "나중에 할게")

    @Test
    fun `거절하는 말`() = assertAll(VoiceReply.NO, "아니", "아니요", "그대로 갈게", "싫어")

    @Test
    fun `상관없는 말은 무시한다`() = assertAll(null, "오늘 날씨 어때", "", "...", "음악 틀어 줘", "어디야", "네비 켜")
}
