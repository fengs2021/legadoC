package io.legado.app.help.sound

import android.content.Context
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * AI 朗读音效与背景音乐的统一入口。
 *
 * 播放侧只认两件事：
 * 1. **AI 时间线**（[AiSoundTimelineHelper.Timeline]）——哪一段的第几个字放哪个音效、
 *    本章用哪首配乐。时间线按章缓存，没缓存时后台生成，生成好立即生效。
 * 2. **本书手动规则**（[BookAloudSoundConfig]）——用户自己加的本地音效与曲目。
 *
 * 两者并存：手动规则按关键词命中触发，时间线按段内字符偏移触发。
 */
class AloudSoundEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {

    private val bgm = BgmPlayer(context, scope)
    private val soundEffect = SoundEffectPlayer()

    private var bookUrl: String? = null
    private var attached = false
    private var chapterIndex: Int = -1
    private var pendingUnduck = false

    private var timelineJob: Job? = null

    private var timeline: AiSoundTimelineHelper.Timeline? = null
    private var chapterSegments: List<String> = emptyList()

    /** 本段待触发的 cue（按 offsetChars 升序），每段开始时重置 */
    private var pendingCues: List<AiSoundTimelineHelper.SoundCue> = emptyList()
    private var firedCueKeys: MutableSet<String> = mutableSetOf()

    fun attach(bookUrl: String?, chapterIndex: Int) {
        if (attached) detach()
        this.bookUrl = bookUrl
        this.chapterIndex = chapterIndex
        attached = true
        chapterSegments = emptyList()
        val config = runCatching { AloudSoundStore.getConfig(bookUrl) }
            .onFailure { AppLog.put("朗读音效配置读取失败", it) }
            .getOrDefault(BookAloudSoundConfig())
        soundEffect.configure(
            rules = if (AppConfig.aloudSoundEffectEnabled) config.effectRules else emptyList(),
            volume = AppConfig.aloudSoundEffectVolume / 100f
        )
        bgm.setTracks(
            newTracks = if (AppConfig.aloudBgmEnabled) config.bgmTracks else emptyList(),
            volume = AppConfig.aloudBgmVolume / 100f,
            duck = AppConfig.aloudBgmDuckRatio / 100f
        )
        timeline = if (bookUrl.isNullOrBlank()) null else AiSoundTimelineHelper.loadCached(bookUrl, chapterIndex)
        applyTimeline()
    }

    /**
     * 换章：更新章节内容并切换配乐。
     *
     * [segments] 与朗读用的 contentList 同序；仅用于没有缓存时间线时发起 AI 匹配。
     */
    fun onChapterChanged(chapterIndex: Int, segments: List<String> = emptyList()) {
        if (!attached) return
        val chapterChanged = chapterIndex != this.chapterIndex
        this.chapterIndex = chapterIndex
        if (segments.isNotEmpty()) chapterSegments = segments
        if (!chapterChanged && timeline != null) {
            bgm.startForChapter(chapterIndex)
            return
        }
        val url = bookUrl
        timeline = if (url.isNullOrBlank()) {
            null
        } else {
            AiSoundTimelineHelper.loadCached(url, chapterIndex)
        }
        applyTimeline()
        bgm.startForChapter(chapterIndex)
        if (timeline == null) requestTimeline()
    }

    /** 该章还没有时间线且 AI 可用时，后台生成一次。 */
    private fun requestTimeline() {
        val url = bookUrl ?: return
        if (chapterSegments.isEmpty()) return
        if (!AppConfig.aloudSoundAiMatch) return
        if (!AiSoundTimelineHelper.isAvailable()) return
        if (timelineJob?.isActive == true) return
        val segments = chapterSegments
        val index = chapterIndex
        timelineJob = scope.launch {
            runCatching {
                AiSoundTimelineHelper.generate(
                    bookUrl = url,
                    chapterIndex = index,
                    segments = segments,
                    bgmNames = downloadedBgmNames(),
                    analysisChars = AppConfig.aloudSoundAnalysisChars
                )
            }.onSuccess { generated ->
                if (!attached || index != chapterIndex) return@onSuccess
                timeline = generated
                applyTimeline()
                if (generated.bgmName.isNotBlank()) bgm.startForChapter(chapterIndex)
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                AppLog.put("AI 音效匹配失败，本篇改为关键词匹配", it)
            }
        }
    }

