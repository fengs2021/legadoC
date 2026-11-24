package io.legado.app.plugin.jread

import io.legado.app.constant.AppLog
import splitties.init.appCtx
import java.io.File

/**
 * 单个插件的私有键值存储（对应插件里的 `ttsrv.tts.data`）。
 * 每个插件一个文件，互不可见；损坏时清空重建而不是静默保留半截数据。
 */
class JReadStorage(private val pluginId: String) {

    private val gson = com.google.gson.GsonBuilder().disableHtmlEscaping().create()

    private val file: File
        get() = File(appCtx.filesDir, "jread/data").apply { mkdirs() }
            .let { File(it, "${pluginId.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json") }

    fun entries(): Map<String, String> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            val type = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
            gson.fromJson<Map<String, String>>(file.readText(), type) ?: emptyMap()
        }.getOrElse {
            AppLog.put("JRead 插件数据损坏，已重置：$pluginId\n${it.localizedMessage}", it)
            file.delete()
            emptyMap()
        }
    }

    fun replaceAll(values: Map<String, String>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(values))
        }.onFailure {
            AppLog.put("JRead 插件数据写入失败：$pluginId\n${it.localizedMessage}", it)
        }
    }
}
