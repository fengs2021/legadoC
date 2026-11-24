package io.legado.app.plugin.jread

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import io.legado.app.constant.AppLog
import java.io.ByteArrayOutputStream

/**
 * 插件试听：把 WAV/PCM 用 AudioTrack 直放一次，不进朗读队列。
 * 只服务 `ttsrv.playAudio / playAudioChunk` 的试听语义，播放完即释放。
 */
internal object JReadPreview {

    private const val MAX_PREVIEW_BYTES = 8 * 1024 * 1024

    @Volatile
    private var playing: AudioTrack? = null

    fun play(audio: ByteArray) {
        if (audio.size < 64 || audio.size > MAX_PREVIEW_BYTES) return
        runCatching {
            stop()
            val pcm = if (JReadAudioProbe.isWav(audio)) stripWavHeader(audio) else audio
            if (pcm.isEmpty()) return
            val sampleRate = JReadAudioProbe.sampleRate(audio).takeIf { it > 0 } ?: 24000
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            playing = track
            Thread {
                runCatching {
                    Thread.sleep((pcm.size * 1000L / (sampleRate * 2)).coerceAtLeast(200))
                    stop()
                }
            }.start()
        }.onFailure {
            AppLog.put("JRead 试听失败\n${it.localizedMessage}", it)
        }
    }

    fun stop() {
        playing?.let { track ->
            runCatching { if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop() }
            runCatching { track.release() }
        }
        playing = null
    }

    private fun stripWavHeader(audio: ByteArray): ByteArray {
        var offset = 12
        while (offset + 8 <= audio.size) {
            val id = String(audio, offset, 4, Charsets.US_ASCII)
            val size = (audio[offset + 4].toInt() and 0xFF) or
                ((audio[offset + 5].toInt() and 0xFF) shl 8) or
                ((audio[offset + 6].toInt() and 0xFF) shl 16) or
                ((audio[offset + 7].toInt() and 0xFF) shl 24)
            if (id == "data") {
                val start = offset + 8
                val end = (start + size).coerceAtMost(audio.size)
                val out = ByteArrayOutputStream(end - start)
                out.write(audio, start, end - start)
                return out.toByteArray()
            }
            offset += 8 + size + (size and 1)
        }
        return ByteArray(0)
    }
}
