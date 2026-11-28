package io.legado.app.help.sound

import android.content.Context
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.CoroutineScope

/**
 * AI 朗读音效与背景音乐的统一入口。
 *
 * 朗读服务只看到这一个门面：章节开始时换曲、每段文本播出前触发音效、
 * 语音进行时压低音乐、暂停/停止时收尾。音量与开关全部来自 AppConfig，
 * 曲目与规则按书来自 [AloudSoundStore]，两者都不在引擎里自己存状态。
 *
 * 一个朗读会话对应一个实例，会话结束调用 [detach]。
 */
class AloudSoundEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {

    private val bgm = BgmPlayer(context, scope)
    private val soundEffect = SoundEffectPlayer()

    private var bookUrl: String? = null
    private var attached = false
    private var lastChapterIndex = Int.MIN_VALUE
    private var pendingUnduck = false

    /** 朗读开始时挂载：读取本书配置并起播背景音乐。 */
    fun attach(bookUrl: String?, chapterIndex: Int) {
        if (attached) detach()
        this.bookUrl = bookUrl
        attached = true
        lastChapterIndex = Int.MIN_VALUE
        val config = runCatching { AloudSoundStore.getConfig(bookUrl) }
            .onFailure { AppLog.put("朗读音效配置读取失败", it) }
            .getOrDefault(BookAloudSoundConfig())
        val effectEnabled = AppConfig.aloudSoundEffectEnabled && config.hasEffect
        soundEffect.configure(
            rules = if (effectEnabled) config.effectRules else emptyList(),
            volume = AppConfig.aloudSoundEffectVolume / 100f
        )
        val bgmEnabled = AppConfig.aloudBgmEnabled && config.hasBgm
        bgm.setTracks(
            newTracks = if (bgmEnabled) config.bgmTracks else emptyList(),
            volume = AppConfig.aloudBgmVolume / 100f,
            duck = AppConfig.aloudBgmDuckRatio / 100f
        )
        if (bgmEnabled) {
            bgm.startForChapter(chapterIndex)
        }
    }

    /** 换章：背景音乐按章节推进到对应曲目。 */
    fun onChapterChanged(chapterIndex: Int) {
        if (!attached || chapterIndex == lastChapterIndex) return
        lastChapterIndex = chapterIndex
        bgm.startForChapter(chapterIndex)
    }

    /** 一段文本开始播出：触发命中的音效，并把音乐压低。 */
    fun onSegmentStarted(text: String?) {
        if (!attached) return
        runCatching { soundEffect.onText(text) }
            .onFailure { AppLog.put("音效触发异常", it) }
        pendingUnduck = true
        bgm.setDucked(true)
    }

    /** 一段文本播完：恢复音乐音量。 */
    fun onSegmentEnded() {
        if (!attached || !pendingUnduck) return
        pendingUnduck = false
        bgm.setDucked(false)
    }

    /** 朗读暂停：音乐淡出并暂停，避免暂停后还在响。 */
    fun pause() {
        if (!attached) return
        bgm.pause()
    }

    /** 朗读恢复。 */
    fun resume() {
        if (!attached) return
        bgm.resume()
    }

    /** 停止朗读：释放音效池并停掉音乐。 */
    fun detach() {
        if (!attached) return
        attached = false
        pendingUnduck = false
        bookUrl = null
        lastChapterIndex = Int.MIN_VALUE
        runCatching { bgm.stop() }
        runCatching { soundEffect.release() }
    }

    /**
     * 配置变化后重新生效（音量、开关、曲目、规则在设置页改动后调用）。
     * 不改变当前朗读进度，只重新装载配置。
     */
    fun reload(chapterIndex: Int) {
        if (!attached) return
        attach(bookUrl, if (chapterIndex == Int.MIN_VALUE) 0 else chapterIndex)
    }
}
