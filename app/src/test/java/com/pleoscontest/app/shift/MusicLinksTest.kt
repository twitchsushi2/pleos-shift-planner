package com.pleoscontest.app.shift

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicLinksTest {
    @Test
    fun `멜론 링크를 알아본다`() {
        assertEquals("멜론", MusicLinks.platform("https://www.melon.com/mymusic/playlist/mymusicplaylistview_inform.htm?plylstSeq=123"))
        assertEquals("멜론", MusicLinks.platform("https://m2.melon.com/pda/msvc/playList.jsp?plylstSeq=123"))
        assertEquals("멜론", MusicLinks.platform("https://kko.to/AbCdEf"))
        assertEquals("멜론", MusicLinks.platform("melonapp://playlist?plylstSeq=123"))
    }

    @Test
    fun `다른 음악 서비스도 알아본다`() {
        assertEquals("유튜브 뮤직", MusicLinks.platform("https://music.youtube.com/playlist?list=PL123"))
        assertEquals("애플 뮤직", MusicLinks.platform("https://music.apple.com/kr/playlist/x/pl.1"))
        assertEquals("스포티파이", MusicLinks.platform("https://open.spotify.com/playlist/1"))
        assertEquals(null, MusicLinks.platform("https://example.com/list"))
        assertEquals("음악 플레이리스트", MusicLinks.label("https://example.com/list"))
    }

    @Test
    fun `열 수 없는 링크는 받지 않는다`() {
        assertTrue(MusicLinks.isValid("https://music.youtube.com/playlist?list=PL1"))
        assertFalse(MusicLinks.isValid("재즈"))
        assertFalse(MusicLinks.isValid("javascript:alert(1)"))
        assertFalse(MusicLinks.isValid(""))
    }
}
