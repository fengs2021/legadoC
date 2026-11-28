package io.legado.app.help.sound

import com.google.gson.annotations.SerializedName
import io.legado.app.constant.AppLog
import io.legado.app.help.ai.AiChatService
import io.legado.app.help.ai.AiStoryboardConfig
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.security.MessageDigest

/**
 * AI 音效与配乐匹配：朗读某章前，由模型决定「哪一段、第几个字、放哪个音效」。
 *
 * 与 Z 阅读一致的两级流程：
 * 1. 本地关键词索引先把文本粗筛成几十条候选（[SoundLibraryIndex.candidatesFor]），
 *    避免把几千个音效名塞进提示词；
 * 2. 模型只在候选里挑真正需要发声的位置，宁可少不要滥。
 *
 * 结果按「书 + 章节」缓存，同一章重复朗读不再请求模型。
 */
object AiSoundTimelineHelper {

    private const val MAX_SEGMENTS_PER_REQUEST = 120
    private const val MAX_CANDIDATES_PER_SEGMENT = 12

    data class SoundCue(
        @SerializedName("segmentIndex") val segmentIndex: Int = 0,
        @SerializedName("effect") val effectName: String = "",
        @SerializedName("offsetChars") val offsetChars: Int = 0,
        @SerializedName("volume") val volume: Float = 0.85f
    )

    data class Timeline(
        @SerializedName("bookUrl") val bookUrl: String = "",
        @SerializedName("chapterIndex") val chapterIndex: Int = -1,
        @SerializedName("cues") val cues: List<SoundCue> = emptyList(),
        @SerializedName("bgm") val bgmName: String = "",
        @SerializedName("createdAt") val createdAt: Long = 0L
    ) {

        fun cuesFor(segmentIndex: Int): List<SoundCue> =
            cues.filter { it.segmentIndex == segmentIndex }.sortedBy { it.offsetChars }
    }

    private val cacheDir: File
        get() = File(appCtx.filesDir, "aloudSound/timelines").apply { mkdirs() }

    fun isAvailable(): Boolean =
        SoundLibraryIndex.isAvailable && runCatching { AiStoryboardConfig.isConfigured() }.getOrDefault(false)

    fun loadCached(bookUrl: String, chapterIndex: Int): Timeline? {
        if (bookUrl.isBlank() || chapterIndex < 0) return null
        val file = cacheFile(bookUrl, chapterIndex)
        if (!file.isFile) return null
        return runCatching {
            GSON.fromJson(file.readText(Charsets.UTF_8), Timeline::class.java)
        }.onFailure {
            AppLog.put("音效时间线缓存读取失败", it)
        }.getOrNull()?.takeIf { it.cues.isNotEmpty() || it.bgmName.isNotBlank() }
    }

    fun save(timeline: Timeline) {
        if (timeline.bookUrl.isBlank() || timeline.chapterIndex < 0) return
        runCatching {
            cacheFile(timeline.bookUrl, timeline.chapterIndex).apply {
                parentFile?.mkdirs()
                writeText(GSON.toJson(timeline), Charsets.UTF_8)
            }
        }.onFailure {
            AppLog.put("音效时间线缓存写入失败", it)
        }
    }

    fun clear(bookUrl: String) {
        if (bookUrl.isBlank()) return
        val prefix = bookHash(bookUrl) + "_"
        cacheDir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
    }

    /**
     * 生成一章的音效时间线。
     *
     * [segments] 必须与朗读的 contentList 同序同源，模型返回的 segmentIndex 直接按它索引。
     */
    suspend fun generate(
        bookUrl: String,
        chapterIndex: Int,
        segments: List<String>,
        bgmNames: List<String> = emptyList()
    ): Timeline = withContext(Dispatchers.IO) {
        require(segments.isNotEmpty()) { "章节内容为空，无法匹配音效" }
        if (!SoundLibraryIndex.isAvailable) throw IllegalStateException("音效库索引不可用")
        val (provider, modelId) = AiStoryboardConfig.requireModelTarget()
        val allCues = mutableListOf<SoundCue>()
        var bgm = ""
        segments.chunked(MAX_SEGMENTS_PER_REQUEST).forEachIndexed { blockIndex, block ->
            val baseIndex = blockIndex * MAX_SEGMENTS_PER_REQUEST
            val payload = buildPayload(baseIndex, block, bgmNames)
            val result = AiChatService.generateStructuredText(
                provider = provider,
                model = modelId,
                systemPrompt = SYSTEM_PROMPT,
                userContent = payload,
                temperature = 0.2,
                requestTemplate = AiStoryboardConfig.storyboardRequestTemplate
            )
            check(result.isNotBlank()) { "AI 音效匹配返回为空" }
            val parsed = parseTimeline(result, baseIndex, block.size)
            allCues += parsed.first
            if (bgm.isBlank()) bgm = parsed.second
        }
        val timeline = Timeline(
            bookUrl = bookUrl,
            chapterIndex = chapterIndex,
            cues = allCues.distinctBy { it.segmentIndex to it.effectName },
            bgmName = bgm,
            createdAt = System.currentTimeMillis()
        )
        save(timeline)
        Timeline(
            bookUrl = timeline.bookUrl,
            chapterIndex = timeline.chapterIndex,
            cues = timeline.cues,
            bgmName = timeline.bgmName,
            createdAt = timeline.createdAt
        )
    }

