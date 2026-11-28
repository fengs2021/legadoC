package io.legado.app.help.sound

import com.google.gson.annotations.SerializedName
import io.legado.app.constant.AppLog
import io.legado.app.help.ai.AiChatService
import io.legado.app.help.ai.AiStoryboardConfig
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.security.MessageDigest

/**
 * AI 音效与配乐匹配，流程与广播剧音效库的 AI 匹配方案逐条对齐：
 *
 * 正文按分析字数分批：
 *   1. 关键词提取提示词，得到每段 1~2 字的关键字；
 *   2. 本地单字检索，得到带分组路径的 Top20 候选；
 *   3. 音效选择提示词，挑音效并用正文序号给出插入位置；
 *   4. 本地校验，名字必须落在候选集内、序号必须能映射回段内偏移。
 *
 * 位置用正文里的 [N] 序号表达，不用字符偏移量：模型算偏移经常错，
 * 序号则天然与朗读进度对齐。
 */
object AiSoundTimelineHelper {

    private const val PROMPT_ASSET = "ai_sound_prompts.json"
    private const val BGM_TEXT_LIMIT = 2000
    private const val MAX_KEYWORDS_PER_SEGMENT = 6
    private const val MIN_KEYWORDS_PER_SEGMENT = 2
    private const val CANDIDATE_TOP_N = 20

    private val PUNCTUATION = "，。！？；：、…—～,.!?;:".toSet()

