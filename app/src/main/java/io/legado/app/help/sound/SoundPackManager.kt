package io.legado.app.help.sound

import io.legado.app.constant.AppLog
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import splitties.init.appCtx
import java.io.File
import java.net.URLEncoder

/**
 * 音效与音乐整包下载。
 *
 * 对齐 Z 阅读的做法：先把整包素材下到本地，朗读时只按名字取本地文件，
 * 不在朗读过程中联网；因此下载完成度与播放稳定性完全解耦。
 *
 * 清单随 APK 内置（assets/sound_pack_manifest.json），包内只存相对路径，
 * 落地路径 = filesDir/aloudSound/packs/<packId>/<相对路径>。
 */
object SoundPackManager {

    private const val MANIFEST_ASSET = "sound_pack_manifest.json"
    private const val DOWNLOAD_CONCURRENCY = 4

    data class Pack(
        val id: String,
        val name: String,
        val base: String,
        val dir: String,
        val files: List<String>
    ) {
        val total: Int get() = files.size
    }

    data class PackState(
        val pack: Pack,
        val downloaded: Int
    ) {
        val total: Int get() = pack.total
        val finished: Boolean get() = total > 0 && downloaded >= total
        val progressPercent: Int
            get() = if (total <= 0) 0 else (downloaded * 100 / total)
    }

    @Volatile
    private var cachedPacks: List<Pack>? = null

    fun packs(): List<Pack> {
        cachedPacks?.let { return it }
        val parsed = runCatching {
            appCtx.assets.open(MANIFEST_ASSET).use { input ->
                val root = GSON.fromJson(input.reader(Charsets.UTF_8), Manifest::class.java)
                root?.packs.orEmpty().map { raw ->
                    Pack(
                        id = raw.id,
                        name = raw.name,
                        base = raw.base,
                        dir = raw.dir,
                        files = raw.files.orEmpty()
                    )
                }
            }
        }.onFailure {
            AppLog.put("音效包清单读取失败", it)
        }.getOrNull().orEmpty()
        cachedPacks = parsed
        return parsed
    }

    fun pack(id: String): Pack? = packs().firstOrNull { it.id == id }

    fun packDir(packId: String): File =
        File(File(appCtx.filesDir, "aloudSound/packs"), packId)

    fun localFile(pack: Pack, relativePath: String): File =
        File(packDir(pack.id), relativePath)

    fun state(packId: String): PackState? {
        val pack = pack(packId) ?: return null
        val downloaded = pack.files.count { relative ->
            localFile(pack, relative).let { it.isFile && it.length() > 0 }
        }
        return PackState(pack, downloaded)
    }

    fun states(): List<PackState> = packs().mapNotNull { state(it.id) }

    /** 按名字在已下载的包里查找音频；返回第一个命中的本地文件。 */
    fun findByName(name: String): File? {
        val target = name.trim()
        if (target.isEmpty()) return null
        packs().forEach { pack ->
            val hit = pack.files.firstOrNull { relative ->
                val fileName = relative.substringAfterLast('/')
                val bare = fileName.substringBeforeLast('.', fileName)
                bare == target || fileName == target
            }
            if (hit != null) {
                val file = localFile(pack, hit)
                if (file.isFile && file.length() > 0) return file
            }
        }
        return null
    }

    /**
     * 下载整包。已存在的文件跳过，因此可反复点击续传。
     * [onProgress] 回调 (已完成数, 总数)，在 IO 线程触发。
     */
    suspend fun download(
        packId: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<Int> = withContext(Dispatchers.IO) {
        val pack = pack(packId) ?: return@withContext Result.failure(IllegalStateException("未知音效包：$packId"))
        val pending = pack.files.filter { relative ->
            val file = localFile(pack, relative)
            !(file.isFile && file.length() > 0)
        }
        if (pending.isEmpty()) {
            onProgress(pack.total, pack.total)
            return@withContext Result.success(0)
        }
        var done = pack.total - pending.size
        var failed = 0
        onProgress(done, pack.total)
        coroutineScope {
            pending.chunked(DOWNLOAD_CONCURRENCY).forEach { batch ->
                if (!isActive) return@coroutineScope
                val results = batch.map { relative ->
                    async { downloadOne(pack, relative) }
                }.awaitAll()
                failed += results.count { !it }
                done += batch.size
                onProgress(done.coerceAtMost(pack.total), pack.total)
            }
        }
        if (failed > 0) {
            AppLog.put("音效包 $packId 有 $failed 个文件下载失败，可再次点击续传")
        }
        Result.success(failed)
    }

    private fun downloadOne(pack: Pack, relative: String): Boolean {
        val target = localFile(pack, relative)
        if (target.isFile && target.length() > 0) return true
        val url = pack.base + encodePath(pack.dir + "/" + relative)
        return runCatching {
            target.parentFile?.mkdirs()
            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLog.put("音效下载失败(${response.code})：$relative")
                    return@use false
                }
                val body = response.body ?: return@use false
                val temp = File(target.parentFile, "${target.name}.tmp")
                body.byteStream().use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                if (temp.length() <= 0) {
                    temp.delete()
                    return@use false
                }
                if (!temp.renameTo(target)) {
                    temp.delete()
                    return@use false
                }
                true
            }
        }.onFailure {
            AppLog.put("音效下载异常：$relative", it)
        }.getOrDefault(false)
    }

    /** 路径逐段百分号编码，保留分隔符。 */
    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }

    private data class Manifest(
        val version: Int = 2,
        val packs: List<RawPack>? = null
    )

    private data class RawPack(
        val id: String = "",
        val name: String = "",
        val base: String = "",
        val dir: String = "",
        val count: Int = 0,
        val files: List<String>? = null
    )
}