    private fun buildPayload(
        baseIndex: Int,
        segments: List<String>,
        bgmNames: List<String>
    ): String {
        val segmentPayload = segments.mapIndexed { offset, text ->
            val index = baseIndex + offset
            val candidates = SoundLibraryIndex.candidatesFor(text, MAX_CANDIDATES_PER_SEGMENT)
                .map { it.name }
                .distinct()
            mapOf(
                "segmentIndex" to index,
                "text" to text.take(400),
                "candidates" to candidates
            )
        }
        return GSON.toJson(
            mapOf(
                "bookBgmCandidates" to bgmNames,
                "segments" to segmentPayload
            )
        )
    }

    private fun parseTimeline(
        raw: String,
        baseIndex: Int,
        blockSize: Int
    ): Pair<List<SoundCue>, String> {
        val json = extractJson(raw)
        val root = runCatching { GSON.fromJson(json, RawTimeline::class.java) }.getOrNull()
            ?: return emptyList<SoundCue>() to ""
        val cues = root.cues.orEmpty().mapNotNull { raw ->
            val effect = raw.effect?.trim().orEmpty()
            if (effect.isBlank()) return@mapNotNull null
            val index = raw.segmentIndex ?: return@mapNotNull null
            if (index !in baseIndex until baseIndex + blockSize) return@mapNotNull null
            // 名字必须在索引里，避免模型臆造音效
            val known = SoundLibraryIndex.byName(effect)?.name ?: return@mapNotNull null
            SoundCue(
                segmentIndex = index,
                effectName = known,
                offsetChars = (raw.offsetChars ?: 0).coerceAtLeast(0),
                volume = (raw.volume ?: 0.85f).coerceIn(0.1f, 1f)
            )
        }
        return cues to root.bgm?.trim().orEmpty()
    }

    private fun extractJson(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.lines().dropWhile { it.trim().startsWith("```") }
                .dropLastWhile { it.trim().startsWith("```") }
                .joinToString("\n")
        }
        val start = text.indexOfFirst { it == '{' }
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else text
    }

    private fun cacheFile(bookUrl: String, chapterIndex: Int): File =
        File(cacheDir, "${bookHash(bookUrl)}_${chapterIndex}.json")

    private fun bookHash(bookUrl: String): String =
        MessageDigest.getInstance("MD5").digest(bookUrl.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private data class RawTimeline(
        val cues: List<RawCue>? = null,
        val bgm: String? = null
    )

    private data class RawCue(
        val segmentIndex: Int? = null,
        val effect: String? = null,
        val offsetChars: Int? = null,
        val volume: Float? = null
    )

    private val SYSTEM_PROMPT = """
        你是广播剧音效师。给你一章的分段文本，每段附带本地音效库中可能相关的候选音效名。
        任务：只为真正需要声音的位置挑音效，宁缺勿滥。

        规则：
        1. 只在文本明确出现动作、环境变化、情绪转折时才给音效；纯叙述、心理描写、对白本身不加。
        2. effect 必须严格取自该段 candidates 中的名字，不得自创、不得改写。
        3. offsetChars 是该音效在段内触发的字符偏移（0 表示段首），必须小于该段长度。
        4. 音量默认 0.85；环境底声可 0.5 左右以留出人声空间。
        5. 同一段最多 2 个音效；整章音效密度控制在每 3~5 段 1 个。
        6. 如果给定 bookBgmCandidates，可从中挑 1 首作为本章配乐，写入 bgm；没有合适就留空。

        输出格式（只返回 JSON，不要解释）：
        {"cues":[{"segmentIndex":3,"effect":"脚步声","offsetChars":5,"volume":0.85}],"bgm":"危机降临"}
    """.trimIndent()
}
