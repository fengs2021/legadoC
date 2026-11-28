package io.legado.app.help.sound

import io.legado.app.constant.AppLog
import io.legado.app.utils.GSON
import splitties.init.appCtx
import java.io.File

/**
 * AI 朗读音效与背景音乐的唯一持久化入口。
 *
 * 全部书的配置存在单个 JSON 快照里：数据量按书计、每本只有若干曲目与规则，
 * 不需要为它引入 Room 迁移；写入走临时文件 + rename 保证原子性。
 */
object AloudSoundStore {

    private const val FILE_NAME = "aloud_sound.json"

    private val file get() = File(appCtx.filesDir, FILE_NAME)
    private val tempFile get() = File(appCtx.filesDir, "$FILE_NAME.tmp")

    @Volatile
    private var cache: MutableMap<String, BookAloudSoundConfig>? = null

    private fun snapshot(): MutableMap<String, BookAloudSoundConfig> {
        cache?.let { return it }
        val loaded = runCatching {
            if (!file.isFile) return@runCatching mutableMapOf()
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) return@runCatching mutableMapOf()
            val stored = GSON.fromJson(text, StoredSnapshot::class.java)
            stored?.books
                ?.filter { it.bookUrl.isNotBlank() }
                ?.associateByTo(mutableMapOf()) { it.bookUrl }
                ?: mutableMapOf()
        }.onFailure {
            AppLog.put("朗读音效配置读取失败，按空配置继续", it)
        }.getOrDefault(mutableMapOf())
        cache = loaded
        return loaded
    }

    private fun persist() {
        val data = snapshot()
        val payload = StoredSnapshot(version = 1, books = data.values.toList())
        runCatching {
            file.parentFile?.mkdirs()
            tempFile.writeText(GSON.toJson(payload), Charsets.UTF_8)
            if (!tempFile.renameTo(file)) {
                throw IllegalStateException("cannot atomically replace ${file.absolutePath}")
            }
        }.onFailure {
            AppLog.put("朗读音效配置保存失败", it)
        }
    }

    fun getConfig(bookUrl: String?): BookAloudSoundConfig {
        if (bookUrl.isNullOrBlank()) return BookAloudSoundConfig()
        return snapshot()[bookUrl] ?: BookAloudSoundConfig(bookUrl = bookUrl)
    }

    fun saveConfig(config: BookAloudSoundConfig) {
        if (config.bookUrl.isBlank()) return
        val data = snapshot()
        if (!config.hasBgm && !config.hasEffect
            && config.bgmTracks.isEmpty() && config.effectRules.isEmpty()
        ) {
            // 空配置不落盘，避免清理后残留空壳
            if (data.remove(config.bookUrl) != null) persist()
            return
        }
        data[config.bookUrl] = config
        persist()
    }

    fun clearConfig(bookUrl: String?) {
        if (bookUrl.isNullOrBlank()) return
        if (snapshot().remove(bookUrl) != null) persist()
    }

    /** 所有已配置的书，用于设置页统计与批量管理 */
    fun allConfigs(): List<BookAloudSoundConfig> = snapshot().values.toList()

    private data class StoredSnapshot(
        val version: Int = 1,
        val books: List<BookAloudSoundConfig> = emptyList()
    )
}
