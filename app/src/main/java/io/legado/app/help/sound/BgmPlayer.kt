package io.legado.app.help.sound

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.constant.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * AI 朗读背景音乐播放器。
 *
 * 与 TTS 播放器是两个独立 [ExoPlayer] 实例，由系统混音，因此不需要把音乐与语音
 * 离线拼成一条音轨；代价是播放设备必须支持多路音频（Android 默认如此）。
 *
 * 语音段播放期间把音乐压到 [duckRatio] 倍，段间空隙恢复，避免盖住人声。
 */
class BgmPlayer(
    context: Context,
    private val scope: CoroutineScope
) {

    private val player: ExoPlayer = ExoPlayer.Builder(context).build()

    private var tracks: List<BgmTrack> = emptyList()
    private var trackIndex = 0
    private var baseVolume = 1f
    private var duckRatio = 0.35f
    private var ducked = false
    private var started = false
    private var fadeJob: Job? = null

    init {
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.volume = 0f
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && started) {
                    playNext()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                AppLog.put("背景音乐播放失败：${error.errorCodeName}", error)
                if (started) playNext()
            }
        })
    }

    /** 换一组曲目：保留当前播放位置，只有曲目确实变化时才重新起播。 */
    fun setTracks(newTracks: List<BgmTrack>, volume: Float, duck: Float) {
        baseVolume = volume.coerceIn(0f, 1f)
        duckRatio = duck.coerceIn(0f, 1f)
        val usable = newTracks.filter { it.isUsable }
        if (usable == tracks) {
            applyTargetVolume(animated = false)
            return
        }
        tracks = usable
        trackIndex = 0
        if (tracks.isEmpty()) {
            stop()
        } else if (started) {
            playCurrent(immediate = true)
        }
    }

    /** 按章节推进选曲：章节序号对曲目数取模，保证同一章稳定落在同一首。 */
    fun startForChapter(chapterIndex: Int) {
        if (tracks.isEmpty()) return
        trackIndex = ((chapterIndex % tracks.size) + tracks.size) % tracks.size
        started = true
        playCurrent(immediate = false)
    }

    fun pause() {
        if (!started) return
        fadeTo(0f, FADE_OUT_MS) { player.pause() }
    }

    fun resume() {
        if (!started || tracks.isEmpty()) return
        if (player.playbackState == Player.STATE_IDLE) {
            playCurrent(immediate = false)
        } else {
            player.play()
            applyTargetVolume(animated = true)
        }
    }

    /** 进入/离开语音段：只改目标音量，不打断音乐本身。 */
    fun setDucked(ducked: Boolean) {
        if (this.ducked == ducked) return
        this.ducked = ducked
        if (started) applyTargetVolume(animated = true)
    }

    fun stop() {
        started = false
        fadeJob?.cancel()
        fadeJob = null
        runCatching {
            player.pause()
            player.clearMediaItems()
        }
        player.volume = 0f
    }

    fun release() {
        stop()
        runCatching { player.release() }
        tracks = emptyList()
    }

    private fun playNext() {
        if (tracks.isEmpty()) return
        trackIndex = (trackIndex + 1) % tracks.size
        playCurrent(immediate = false)
    }

    private fun playCurrent(immediate: Boolean) {
        val track = tracks.getOrNull(trackIndex) ?: return
        val item = buildMediaItem(track) ?: run {
            AppLog.put("背景音乐不可用：${track.name} ${track.filePath}")
            if (tracks.size > 1) playNext()
            return
        }
        runCatching {
            player.setMediaItem(item)
            player.repeatMode = if (track.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            player.prepare()
            player.play()
            if (immediate) {
                applyTargetVolume(animated = false)
            } else {
                player.volume = 0f
                applyTargetVolume(animated = true)
            }
        }.onFailure {
            AppLog.put("背景音乐起播失败：${track.name}", it)
            if (tracks.size > 1) playNext()
        }
    }

    private fun buildMediaItem(track: BgmTrack): MediaItem? {
        val path = track.filePath
        if (path.isBlank()) return null
        if (path.startsWith("http", true)) return MediaItem.fromUri(path)
        if (!File(path).isFile) return null
        return MediaItem.fromUri(File(path).toURI().toString())
    }

    private fun applyTargetVolume(animated: Boolean) {
        val trackVolume = tracks.getOrNull(trackIndex)?.volume?.coerceIn(0f, 1f) ?: 1f
        val target = (baseVolume * trackVolume * if (ducked) duckRatio else 1f).coerceIn(0f, 1f)
        if (animated) {
            fadeTo(target, FADE_MS)
        } else {
            fadeJob?.cancel()
            player.volume = target
        }
    }

    private fun fadeTo(target: Float, durationMs: Long, onDone: (() -> Unit)? = null) {
        fadeJob?.cancel()
        val from = player.volume
        if (from == target) {
            onDone?.invoke()
            return
        }
        fadeJob = scope.launch {
            val steps = (durationMs / FADE_STEP_MS).coerceAtLeast(1)
            for (step in 1..steps.toInt()) {
                if (!isActive) return@launch
                delay(FADE_STEP_MS)
                player.volume = from + (target - from) * (step.toFloat() / steps.toFloat())
            }
            player.volume = target
            onDone?.invoke()
        }
    }

    private companion object {
        const val FADE_MS = 600L
        const val FADE_OUT_MS = 800L
        const val FADE_STEP_MS = 40L
    }
}