    private fun downloadedBgmNames(): List<String> {
        val pack = SoundPackManager.pack("bgm") ?: return emptyList()
        return pack.files
            .filter { SoundPackManager.localFile(pack, it).isFile }
            .map { it.substringAfterLast('/').substringBeforeLast('.') }
    }

    /** 时间线生效：预加载音效文件、按 AI 结果选配乐，必要时回退到本书曲目。 */
    private fun applyTimeline() {
        val current = timeline
        if (current == null) {
            if (AppConfig.aloudBgmEnabled) bgm.startForChapter(chapterIndex)
            return
        }
        val files = current.cues.mapNotNull { cue ->
            SoundPackManager.findByName(cue.effectName)
        }.distinctBy { it.absolutePath }
        if (files.isNotEmpty()) soundEffect.preloadFiles(files)
        val aiBgm = current.bgmName.takeIf { it.isNotBlank() }
            ?.let { SoundPackManager.findByName(it) }
        if (aiBgm != null && AppConfig.aloudBgmEnabled) {
            bgm.setTracks(
                newTracks = listOf(
                    BgmTrack(
                        id = "ai:${aiBgm.absolutePath}",
                        name = aiBgm.name.substringBeforeLast('.'),
                        filePath = aiBgm.absolutePath,
                        volume = 1f,
                        loop = false
                    )
                ),
                volume = AppConfig.aloudBgmVolume / 100f,
                duck = AppConfig.aloudBgmDuckRatio / 100f
            )
        }
    }

    /**
     * 一段文本开始播出：重置本段 cue，触发关键词规则，并把音乐压低。
     *
     * [segmentIndex] 与时间线里的 segmentIndex 同一套编号（contentList 下标）。
     */
    fun onSegmentStarted(segmentIndex: Int, text: String?) {
        if (!attached) return
        firedCueKeys.clear()
        pendingCues = timeline?.cuesFor(segmentIndex).orEmpty()
        runCatching { soundEffect.onText(text) }
            .onFailure { AppLog.put("音效触发异常", it) }
        // 段首 cue 立即触发
        fireCuesUpTo(0)
        pendingUnduck = true
        bgm.setDucked(true)
    }

    /** 段内进度：到了 cue 的偏移就触发。 */
    fun onSegmentProgress(charOffset: Int) {
        if (!attached || pendingCues.isEmpty()) return
        fireCuesUpTo(charOffset)
    }

    private fun fireCuesUpTo(charOffset: Int) {
        if (pendingCues.isEmpty()) return
        pendingCues.forEach { cue ->
            val key = "${cue.segmentIndex}:${cue.effectName}:${cue.offsetChars}"
            if (key in firedCueKeys) return@forEach
            if (cue.offsetChars > charOffset) return@forEach
            firedCueKeys.add(key)
            val file = SoundPackManager.findByName(cue.effectName) ?: return@forEach
            val volume = (cue.volume * AppConfig.aloudSoundEffectVolume / 100f).coerceIn(0f, 1f)
            soundEffect.playFile(file, volume)
        }
    }

    /** 一段文本播完：恢复音乐音量。 */
    fun onSegmentEnded() {
        if (!attached || !pendingUnduck) return
        pendingUnduck = false
        pendingCues = emptyList()
        bgm.setDucked(false)
    }

    fun pause() {
        if (!attached) return
        bgm.pause()
    }

    fun resume() {
        if (!attached) return
        bgm.resume()
    }

    fun detach() {
        if (!attached) return
        attached = false
        pendingUnduck = false
        timelineJob?.cancel()
        timelineJob = null
        timeline = null
        chapterSegments = emptyList()
        pendingCues = emptyList()
        firedCueKeys.clear()
        bookUrl = null
        chapterIndex = -1
        runCatching { bgm.stop() }
        runCatching { soundEffect.release() }
    }

    /** 配置变化后重新生效（音量、开关、曲目在设置页改动后调用）。 */
    fun reload(chapterIndex: Int) {
        if (!attached) return
        attach(bookUrl, if (chapterIndex < 0) 0 else chapterIndex)
    }
}
