package io.legado.app.plugin.jread

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.help.tts.TtsSpeedPolicy
import io.legado.app.service.BaseReadAloudService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 「Z阅读插件引擎」的朗读服务。
 *
 * 逐段把文本交给插件运行时合成成音频字节，落盘后交给 ExoPlayer 播放；
 * 段落推进沿用 [BaseReadAloudService] 的 contentList / nowSpeak / readAloudNumber 语义。
 * 合成失败不静默：记录插件名与音色，暂停朗读并明示原因。
 */
class JReadReadAloudService : BaseReadAloudService(), Player.Listener {

    private val exoPlayer: ExoPlayer by lazy { ExoPlayer.Builder(this).build() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var synthJob: Job? = null
    private var playIndexJob: Job? = null

    private val speakDir: File
        get() = File(cacheDir, "jreadSpeak").apply { mkdirs() }

    override fun onCreate() {
        super.onCreate()
        exoPlayer.addListener(this)
    }

    override fun onDestroy() {
        synthJob?.cancel()
        playIndexJob?.cancel()
        scope.cancel()
        runCatching { exoPlayer.removeListener(this) }
        runCatching { exoPlayer.release() }
        super.onDestroy()
    }

    override fun play() {
        exoPlayer.stop()
        applyPlaybackRate()
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("JRead 朗读列表为空")
            nextChapter()
            return
        }
        super.play()
        upReadAloudLoading(true)
        playCurrentParagraph()
    }

    override fun playStop() {
        synthJob?.cancel()
        playIndexJob?.cancel()
        exoPlayer.stop()
    }

    override fun pauseReadAloud(abandonFocus: Boolean) {
        synthJob?.cancel()
        exoPlayer.pause()
        super.pauseReadAloud(abandonFocus)
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        if (!exoPlayer.isPlaying) exoPlayer.play()
    }

    /**
     * 语速只经播放端变速生效：合成输出保持插件原生速度，避免与插件内部
     * 的 rate 参数叠加造成双重加速（与脚本引擎管线同一处理）。
     */
    override fun upSpeechRate(reset: Boolean) {
        applyPlaybackRate()
        if (!BaseReadAloudService.pause) {
            contentList.getOrNull(nowSpeak)?.let { trackProgress(it) }
        }
    }

    override fun refreshTtsRoute() {
        // 音色/插件变量变更后重新合成当前段
        playCurrentParagraph()
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? = null

    private fun applyPlaybackRate() {
        exoPlayer.setPlaybackSpeed(TtsSpeedPolicy.playbackRate(AppConfig.speechRatePlay))
    }

    /** 合成并播放当前段；[nowSpeak] 越界时进入下一章。 */
    private fun playCurrentParagraph() {
        synthJob?.cancel()
        val text = contentList.getOrNull(nowSpeak)
        if (text.isNullOrBlank()) {
            nextChapter()
            return
        }
        upReadAloudLoading(true)
        synthJob = scope.launch {
            val route = resolveRoute()
            val audio = runCatching {
                withContext(Dispatchers.IO) {
                    JReadRuntime.synthesize(
                        plugin = route.plugin,
                        text = text,
                        voiceId = route.voiceId,
                        baseRate = 50,
                        baseVolume = 50,
                        basePitch = 50
                    )
                }
            }.getOrElse { error ->
                AppLog.put(
                    "JRead 合成失败：${route.plugin.name} / ${route.voiceId}\n${error.localizedMessage}",
                    error,
                    true
                )
                launch(Dispatchers.Main) {
                    upReadAloudLoading(false)
                    pauseReadAloud()
                }
                return@launch
            }
            val file = writeAudioFile(text, route, audio)
            withContext(Dispatchers.Main) {
                exoPlayer.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
                exoPlayer.prepare()
                exoPlayer.play()
                upReadAloudLoading(false)
                trackProgress(text)
            }
        }
    }

    private fun writeAudioFile(text: String, route: ResolvedRoute, audio: JReadAudio): File {
        val key = md5("${route.plugin.pluginId}|${route.voiceId}|$text")
        val extension = when (audio.contentType) {
            "audio/mpeg" -> "mp3"
            "audio/aac" -> "aac"
            "audio/ogg" -> "ogg"
            "audio/flac" -> "flac"
            else -> "wav"
        }
        val file = File(speakDir, "$key.$extension")
        if (!file.exists() || file.length() != audio.bytes.size.toLong()) {
            file.writeBytes(audio.bytes)
        }
        return file
    }

    /** 位置推进：按已播放时长换算段内字数，与其他引擎的进度语义一致。 */
    private fun trackProgress(text: String) {
        playIndexJob?.cancel()
        playIndexJob = scope.launch(Dispatchers.Default) {
            var lastCharDurationMs = 100L
            while (true) {
                val duration = exoPlayer.duration
                val position = exoPlayer.currentPosition
                val charDuration = if (duration > 0) duration / text.length else lastCharDurationMs
                if (duration > 0) lastCharDurationMs = charDuration
                val offset = if (duration > 0) {
                    (text.length.toLong() * position / duration).toInt()
                } else {
                    (position / charDuration.coerceAtLeast(1L)).toInt()
                }
                withContext(Dispatchers.Main) {
                    upTtsProgress(readAloudNumber + offset + 1)
                }
                kotlinx.coroutines.delay(charDuration.coerceAtLeast(20L))
                if (exoPlayer.playbackState == Player.STATE_ENDED) break
            }
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_ENDED -> advanceParagraph()
            Player.STATE_READY -> upReadAloudLoading(false)
            else -> Unit
        }
    }

    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
        AppLog.put("JRead 播放失败\n${error.localizedMessage}", error, true)
        pauseReadAloud()
    }

    /** 段落推进：与 HTTP 引擎同语义，末段进入下一章。 */
    private fun advanceParagraph() {
        val content = contentList.getOrNull(nowSpeak) ?: run {
            nextChapter()
            return
        }
        readAloudNumber += content.length + 1 - paragraphStartPos
        paragraphStartPos = 0
        if (nowSpeak < contentList.lastIndex) {
            nowSpeak++
            playCurrentParagraph()
        } else {
            nextChapter()
        }
    }

    /**
     * 解析本段使用的（插件, 音色）。
     * 优先用 AI 选角绑定（[io.legado.app.data.entities.BookTtsVoiceBinding]，engineId = jread）；
     * 没有绑定时用引擎当前音色，仍没有就用第一个启用插件的第一个音色。
     */
    private fun resolveRoute(): ResolvedRoute {
        val configured = JReadSettings.voiceId
        val fromBinding = JReadSettings.resolveBoundVoice()
        val globalVoiceId = fromBinding ?: configured
        if (globalVoiceId != null) {
            JReadPluginStore.byVoiceGlobalId(globalVoiceId)?.let { (plugin, voiceId) ->
                if (plugin.enabled) return ResolvedRoute(plugin, voiceId)
            }
        }
        val fallback = firstVoice()
        if (fallback != null) return fallback
        throw IllegalStateException("没有可用的语音插件，请先在「Z阅读插件引擎」里导入并启用插件")
    }

    private fun firstVoice(): ResolvedRoute? {
        JReadPluginStore.enabled().forEach { plugin ->
            val voices = runCatching { JReadRuntime.voices(plugin) }.getOrNull().orEmpty()
            val first = voices.firstOrNull()
            if (first != null) return ResolvedRoute(plugin, first.id)
        }
        return null
    }

    private data class ResolvedRoute(val plugin: JReadPlugin, val voiceId: String)

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
