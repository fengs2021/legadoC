package io.legado.app.help.sound

import io.legado.app.constant.AppLog
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.tts.ReadAloudDebugLog
import io.legado.app.utils.GSON
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import splitties.init.appCtx
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
object SoundPackManager {

    private const val MANIFEST_ASSET = "sound_pack_manifest.json"
    private const val DOWNLOAD_CONCURRENCY = 4
    private const val TAG = "音效"
    /** 单个音效文件平均大小，仅用于估算剩余体积 */
    private const val AVERAGE_FILE_BYTES = 60_000L

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

    fun packDir(packId: String): File = File(AloudSoundPaths.packsDir(), packId)

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

    // ===================== 后台下载调度 =====================

    /** 下载跑在独立 scope 里：关闭对话框、切换页面、Activity 重建都不会中断。 */
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadJobs = ConcurrentHashMap<String, Job>()
    private val progressMap = ConcurrentHashMap<String, PackProgress>()

    data class PackProgress(
        val packId: String,
        val done: Int,
        val total: Int,
        val running: Boolean,
        val failed: Int = 0
    ) {
        val percent: Int get() = if (total <= 0) 0 else done * 100 / total
    }

    fun isRunning(packId: String): Boolean = downloadJobs[packId]?.isActive == true

    fun progress(packId: String): PackProgress? = progressMap[packId]

    /**
     * 启动整包下载（已在跑则忽略）。已存在的文件自动跳过，所以这是天然的断点续传：
     * 中断后再点一次只会补缺的部分。
     */
    fun start(packId: String) {
        val pack = pack(packId) ?: return
        if (isRunning(packId)) return
        ReadAloudDebugLog.add(TAG, "下载开始：" + pack.name + " 共 " + pack.total + " 个文件")
        downloadJobs[packId] = downloadScope.launch {
            val pending = pack.files.count { relative ->
                val file = localFile(pack, relative)
                !(file.isFile && file.length() > 0)
            }
            var done = pack.total - pending
            progressMap[packId] = PackProgress(packId, done, pack.total, true)
            var failed = 0
            val remaining = pack.files.filter { relative ->
                val file = localFile(pack, relative)
                !(file.isFile && file.length() > 0)
            }
            remaining.chunked(DOWNLOAD_CONCURRENCY).forEach { batch ->
                if (!isActive) return@launch
                val results = batch.map { relative ->
                    async { downloadOne(pack, relative) }
                }.awaitAll()
                failed += results.count { !it }
                done += batch.size
                progressMap[packId] = PackProgress(packId, done.coerceAtMost(pack.total), pack.total, true, failed)
            }
            progressMap[packId] = PackProgress(packId, done.coerceAtMost(pack.total), pack.total, false, failed)
            ReadAloudDebugLog.add(
                TAG,
                "下载完成：" + pack.name + " 成功 " + (pack.total - failed) + " / " + pack.total +
                    (if (failed > 0) "，失败 " + failed + " 个，可再次点击补下" else "")
            )
        }
    }

    fun cancel(packId: String) {
        downloadJobs.remove(packId)?.cancel()
        progressMap[packId]?.let { progressMap[packId] = it.copy(running = false) }
    }

    /** 把「已下载数」换算成体积估算，供界面提示下载规模。 */
    fun estimatedRemainingBytes(packId: String): Long {
        val pack = pack(packId) ?: return 0L
        val missing = pack.files.count { relative ->
            val file = localFile(pack, relative)
            !(file.isFile && file.length() > 0)
        }
        return missing * AVERAGE_FILE_BYTES
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