    data class SoundCue(
        @SerializedName("segmentIndex") val segmentIndex: Int = 0,
        @SerializedName("effect") val effectName: String = "",
        @SerializedName("offsetChars") val offsetChars: Int = 0,
        @SerializedName("volume") val volume: Float = 0.85f,
        @SerializedName("reason") val reason: String = ""
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

    private data class Prompts(
        val bgm: String = "",
        val extractKeywords: String = "",
        val pickEffects: String = ""
    )

    private val cacheDir: File
        get() = AloudSoundPaths.timelinesDir()

    /** 三类提示词各取用户当前启用的那条；未配置时回落到内置默认。 */
    private fun prompts(): Prompts = Prompts(
        bgm = AiSoundPromptStore.active(AiSoundPromptStore.Kind.BGM).content,
        extractKeywords = AiSoundPromptStore.active(AiSoundPromptStore.Kind.EXTRACT).content,
        pickEffects = AiSoundPromptStore.active(AiSoundPromptStore.Kind.PICK).content
    )

    fun isAvailable(): Boolean = SoundLibraryIndex.isAvailable &&
        runCatching { AiStoryboardConfig.isConfigured() }.getOrDefault(false)

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
     * 生成一章的音效与配乐。
     *
     * segments 必须与朗读用的 contentList 同序同源；bgmNames 是本地已下载的音乐名（不含扩展名）。
     */
    suspend fun generate(
        bookUrl: String,
        chapterIndex: Int,
        segments: List<String>,
        bgmNames: List<String> = emptyList(),
        analysisChars: Int = 480
    ): Timeline = withContext(Dispatchers.IO) {
        require(segments.isNotEmpty()) { "章节内容为空，无法匹配音效" }
        if (!SoundLibraryIndex.isAvailable) throw IllegalStateException("音效库索引不可用")
        val target = AiStoryboardConfig.requireModelTarget()
        val provider = target.first
        val modelId = target.second
        val prompt = prompts()
        val cues = mutableListOf<SoundCue>()

        segments.chunkedByChars(analysisChars).forEach { batch ->
            runCatching {
                matchBatch(provider, modelId, batch, segments, prompt)
            }.onSuccess { picked ->
                cues += picked
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                AppLog.put("音效匹配失败：第 " + (batch.range.first + 1) + " 段起", error)
            }
        }

        val bgm = if (bgmNames.isEmpty()) "" else runCatching {
            pickBgm(provider, modelId, segments, bgmNames, prompt)
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            AppLog.put("背景音乐选择失败", it)
        }.getOrDefault("")

        val timeline = Timeline(
            bookUrl = bookUrl,
            chapterIndex = chapterIndex,
            cues = cues.filter { it.effectName.isNotBlank() }
                .distinctBy { it.segmentIndex.toString() + "|" + it.effectName + "|" + it.offsetChars },
            bgmName = bgm,
            createdAt = System.currentTimeMillis()
        )
        save(timeline)
        timeline
    }

    /** 一批段落：关键词提取 → 本地检索 → 精挑 → 校验映射回段内偏移。 */
    private suspend fun matchBatch(
        provider: io.legado.app.ui.main.ai.AiProviderConfig,
        modelId: String,
        batch: Batch,
        segments: List<String>,
        prompt: Prompts
    ): List<SoundCue> {
        // 1) 关键词提取
        val extractRaw = AiChatService.generateStructuredText(
            provider = provider,
            model = modelId,
            systemPrompt = prompt.extractKeywords,
            userContent = batch.text,
            disableThinking = !AppConfig.aloudSoundThinking,
            temperature = 0.2,
            requestTemplate = AiStoryboardConfig.storyboardRequestTemplate
        )
        var keywords = parseKeywords(extractRaw)

        // 2) 本地单字检索
        var candidates = SoundLibraryIndex.searchByKeywords(
            keywords.values.flatten(), CANDIDATE_TOP_N
        )
        if (candidates.size < SoundLibraryIndex.MIN_CANDIDATES && keywords.isNotEmpty()) {
            // 兜底：候选太少说明模型想偏了，追加一轮扩词后再检索
            val retryInput = batch.text + "\n\n（上一轮关键字：" +
                keywords.values.flatten().joinToString("、") +
                " 检索不到足够候选，请换更常见的具体声音词重新提炼）"
            val retryRaw = runCatching {
                AiChatService.generateStructuredText(
                    provider = provider,
                    model = modelId,
                    systemPrompt = prompt.extractKeywords,
                    userContent = retryInput,
                    disableThinking = !AppConfig.aloudSoundThinking,
                    temperature = 0.4,
                    requestTemplate = AiStoryboardConfig.storyboardRequestTemplate
                )
            }.getOrNull().orEmpty()
            val more = parseKeywords(retryRaw)
            val merged = (keywords.values.flatten() + more.values.flatten()).distinct()
            candidates = SoundLibraryIndex.searchByKeywords(merged, CANDIDATE_TOP_N)
            if (more.isNotEmpty()) keywords = keywords + more
        }
        if (candidates.isEmpty()) return emptyList()

        // 3) 音效选择
        val candidateText = candidates.mapIndexed { index, scored ->
            (index + 1).toString() + ". " + scored.entry.path
        }.joinToString("\n")
        val pickInput = buildPickInput(batch.range, segments, candidateText)
        val pickRaw = AiChatService.generateStructuredText(
            provider = provider,
            model = modelId,
            systemPrompt = prompt.pickEffects,
            userContent = pickInput,
            disableThinking = !AppConfig.aloudSoundThinking,
            temperature = 0.3,
            requestTemplate = AiStoryboardConfig.storyboardRequestTemplate
        )

        // 4) 校验：名字必须在候选集内，pos 必须能映射回真实段内偏移
        val validNames = candidates.map { it.entry.name }.toSet()
        return parsePicks(pickRaw, batch, validNames)
    }

    /** 背景音乐：整章一次，要求模型只返回一个文件名。 */
    private suspend fun pickBgm(
        provider: io.legado.app.ui.main.ai.AiProviderConfig,
        modelId: String,
        segments: List<String>,
        bgmNames: List<String>,
        prompt: Prompts
    ): String {
        val body = segments.joinToString("\n").take(BGM_TEXT_LIMIT)
        val list = bgmNames.joinToString("\n") { it }
        val userContent = body + "\n\n---\n\n当前可用的背景音乐文件列表如下请根据正文内容直接返回以下文件名其中一个：\n" + list
        val raw = AiChatService.generateStructuredText(
            provider = provider,
            model = modelId,
            systemPrompt = prompt.bgm,
            userContent = userContent,
            disableThinking = !AppConfig.aloudSoundThinking,
            temperature = 0.3,
            requestTemplate = AiStoryboardConfig.storyboardRequestTemplate
        )
        val cleaned = raw.trim()
            .removeSurrounding("\"")
            .removeSuffix(".mp3")
            .substringAfterLast('/')
            .trim()
        return bgmNames.firstOrNull { it == cleaned }
            ?: bgmNames.firstOrNull {
                cleaned.isNotBlank() && it.contains(cleaned, ignoreCase = true)
            }
            ?: ""
    }

    private fun parseKeywords(raw: String): Map<Int, List<String>> {
        if (raw.isBlank()) return emptyMap()
        val root = runCatching {
            GSON.fromJson(extractJson(raw), RawParas::class.java)
        }.getOrNull() ?: return emptyMap()
        return root.paras.orEmpty().mapNotNull { para ->
            val p = para.p ?: return@mapNotNull null
            val words = para.k.orEmpty()
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.length <= 3 }
            p to words
        }.toMap()
    }

