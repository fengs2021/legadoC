package io.legado.app.help.sound

import io.legado.app.constant.AppLog
import io.legado.app.utils.GSON
import splitties.init.appCtx

/**
 * 音效库索引与本地检索。
 *
 * 检索算法直接沿用广播剧音效库的 AI 匹配方案（单字检索）：
 * 关键词拆成单字 → 过滤停用字 → 按「命中字数」降序、同分按名字短优先排序。
 * 这套排序是关键：单字匹配会捞回两百多个候选，噪声率 78%~98%，
 * 但真目标通常命中 2~4 个字，排序后自然浮到前面（平均第 7 名）。
 */
object SoundLibraryIndex {

    private const val ASSET = "sound_library_keywords.json"

    /** 命中极多但毫无区分度的字，必须过滤，否则会捞回上千条无用结果。 */
    private val STOP_WORDS: Set<Char> = (
        "的了是一不有着我你他她它们在这那上下中大小多少甲乙丙" +
            "个把被让给对从向到过还很就都要会能可所只又再"
        ).toSet()

    data class Entry(
        val category: String,
        val subCategory: String,
        val name: String,
        val keywords: List<String>
    ) {
        /** 候选展示与回声用的完整路径：大分组/子分组/音效名 */
        val path: String get() = "$category/$subCategory/$name"
    }

    data class Scored(
        val entry: Entry,
        val hitChars: Int
    )

    @Volatile
    private var cached: List<Entry>? = null

    @Volatile
    private var byName: Map<String, Entry>? = null

    fun entries(): List<Entry> {
        cached?.let { return it }
        val parsed = runCatching {
            appCtx.assets.open(ASSET).use { input ->
                val root = GSON.fromJson(input.reader(Charsets.UTF_8), Map::class.java) as? Map<*, *>
                    ?: emptyMap<Any, Any>()
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
            AppLog.put("音效库索引读取失败", it)
        }.getOrNull().orEmpty()
        cached = parsed
        return parsed
    }

    val isAvailable: Boolean get() = entries().isNotEmpty()

    val totalCount: Int get() = entries().size

    fun byName(name: String): Entry? {
        val map = byName ?: run {
            val built = entries().groupBy { it.name }.mapValues { it.value.first() }
            byName = built
            built
        }
        return map[name.trim()]
    }

    /**
     * 单字检索：与 Z 阅读一致的打分口径。
     *
     * @param keywords 模型提炼的关键字（1~2 字）
     * @param topN 返回条数，方案实测 Top20 召回率 99%
     */
    fun searchByKeywords(keywords: List<String>, topN: Int = 20): List<Scored> {
        val chars = keywords.joinToString("")
            .filter { it !in STOP_WORDS && !it.isWhitespace() }
            .toSet()
        if (chars.isEmpty()) return emptyList()
        val scored = entries().mapNotNull { entry ->
            val hit = chars.count { it in entry.name }
            if (hit <= 0) null else Scored(entry, hit)
        }
        if (scored.isEmpty()) return emptyList()
        return scored
            .sortedWith(compareByDescending<Scored> { it.hitChars }.thenBy { it.entry.name.length })
            .take(topN)
    }

    /** 关键词覆盖不足时的兜底：候选少于阈值即视为模型想偏了。 */
    const val MIN_CANDIDATES = 15
}
