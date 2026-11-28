package io.legado.app.help.sound

import android.content.Context
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.help.tts.ReadAloudDebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * AI 朗读音效与背景音乐的统一入口。
 *
 * 播放侧三条来源并存，按优先级命中：
 * 1. AI 时间线：哪一段第几个字放哪个音效、本章用哪首配乐；
 * 2. 本地关键词匹配：AI 不可用或还没生成时间线时的兜底，保证开关打开就有声音；
 * 3. 本书手动规则：用户自己加的本地音效与曲目。
 *
 * 每个决策点都写进朗读日志（tag 音效 / 配乐），便于直接定位「为什么没响」。
 */
class AloudSoundEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {

    private val bgm = BgmPlayer(context, scope)
    private val soundEffect = SoundEffectPlayer()
    private val localMatcher = LocalSoundMatcher()

    private var bookUrl: String? = null
    private var attached = false
    private var chapterIndex: Int = -1
    private var pendingUnduck = false
    private var config: BookAloudSoundConfig = BookAloudSoundConfig()

    private var timelineJob: Job? = null
    private var setupJob: Job? = null

    private var timeline: AiSoundTimelineHelper.Timeline? = null
    private var chapterSegments: List<String> = emptyList()
    private var pendingCues: List<AiSoundTimelineHelper.SoundCue> = emptyList()
    private val firedCueKeys: MutableSet<String> = mutableSetOf()

    fun attach(bookUrl: String?, chapterIndex: Int) {
        if (attached) detach()
        this.bookUrl = bookUrl
        this.chapterIndex = chapterIndex
        attached = true
        chapterSegments = emptyList()
        config = runCatching { AloudSoundStore.getConfig(bookUrl) }
            .onFailure { AppLog.put("朗读音效配置读取失败", it) }
            .getOrDefault(BookAloudSoundConfig())

        log("挂载：书=" + (bookUrl ?: "<空>") + " 章=" + chapterIndex +
                " 音效开关=" + AppConfig.aloudSoundEffectEnabled +
                " 配乐开关=" + AppConfig.aloudBgmEnabled +
                " AI匹配=" + AppConfig.aloudSoundAiMatch +
                " 手动规则=" + config.effectRules.size +
                " 手动曲目=" + config.bgmTracks.size)

        soundEffect.configure(
            rules = if (AppConfig.aloudSoundEffectEnabled) config.effectRules else emptyList(),
            volume = AppConfig.aloudSoundEffectVolume / 100f
        )
        bgm.setTracks(
            newTracks = if (AppConfig.aloudBgmEnabled) config.bgmTracks else emptyList(),
            volume = AppConfig.aloudBgmVolume / 100f,
            duck = AppConfig.aloudBgmDuckRatio / 100f
        )

        // 本地匹配与包状态都要读磁盘，放后台，避免阻塞朗读起播
        setupJob = scope.launch {
            // 首次切到公共目录时把旧的私有目录文件搬过去，避免重复下载
            runCatching { AloudSoundPaths.migrateFromPrivate() }
                .onFailure { AppLog.put("音效目录迁移失败", it) }
            runCatching { localMatcher.build() }
                .onFailure { AppLog.put("本地音效索引构建失败", it) }
        }

        timeline = if (bookUrl.isNullOrBlank()) null else AiSoundTimelineHelper.loadCached(bookUrl, chapterIndex)
        log(
            if (timeline == null) "本章无时间线缓存"
            else "时间线缓存命中：cue " + timeline!!.cues.size + " 条，配乐=" + timeline!!.bgmName
        )
        applyTimeline()
        ensureBgmPlayable(chapterIndex)
    }

    fun onChapterChanged(chapterIndex: Int, segments: List<String> = emptyList()) {
        if (!attached) return
        val changed = chapterIndex != this.chapterIndex
        this.chapterIndex = chapterIndex
        if (segments.isNotEmpty()) chapterSegments = segments
        if (!changed && timeline != null) {
            bgm.startForChapter(chapterIndex)
            return
        }
        val url = bookUrl
        timeline = if (url.isNullOrBlank()) null else AiSoundTimelineHelper.loadCached(url, chapterIndex)
        applyTimeline()
        ensureBgmPlayable(chapterIndex)
        if (timeline == null) requestTimeline()
    }

