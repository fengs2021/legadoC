package io.legado.app.help.sound

import android.os.Environment
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.help.tts.ReadAloudDebugLog
import splitties.init.appCtx
import java.io.File

/**
 * 音效与音乐的落盘位置。
 *
 * 默认放在公共下载目录（Download/yuedu/aloudSound），重装应用不会丢；
 * 该目录不可写时回退到应用私有目录，并在朗读日志里明说，避免"下完就没了"的错觉。
 */
object AloudSoundPaths {

    const val DEFAULT_RELATIVE = "Download/yuedu/aloudSound"
    private const val PROBE_NAME = ".write_probe"

    @Volatile
    private var resolved: File? = null

    /** 当前生效的根目录：用户自定义 → 公共下载目录 → 私有目录兜底。 */
    fun root(): File {
        resolved?.let { return it }
        val custom = AppConfig.aloudSoundRootDir
        val candidates = buildList {
            if (custom.isNotBlank()) add(File(custom))
            add(File(Environment.getExternalStorageDirectory(), DEFAULT_RELATIVE))
            add(File(appCtx.filesDir, "aloudSound"))
        }
        val picked = candidates.firstOrNull { writable(it) } ?: candidates.last()
        picked.mkdirs()
        resolved = picked
        ReadAloudDebugLog.add("音效", "存储目录：" + picked.absolutePath)
        return picked
    }

    /** 目录一旦切换需要重新解析。 */
    fun invalidate() {
        resolved = null
    }

    fun packsDir(): File = File(root(), "packs").apply { mkdirs() }
    fun timelinesDir(): File = File(root(), "timelines").apply { mkdirs() }
    fun libraryDir(): File = File(root(), "lib").apply { mkdirs() }
    fun promptFile(): File = File(root(), "prompts.json")
    fun bookDir(bookUrl: String): File =
        File(root(), "book/" + bookUrl.hashCode().toUInt().toString(16)).apply { mkdirs() }

    private fun writable(dir: File): Boolean = runCatching {
        dir.mkdirs()
        if (!dir.isDirectory) return@runCatching false
        val probe = File(dir, PROBE_NAME)
        probe.writeText("1")
        probe.delete()
        true
    }.getOrDefault(false)

    /**
     * 把旧私有目录里已下载的内容搬到当前根目录，只补缺、不覆盖。
     * 首次切换到公共目录时调用一次，避免用户重新下载已有素材。
     */
    fun migrateFromPrivate() {
        val legacy = File(appCtx.filesDir, "aloudSound")
        if (!legacy.isDirectory) return
        val target = root()
        if (target.absolutePath == legacy.absolutePath) return
        runCatching {
            var copied = 0
            legacy.walkTopDown().filter { it.isFile }.forEach { src ->
                val destination = File(target, src.relativeTo(legacy).path)
                if (destination.exists()) return@forEach
                destination.parentFile?.mkdirs()
                runCatching {
                    src.copyTo(destination, overwrite = false)
                    copied += 1
                }
            }
            if (copied > 0) {
                ReadAloudDebugLog.add("音效", "已迁移 " + copied + " 个旧文件到 " + target.absolutePath)
            }
        }.onFailure { AppLog.put("音效目录迁移失败", it) }
    }
}