    private fun parsePicks(
        raw: String,
        batch: Batch,
        validNames: Set<String>
    ): List<SoundCue> {
        if (raw.isBlank()) return emptyList()
        val root = runCatching {
            GSON.fromJson(extractJson(raw), RawPicks::class.java)
        }.getOrNull() ?: return emptyList()
        val count = batch.range.count()
        return root.picks.orEmpty().mapNotNull { pick ->
            val p = pick.p ?: return@mapNotNull null
            if (p < 1 || p > count) return@mapNotNull null
            val name = pick.name?.trim().orEmpty()
            if (name !in validNames) {
                AppLog.put("丢弃幻觉音效：" + name)
                return@mapNotNull null
            }
            val posMap = batch.posMaps.getOrNull(p - 1) ?: return@mapNotNull null
            val pos = pick.pos ?: 1
            val offset = posMap[pos]
                ?: posMap.entries.minByOrNull { kotlin.math.abs(it.key - pos) }?.value
                ?: 0
            SoundCue(
                segmentIndex = batch.range.first + (p - 1),
                effectName = name,
                offsetChars = offset,
                volume = 0.85f,
                reason = pick.reason?.trim().orEmpty()
            )
        }
    }

    /** 精挑阶段的输入：候选列表 + 每段带序号的正文与最多个数。 */
    private fun buildPickInput(range: IntRange, segments: List<String>, candidateText: String): String {
        val builder = StringBuilder()
        builder.append("【候选音效列表】\n").append(candidateText).append("\n\n【待分析正文】\n")
        range.forEachIndexed { offset, segmentIndex ->
            val raw = segments[segmentIndex]
            builder.append("第").append(offset + 1).append("段（").append(raw.length)
                .append("字，最多").append(keywordCountFor(raw.length)).append("个）\n")
            builder.append(numberSegment(raw).first).append("\n\n")
        }
        return builder.toString().trimEnd()
    }

    /** 关键词阶段的输入：标题写明每段要求的关键字个数。 */
    private fun buildExtractInput(range: IntRange, segments: List<String>): String {
        val builder = StringBuilder()
        range.forEachIndexed { offset, segmentIndex ->
            val raw = segments[segmentIndex]
            builder.append("第").append(offset + 1).append("段（").append(raw.length)
                .append("字，请给").append(keywordCountFor(raw.length)).append("个）\n")
            builder.append(numberSegment(raw).first).append("\n\n")
        }
        return builder.toString().trimEnd()
    }

    private fun keywordCountFor(length: Int): Int =
        (length / 30).coerceIn(MIN_KEYWORDS_PER_SEGMENT, MAX_KEYWORDS_PER_SEGMENT)

    /**
     * 给一段正文插位置序号：段首 [1]，每个句读符后递增。
     * 返回带序号的文本与「序号 → 段内字符偏移」映射。
     */
    private fun numberSegment(raw: String): Pair<String, Map<Int, Int>> {
        val builder = StringBuilder()
        val map = LinkedHashMap<Int, Int>()
        var index = 1
        map[1] = 0
        builder.append("[1]")
        raw.forEachIndexed { i, ch ->
            builder.append(ch)
            if (ch in PUNCTUATION) {
                index += 1
                map[index] = i + 1
                builder.append('[').append(index).append(']')
            }
        }
        val lastChar = raw.lastOrNull()
        if (lastChar == null || lastChar !in PUNCTUATION) {
            index += 1
            map[index] = raw.length
            builder.append('[').append(index).append(']')
        }
        return builder.toString() to map
    }

    /** 按字数分批，保证单次请求的正文长度可控（默认 480 字，与 Z 阅读的分析字数一致）。 */
    private fun List<String>.chunkedByChars(maxChars: Int): List<Batch> {
        val batches = mutableListOf<Batch>()
        var start = 0
        while (start < size) {
            var end = start
            var chars = 0
            while (end < size && (chars + this[end].length <= maxChars || end == start)) {
                chars += this[end].length
                end += 1
            }
            val range = start until end
            val slices = subList(start, end)
            val posMaps = slices.map { numberSegment(it).second }
            batches += Batch(range, buildExtractInput(range, this), posMaps)
            start = end
        }
        return batches
    }

    private class Batch(
        val range: IntRange,
        val text: String,
        val posMaps: List<Map<Int, Int>>
    )

    private fun extractJson(raw: String): String {
        var text = raw.trim()
        val fence = "```"
        if (text.startsWith(fence)) {
            text = text.removePrefix(fence + "json").removePrefix(fence)
            val close = text.lastIndexOf(fence)
            if (close >= 0) text = text.substring(0, close)
        }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else text.trim()
    }

    private fun cacheFile(bookUrl: String, chapterIndex: Int): File =
        File(cacheDir, bookHash(bookUrl) + "_" + chapterIndex + ".json")

    private fun bookHash(bookUrl: String): String =
        MessageDigest.getInstance("MD5").digest(bookUrl.toByteArray())
            .joinToString("") { byte -> String.format("%02x", byte) }

    private data class RawParas(val paras: List<RawPara>? = null)
    private data class RawPara(val p: Int? = null, val k: List<String>? = null)
    private data class RawPicks(val picks: List<RawPick>? = null)
    private data class RawPick(
        val p: Int? = null,
        val name: String? = null,
        val pos: Int? = null,
        val reason: String? = null
    )
}
