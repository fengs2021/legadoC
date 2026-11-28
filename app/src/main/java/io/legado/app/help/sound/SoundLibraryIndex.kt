package io.legado.app.help.sound

import io.legado.app.constant.AppLog
import io.legado.app.utils.GSON
import splitties.init.appCtx

/**
 * 音效库关键词索引：分类 → 子类 → 「音效名|关键词1|关键词2…」。
 *
 * 这套索引来自广播剧音效库的整理成果，作用是让「文本 → 候选音效」的粗筛在本地完成：
 * 先按关键词收敛到几十条候选，再交给 AI 决策，避免把几千个音效名整包塞进提示词。
 */
object SoundLibraryIndex {

    private const val ASSET = "sound_library_keywords.json"

    data class Entry(
        val category: String,
        val subCategory: String,
        val name: String,
        val keywords: List<String>
    ) {
        /** 未接入 AI 时的本地兜底匹配：关键词任一命中即算候选。 */
        fun matches(text: String): Boolean =
            keywords.any { it.isNotBlank() && text.contains(it, ignoreCase = true) }
    }

    @Volatile
    private var cached: List<Entry>? = null

    @Volatile
    private var keywordMap: Map<String, List<Entry>>? = null

    fun entries(): List<Entry> {
        cached?.let { return it }
        val parsed = runCatching {
            appCtx.assets.open(ASSET).use { input ->
                val root = GSON.fromJson(
                    input.reader(Charsets.UTF_8),
                    Map::class.java
                ) as? Map<*, *> ?: emptyMap<Any, Any>()
                buildList {
                    root.forEach { (categoryKey, subMap) ->
                        val category = categoryKey?.toString().orEmpty()
                        (subMap as? Map<*, *>)?.forEach { (subKey, list) ->
                            val subCategory = subKey?.toString().orEmpty()
                            (list as? List<*>)?.forEach { raw ->
                                val line = raw?.toString() ?: return@forEach
                                val parts = line.split('|').map { it.trim() }
                                val name = parts.firstOrNull().orEmpty()
                                if (name.isBlank()) return@forEach
                                add(
                                    Entry(
                                        category = category,
                                        subCategory = subCategory,
                                        name = name,
                                        keywords = parts.drop(1).filter { it.isNotBlank() }
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }.onFailure {
            AppLog.put("音效库关键词索引读取失败", it)
        }.getOrNull().orEmpty()
        cached = parsed
        return parsed
    }

    val isAvailable: Boolean get() = entries().isNotEmpty()

    /**
     * 文本粗筛：返回命中的条目，按命中关键词在文本中的位置排序，
     * [limit] 控制送给 AI 的候选规模。
     */
    fun candidatesFor(text: String, limit: Int = 60): List<Entry> {
        if (text.isBlank()) return emptyList()
        val all = entries()
        if (all.isEmpty()) return emptyList()
        val hits = all.filter { it.matches(text) }
        if (hits.size <= limit) return hits
        // 命中词越靠前越相关：用最小命中位置排序，保证同一段里先出现的声音优先
        return hits.sortedBy { entry ->
            entry.keywords
                .map { text.indexOf(it, ignoreCase = true) }
                .filter { it >= 0 }
                .minOrNull() ?: Int.MAX_VALUE
        }.take(limit)
    }

    /** 名称 → 条目，供播放侧按 AI 返回的音效名解析成实际文件。 */
    fun byName(name: String): Entry? {
        val map = keywordMap ?: run {
            val built = entries().groupBy { it.name }
            keywordMap = built
            built
        }
        return map[name.trim()]?.firstOrNull()
    }

    /** 全部音效名，用于给 AI 说明可选范围（按分类分组）。 */
    fun namesByCategory(): Map<String, List<String>> =
        entries().groupBy { it.category }.mapValues { (_, list) -> list.map { it.name } }
}