    private fun requestTimeline() {
        val url = bookUrl ?: return
        if (chapterSegments.isEmpty()) {
            log("跳过 AI 匹配：本章段落为空");
            return
        }
        if (!AppConfig.aloudSoundAiMatch) {
            log("跳过 AI 匹配：开关关闭，走本地关键词匹配");
            return
        }
        if (!AiSoundTimelineHelper.isAvailable()) {
            log("跳过 AI 匹配：AI 未配置或音效索引为空，走本地关键词匹配");
            return
        }
        if (timelineJob?.isActive == true) return
        val segments = chapterSegments
        val index = chapterIndex
        log("AI 匹配开始：段落 " + segments.size + " 段，分析字数 " + AppConfig.aloudSoundAnalysisChars);
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
                log("AI 匹配完成：cue " + generated.cues.size + " 条，配乐=" + generated.bgmName);
                if (!attached || index != chapterIndex) return@onSuccess
                timeline = generated
                applyTimeline()
                ensureBgmPlayable(chapterIndex)
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                log("AI 匹配失败，改用本地关键词匹配：" + (it.localizedMessage ?: it.javaClass.simpleName));
                AppLog.put("AI 音效匹配失败", it)
            }
        }
    }

    private fun downloadedBgmNames(): List<String> {
        val pack = SoundPackManager.pack("bgm") ?: return emptyList()
        return pack.files
            .filter { SoundPackManager.localFile(pack, it).isFile }
            .map { it.substringAfterLast('/').substringBeforeLast('.') }
    }

    private fun downloadedBgmFiles(): List<File> {
        val pack = SoundPackManager.pack("bgm") ?: return emptyList()
        return pack.files.mapNotNull { relative ->
            val file = SoundPackManager.localFile(pack, relative)
            if (file.isFile && file.length() > 0) file else null
        }
    }

    private fun applyTimeline() {
        val current = timeline ?: return
        val files = current.cues.mapNotNull { cue -> SoundPackManager.findByName(cue.effectName) }
            .distinctBy { it.absolutePath }
        if (files.isEmpty()) {
            if (current.cues.isNotEmpty()) {
                log("时间线里的音效本地都找不到文件，请确认音效包已下载完成");
            }
        } else {
            soundEffect.preloadFiles(files)
            log("预加载音效 " + files.size + " 个");
        }
    }

    /** 配乐来源优先级：AI 指定 → 本书手动曲目 → 已下载音乐包按章节轮换。 */
    private fun ensureBgmPlayable(chapterIndex: Int) {
        if (!AppConfig.aloudBgmEnabled) {
            log("配乐关闭：跳过");
            return
        }
        val aiTrack = timeline?.bgmName?.takeIf { it.isNotBlank() }
            ?.let { name -> SoundPackManager.findByName(name) }
        if (aiTrack != null) {
            bgm.setTracks(
                newTracks = listOf(
                    BgmTrack(
                        id = "ai:" + aiTrack.absolutePath,
                        name = aiTrack.name.substringBeforeLast('.'),
                        filePath = aiTrack.absolutePath,
                        volume = 1f,
                        loop = false
                    )
                ),
                volume = AppConfig.aloudBgmVolume / 100f,
                duck = AppConfig.aloudBgmDuckRatio / 100f
            )
            bgm.startForChapter(chapterIndex)
            log("配乐使用 AI 选曲：" + aiTrack.name);
            return
        }
        if (config.bgmTracks.any { it.isUsable }) {
            bgm.startForChapter(chapterIndex)
            log("配乐使用本书手动曲目 " + config.bgmTracks.size + " 首");
            return
        }
        val packFiles = downloadedBgmFiles()
        if (packFiles.isEmpty()) {
            log("没有可用配乐：音乐包未下载，且本书没有手动曲目");
            return
        }
        bgm.setTracks(
            newTracks = packFiles.map { file ->
                BgmTrack(
                    id = "pack:" + file.absolutePath,
                    name = file.name.substringBeforeLast('.'),
                    filePath = file.absolutePath,
                    volume = 1f,
                    loop = false
                )
            },
            volume = AppConfig.aloudBgmVolume / 100f,
            duck = AppConfig.aloudBgmDuckRatio / 100f
        )
        bgm.startForChapter(chapterIndex)
        log("配乐使用音乐包按章轮换：共 " + packFiles.size + " 首");
    }

    fun onSegmentStarted(segmentIndex: Int, text: String?) {
        if (!attached) return
        firedCueKeys.clear()
        pendingCues = timeline?.cuesFor(segmentIndex).orEmpty()
        if (AppConfig.aloudSoundEffectEnabled) {
            runCatching { soundEffect.onText(text) }
                .onFailure { AppLog.put("音效触发异常", it) }
            fireCuesUpTo(0)
            if (pendingCues.isEmpty()) {
                // 没有 AI 时间线：走本地关键词匹配，保证开关打开就能听到声音
                val hit = runCatching { localMatcher.match(text.orEmpty()) }.getOrNull()
                if (hit != null) {
                    val volume = (AppConfig.aloudSoundEffectVolume / 100f).coerceIn(0f, 1f)
                    soundEffect.playFile(hit.first, volume)
                }
            }
        }
        pendingUnduck = true
        bgm.setDucked(true)
    }

    fun onSegmentProgress(charOffset: Int) {
        if (!attached || pendingCues.isEmpty()) return
        fireCuesUpTo(charOffset)
    }

    private fun fireCuesUpTo(charOffset: Int) {
        if (pendingCues.isEmpty()) return
        pendingCues.forEach { cue ->
            val key = cue.segmentIndex.toString() + ":" + cue.effectName + ":" + cue.offsetChars
            if (key in firedCueKeys) return@forEach
            if (cue.offsetChars > charOffset) return@forEach
            firedCueKeys.add(key)
            val file = SoundPackManager.findByName(cue.effectName)
            if (file == null) {
                log("时间线音效缺文件：" + cue.effectName)
                return@forEach
            }
            val volume = (cue.volume * AppConfig.aloudSoundEffectVolume / 100f).coerceIn(0f, 1f)
            soundEffect.playFile(file, volume)
            log("时间线触发：" + cue.effectName + " @" + cue.offsetChars + " 字");
        }
    }

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
        log("卸载：释放音效与配乐");
        attached = false
        pendingUnduck = false
        timelineJob?.cancel()
        timelineJob = null
        setupJob?.cancel()
        setupJob = null
        timeline = null
        chapterSegments = emptyList()
        pendingCues = emptyList()
        firedCueKeys.clear()
        bookUrl = null
        chapterIndex = -1
        runCatching { bgm.stop() }
        runCatching { soundEffect.release() }
    }

    fun reload(chapterIndex: Int) {
        if (!attached) return
        attach(bookUrl, if (chapterIndex < 0) 0 else chapterIndex)
    }

    private fun log(message: String) {
        ReadAloudDebugLog.add(TAG, message)
    }

    private companion object {
        const val TAG = "音效"
    }
}
