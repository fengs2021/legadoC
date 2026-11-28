package io.legado.app.help.sound

import io.legado.app.constant.AppLog
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import splitties.init.appCtx
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * 在线音效库：Z 阅读使用的那套中文音效素材（音效名即文件名）。
 *
 * 索引随 APK 内置（assets/sound_library_index.json），音频按需从素材仓库下载
 * 并缓存到私有目录；一次下载之后与本地音效完全一致，朗读触发零延迟。
 */
object OnlineSoundLibrary {

    private const val INDEX_ASSET = "sound_library_index.json"

    @Volatile
    private var loaded: LibraryIndex? = null

    data class Entry(
        /** 库内文件名，例如「下雨音效.mp3」 */
        val fileName: String,
        /** 去掉扩展名与常见后缀的展示名 */
        val displayName: String,
        /** 由文件名推导的匹配关键词 */
        val keywords: String
    )

    private data class LibraryIndex(
        val version: Int = 1,
        val base: String = "",
        val dir: String = "",
        val names: List<String> = emptyList()
    )

    private val cacheDir: File
        get() = File(appCtx.filesDir, "aloudSound/lib").apply { mkdirs() }

    private fun index(): LibraryIndex {
        loaded?.let { return it }
        val parsed = runCatching {
            appCtx.assets.open(INDEX_ASSET).use { input ->
                GSON.fromJson(input.reader(Charsets.UTF_8), LibraryIndex::class.java)
            }
        }.onFailure {
            AppLog.put("在线音效库索引读取失败", it)
        }.getOrNull() ?: LibraryIndex()
        loaded = parsed
        return parsed
    }

    val isAvailable: Boolean
        get() = index().names.isNotEmpty()

    val totalCount: Int
        get() = index().names.size

    /** 全量条目（首次调用会解析索引，之后走内存缓存）。 */
    fun allEntries(): List<Entry> = index().names.map { it.toEntry() }

    /** 按关键词搜索：匹配音效名，忽略「音效」这类无信息后缀。 */
    fun search(keyword: String?, limit: Int = 200): List<Entry> {
        val all = allEntries()
        val key = keyword.orEmpty().trim()
        if (key.isBlank()) return all.take(limit)
        val tokens = key.split(' ', '，', ',', '|').map { it.trim() }.filter { it.isNotEmpty() }
        return all.asSequence()
            .filter { entry -> tokens.all { entry.displayName.contains(it, ignoreCase = true) } }
            .take(limit)
            .toList()
    }

    /** 已缓存的本地文件；未下载返回 null。 */
    fun cachedFile(fileName: String): File? {
        val file = File(cacheDir, cacheName(fileName))
        return file.takeIf { it.isFile && it.length() > 0 }
    }

    /** 下载（或直接命中缓存）。失败返回 null，不抛异常给调用方。 */
    suspend fun download(fileName: String): File? = withContext(Dispatchers.IO) {
        cachedFile(fileName)?.let { return@withContext it }
        val idx = index()
        if (idx.base.isBlank() || idx.names.isEmpty()) return@withContext null
        val url = idx.base + idx.dir + "/" + encodePathSegment(fileName)
        runCatching {
            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLog.put("音效下载失败(${response.code})：$fileName")
                    return@use null
                }
                val body = response.body ?: return@use null
                val target = File(cacheDir, cacheName(fileName))
                val temp = File(cacheDir, "${cacheName(fileName)}.tmp")
                body.byteStream().use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                if (!temp.renameTo(target)) {
                    temp.delete()
                    return@use null
                }
                target
            }
        }.onFailure {
            AppLog.put("音效下载异常：$fileName", it)
        }.getOrNull()
    }

    /** 音效原始下载地址，供试听直接流式播放。 */
    fun remoteUrl(fileName: String): String? {
        val idx = index()
        if (idx.base.isBlank() || idx.dir.isBlank()) return null
        return idx.base + idx.dir + "/" + encodePathSegment(fileName)
    }

    private fun encodePathSegment(name: String): String =
        URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    private fun cacheName(fileName: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(fileName.toByteArray())
        return digest.joinToString("") { "%02x".format(it) } + ".mp3"
    }

    private fun String.toEntry(): Entry {
        val display = this.substringBeforeLast('.', this)
            .removeSuffix("音效")
            .ifBlank { this }
        return Entry(
            fileName = this,
            displayName = display,
            keywords = display
        )
    }
}
