package com.pleoscontest.app.shift

import org.junit.Assert.assertEquals
import org.junit.Test

class JosaTest {
    @Test
    fun `받침에 맞춰 을과 를을 고른다`() {
        assertEquals("교대를", Josa.object_("교대"))
        assertEquals("휴식을", Josa.object_("휴식"))
        assertEquals("하빈졸음쉼터를", Josa.object_("하빈졸음쉼터"))
        assertEquals("멜론 플레이리스트를", Josa.object_("멜론 플레이리스트"))
    }

    @Test
    fun `받침에 맞춰 으로와 로를 고르고 ㄹ 받침은 로다`() {
        assertEquals("인천공항으로", Josa.toward("인천공항"))
        assertEquals("공검휴게소로", Josa.toward("공검휴게소"))
        assertEquals("서울로", Josa.toward("서울"))
        assertEquals("베니키아호텔 해운대로", Josa.toward("베니키아호텔 해운대"))
        // 괄호 속 방향 표시는 건너뛰고 이름 끝 글자로 본다.
        assertEquals("안성맞춤휴게소(부산방향)로", Josa.toward("안성맞춤휴게소(부산방향)"))
        // 한글이 아니면 받침이 없는 것으로 본다.
        assertEquals("IFC몰로", Josa.toward("IFC몰"))
        assertEquals("Starbucks로", Josa.toward("Starbucks"))
    }
}
