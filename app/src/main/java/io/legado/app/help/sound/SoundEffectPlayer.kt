package io.legado.app.help.sound

import android.media.AudioAttributes
import android.media.SoundPool
import io.legado.app.constant.AppLog
import java.io.File

/**
 * AI 朗读音效播放器。
 *
 * 音效都是短音频，用 [SoundPool] 预加载后零延迟触发，可多路并发；
 * 与 TTS 播放器各自持有独立音频通路，由系统混音，不需要额外混流。
 *
 * 一个实例对应一次朗读会话，由 [AloudSoundEngine] 创建和释放。
 */
class SoundEffectPlayer {

    private val loaded = HashMap<String, Int>()       // filePath -> soundId
    private val pending = HashMap<Int, String>()      // sampleId -> filePath（加载中）
    private val lastPlayAt = HashMap<String, Long>()  // ruleId -> 上次触发时间
    private var compiledRules: List<CompiledRule> = emptyList()
    private var enabled = false
    private var globalVolume = 1f

    private val soundPool: SoundPool by lazy {
        SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
            .apply {
                // SoundPool 只在异步加载完成后才拿得到可用的 sampleId：
                // 加载期间用 pending 记录 sampleId -> 路径，成功后再转进 loaded。
                setOnLoadCompleteListener { _, sampleId, status ->
                    val filePath = synchronized(pending) { pending.remove(sampleId) }
                    if (status == 0 && filePath != null) {
                        loaded[filePath] = sampleId
                    } else if (status != 0) {
                        AppLog.put("音效加载失败(status=$status)：$filePath")
                    }
                }
            }
    }

    fun configure(rules: List<SoundEffectRule>, volume: Float) {
        enabled = rules.any { it.isUsable } && volume > 0f
        globalVolume = volume.coerceIn(0f, 1f)
        compiledRules = rules.filter { it.isUsable }.mapNotNull { rule ->
            runCatching { CompiledRule(rule, compileMatcher(rule)) }
                .onFailure { AppLog.put("音效规则无效：${rule.name}", it) }
                .getOrNull()
        }
        loaded.entries.removeAll { entry ->
            val unused = compiledRules.none { it.rule.filePath == entry.key }
            if (unused) soundPool.unload(entry.value)
            unused
        }
        if (enabled) {
            compiledRules.forEach { preload(it.rule.filePath) }
        }
    }

    private fun compileMatcher(rule: SoundEffectRule): (String) -> Boolean {
        if (rule.isRegex) {
            val regex = Regex(rule.pattern)
            return { text -> regex.containsMatchIn(text) }
        }
        val keywords = rule.pattern.split('|')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        require(keywords.isNotEmpty()) { "no keyword" }
        return { text -> keywords.any { text.contains(it, ignoreCase = true) } }
    }

    private fun preload(filePath: String) {
        if (loaded.containsKey(filePath)) return
        if (synchronized(pending) { pending.containsValue(filePath) }) return
        if (filePath.startsWith("http", true)) {
            // 网络音效需要先落地，未就绪时跳过，不影响朗读
            return
        }
        val file = File(filePath)
        if (!file.isFile) {
            AppLog.put("音效文件不存在：$filePath")
            return
        }
        runCatching {
            val sampleId = soundPool.load(filePath, 1)
            synchronized(pending) { pending[sampleId] = filePath }
        }.onFailure {
            AppLog.put("音效加载失败：$filePath", it)
        }
    }

    /**
     * 朗读一段文本时调用：命中规则的触发音效。
     *
     * 同一规则受 cooldownMs 限制，避免同一句话里连续触发同一音效。
     */
    fun onText(text: String?) {
        if (!enabled || text.isNullOrBlank()) return
        val now = System.currentTimeMillis()
        compiledRules.forEach { compiled ->
            val rule = compiled.rule
            if (!compiled.matches(text)) return@forEach
            val last = lastPlayAt[rule.id] ?: 0L
            if (now - last < rule.cooldownMs) return@forEach
            val soundId = loaded[rule.filePath] ?: return@forEach
            val volume = (rule.volume * globalVolume).coerceIn(0f, 1f)
            if (volume <= 0f) return@forEach
            runCatching {
                soundPool.play(soundId, volume, volume, 1, 0, 1f)
            }.onFailure {
                AppLog.put("音效播放失败：${rule.name}", it)
            }
            lastPlayAt[rule.id] = now
        }
    }

    fun release() {
        runCatching { soundPool.release() }
        loaded.clear()
        synchronized(pending) { pending.clear() }
        lastPlayAt.clear()
        compiledRules = emptyList()
        enabled = false
    }

    private class CompiledRule(
        val rule: SoundEffectRule,
        val matches: (String) -> Boolean
    )

    private companion object {
        const val MAX_STREAMS = 6
    }
}
