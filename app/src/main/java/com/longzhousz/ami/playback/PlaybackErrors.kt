package com.longzhousz.ami.playback

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import com.longzhousz.ami.data.NeteaseAudioUnavailable
import com.longzhousz.ami.data.NeteaseLoginExpired
import com.longzhousz.ami.data.NeteaseRequestRejected
import java.io.IOException

/** Use only fixed messages/codes. Exception messages can contain signed URLs or session data. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun neteasePlaybackError(error: PlaybackException): String {
    val causes = generateSequence(error as Throwable) { it.cause }.take(12).toList()
    causes.filterIsInstance<NeteaseLoginExpired>().firstOrNull()?.let { return it.message!! }
    causes.filterIsInstance<NeteaseAudioUnavailable>().firstOrNull()?.let { return it.message!! }
    causes.filterIsInstance<NeteaseRequestRejected>().firstOrNull()?.let { return it.message!! }
    causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.let {
        return "音频服务器暂时无法提供这首歌（${it.responseCode}），请重试"
    }
    if (causes.any { it is IllegalArgumentException }) return "网易云返回的音源未通过校验，请重试"
    return when (error.errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
            "手机暂时无法解码当前音源（${error.errorCode}）"
        else -> if (causes.any { it is IOException }) "音频连接失败，请检查网络后重试（${error.errorCode}）"
            else "这首歌暂时播放失败（${error.errorCode}），请重试"
    }
}
