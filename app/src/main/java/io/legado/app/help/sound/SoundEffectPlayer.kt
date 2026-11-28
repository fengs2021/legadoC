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
 * 在线音效库条目在 [AloudSoundEngine] 下载完成后通过 [attachResolved] 增量接入，
 * 未就绪的规则只是暂时不响，不影响朗读本身。
 */
class SoundEffectPlayer {

    private val soundIds = HashMap<String, Int>()      // ruleId -> soundId
    private val pending = HashMap<Int, String>()       // sampleId -> ruleId
    private val loadedPaths = HashMap<String, String>() // ruleId -> filePath
    private val lastPlayAt = HashMap<String, Long>()   // ruleId -> 上次触发时间
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
                setOnLoadCompleteListener { _, sampleId, status ->
                    val ruleId = synchronized(pending) { pending.remove(sampleId) }
                    if (status == 0 && ruleId != null) {
                        soundIds[ruleId] = sampleId
                    } else if (status != 0) {
                        AppLog.put("音效加载失败(status=$status)：$ruleId")
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
        // 回收不再需要的样本
        val alive = compiledRules.map { it.rule.id }.toSet()
        soundIds.keys.filter { it !in alive }.forEach { ruleId ->
            soundIds.remove(ruleId)?.let { runCatching { soundPool.unload(it) } }
            loadedPaths.remove(ruleId)
        }
        if (!enabled) return
        compiledRules.forEach { prepared ->
            val rule = prepared.rule
            if (rule.isLibrary) {
                // 已缓存的库条目直接加载；未缓存的等下载完成回调 attachResolved
                OnlineSoundLibrary.cachedFile(rule.libraryName)?.let { load(rule.id, it) }
            } else {
                load(rule.id, File(rule.filePath))
            }
        }
    }

    /** 在线音效库条目下载完成后的增量接入。 */
    fun attachResolved(ruleId: String, localFile: File) {
        if (!enabled) return
        if (compiledRules.none { it.rule.id == ruleId }) return
        load(ruleId, localFile)
    }

    private fun load(ruleId: String, file: File) {
        if (soundIds.containsKey(ruleId)) return
        if (loadedPaths[ruleId] == file.absolutePath) return
        if (synchronized(pending) { pending.containsValue(ruleId) }) return
        if (!file.isFile) {
            AppLog.put("音效文件不存在：${file.absolutePath}")
            return
        }
        runCatching {
            val sampleId = soundPool.load(file.absolutePath, 1)
            synchronized(pending) { pending[sampleId] = ruleId }
            loadedPaths[ruleId] = file.absolutePath
        }.onFailure {
            AppLog.put("音效加载失败：${file.absolutePath}", it)
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

    /**
     * 朗读一段文本时调用：命中规则的触发音效。
     *
     * 同一规则受 cooldownMs 限制，避免同一句话里连续触发同一音效。
     */
    fun onText(text: String?) {
        if (!enabled || text.isNullOrBlank()) return
        val now = System.currentTimeMillis()
        compiledRules.forEach { prepared ->
            val rule = prepared.rule
            if (!prepared.matches(text)) return@forEach
            val last = lastPlayAt[rule.id] ?: 0L
            if (now - last < rule.cooldownMs) return@forEach
            val soundId = soundIds[rule.id] ?: return@forEach
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
        soundIds.clear()
        synchronized(pending) { pending.clear() }
        loadedPaths.clear()
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
