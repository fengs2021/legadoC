package io.legado.app.help.sound

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.legado.app.constant.AppLog
import java.io.File

/**
 * 音效与背景音乐音频导入：把 SAF 选中的音频复制进应用私有目录。
 *
 * 必须复制而不是保存 content:// 授权：授权可能随重启/清理失效，
 * 而音效由 SoundPool 按文件路径加载，路径必须长期稳定。
 */
object AloudSoundImporter {

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "wav", "ogg", "m4a", "aac", "flac", "opus", "wma", "amr", "mid", "midi"
    )

    fun isAudioFile(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

    /** 导入单个音频，返回落地后的文件；失败返回 null。 */
    fun import(context: Context, bookUrl: String, uri: Uri): File? {
        val displayName = queryDisplayName(context, uri) ?: "audio_${System.currentTimeMillis()}"
        if (!isAudioFile(displayName)) return null
        return runCatching {
            val targetDir = directoryFor(bookUrl)
            val safeName = sanitizeFileName(displayName)
            val target = uniqueTarget(targetDir, safeName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            target.takeIf { it.isFile && it.length() > 0 }
        }.onFailure {
            AppLog.put("音频导入失败：$displayName", it)
        }.getOrNull()
    }

    fun directoryFor(bookUrl: String): File = AloudSoundPaths.bookDir(bookUrl)

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)

    private fun uniqueTarget(dir: File, name: String): File {
        var target = File(dir, name)
        if (!target.exists()) return target
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var index = 2
        while (target.exists()) {
            val candidate = if (ext.isEmpty()) "${base}_$index" else "${base}_$index.$ext"
            target = File(dir, candidate)
            index++
        }
        return target
    }

    /** 由文件名生成默认关键词：去掉扩展名、序号与常见音效前缀。 */
    fun defaultKeywords(fileName: String): String {
        var name = fileName.substringBeforeLast('.', fileName)
        name = name.replace(Regex("^[0-9]+[_.\\-\\s]*"), "")
        name = name.replace(Regex("[_\\-]+"), "|")
        name = name.replace(Regex("\\s+"), "|")
        return name.trim('|').takeIf { it.isNotBlank() } ?: fileName
    }
}
