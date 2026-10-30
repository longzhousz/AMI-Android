package com.longzhousz.ami.playback

import androidx.media3.common.PlaybackException
import com.longzhousz.ami.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PlaybackErrorsTest {
    @Test fun errorMessagesKeepSafeReasonsAndNeverCopyPrivateExceptionMessages() {
        val cases = listOf(
            NeteaseLoginExpired() to "重新登录",
            NeteaseAudioUnavailable(trial = true) to "试听片段",
            NeteaseAudioUnavailable() to "未返回",
            NeteaseRequestRejected(403) to "403",
            IOException("https://m7.music.126.net/a?token=PRIVATE; MUSIC_U=PRIVATE") to "连接失败",
            IllegalArgumentException("https://foreign.test/PRIVATE") to "校验")
        for ((cause, expected) in cases) {
            val wrapped = PlaybackException("PRIVATE", IOException("PRIVATE", cause), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
            val message = neteasePlaybackError(wrapped)
            assertTrue(message, message.contains(expected))
            assertFalse(message.contains("PRIVATE")); assertFalse(message.contains("https://"))
        }
        val decoder = neteasePlaybackError(PlaybackException("PRIVATE", null, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertTrue(decoder.contains("解码")); assertFalse(decoder.contains("登录"))
    }
}
