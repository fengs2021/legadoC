package io.legado.app.help.sound

import io.legado.app.help.tts.ReadAloudDebugLog
import java.io.File

/**
 * 本地音效匹配：不依赖 AI 的兜底通道。

 * AI 未配置、请求失败、或时间线还没生成时，用音效库自带的关键词做倒排匹配，
 * 保证「音效开关打开 + 音效包已下载」就能听到声音。
 *
 * 匹配方式：把段落切成单字与双字，查「关键词 → 音效」倒排表，
 * 命中字数最多的条目胜出；每段最多 1 个，并有全局冷却，避免刷屏。
 */
class LocalSoundMatcher {

    private var keywordIndex: Map<String, List<SoundLibraryIndex.Entry>> = emptyMap()
    /** 音效名 → 本地文件（只在已下载的包里找） */
    private var fileIndex: Map<String, File> = emptyMap()
    private var lastPlayAt = 0L
    private var lastEffectName = ""

    val isReady: Boolean get() = keywordIndex.isNotEmpty() && fileIndex.isNotEmpty()

    fun build() {
        val entries = SoundLibraryIndex.entries()
        val files = buildFileIndex()
        if (entries.isEmpty() || files.isEmpty()) {
            ReadAloudDebugLog.add(
                TAG,
                "本地匹配不可用：索引 " + entries.size + " 条，已下载可匹配文件 " + files.size + " 个"
            )
            keywordIndex = emptyMap()
            fileIndex = emptyMap()
            return
        }
        val index = HashMap<String, MutableList<SoundLibraryIndex.Entry>>()
        entries.forEach { entry ->
            if (!files.containsKey(entry.name)) return@forEach
            val keys = HashSet<String>()
            keys.add(entry.name)
            entry.keywords.forEach { keyword ->
                if (keyword.isNotBlank()) keys.add(keyword)
            }
            keys.forEach { key ->
                index.getOrPut(key) { mutableListOf() }.add(entry)
            }
        }
        keywordIndex = index
        fileIndex = files
        ReadAloudDebugLog.add(
            TAG,
            "本地匹配就绪：音效 " + files.size + " 个，关键词 " + index.size + " 条"
        )
    }

    private fun buildFileIndex(): Map<String, File> {
        val result = HashMap<String, File>()
        SoundPackManager.packs().forEach { pack ->
            if (pack.id == "bgm") return@forEach
            pack.files.forEach { relative ->
                val file = SoundPackManager.localFile(pack, relative)
                if (!file.isFile || file.length() <= 0) return@forEach
                val name = relative.substringAfterLast('/').substringBeforeLast('.')
                if (!result.containsKey(name)) result[name] = file
            }
        }
        return result
    }

    /** 返回本段应当播放的音效文件；没有合适的不返回。 */
    fun match(text: String): Pair<File, String>? {
        if (text.isBlank() || !isReady) return null
        val now = System.currentTimeMillis()
        if (now - lastPlayAt < COOLDOWN_MS) return null
        val hits = HashMap<String, Int>()
        val chars = text.length
        var i = 0
        while (i < chars) {
            val ch = text[i]
            if (ch in STOP) { i++; continue }
            val single = ch.toString()
            val two = if (i + 1 < chars) text.substring(i, i + 2) else ""
            if (two.isNotEmpty() && two in keywordIndex) {
                keywordIndex[two]?.forEach { hits[it.name] = (hits[it.name] ?: 0) + 2 }
            }
            if (single in keywordIndex) {
                keywordIndex[single]?.forEach { hits[it.name] = (hits[it.name] ?: 0) + 1 }
            }
            i++
        }
        if (hits.isEmpty()) return null
        val best = hits.entries
            .filter { it.value >= MIN_SCORE }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.length })
            .firstOrNull() ?: return null
        if (best.key == lastEffectName) return null
        val file = fileIndex[best.key] ?: return null
        lastPlayAt = now
        lastEffectName = best.key
        ReadAloudDebugLog.add(TAG, "本地命中：" + best.key + "（得分 " + best.value + "）" + file.name)
        return file to best.key
    }

    fun reset() {
        lastPlayAt = 0L
        lastEffectName = ""
    }

    private companion object {
        const val TAG = "音效"
        const val COOLDOWN_MS = 6000L
        const val MIN_SCORE = 3
        val STOP: Set<Char> = "的了是一不有着我你他她它们在这那上下中大小多少个把被让给对从向到过还很就都要会能可所只又再".toSet()
    }
}
