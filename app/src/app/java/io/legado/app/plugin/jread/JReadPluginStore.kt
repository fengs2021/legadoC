package io.legado.app.plugin.jread

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import io.legado.app.constant.AppLog
import splitties.init.appCtx
import java.io.File

/**
 * Z·阅读 语音插件仓库（单一数据源）。
 *
 * 首次启动从 assets 导入内置插件包到应用私有目录，之后以私有文件为准：
 * 用户导入、更新、删除、启停、改变量都只落这个文件，[revision] 递增通知上层
 * （音色目录、引擎 UI）失效重建。插件代码本身不做任何裁剪或改写。
 */
object JReadPluginStore {

    private const val ASSET_BUNDLE = "jread/jread_voice_plugins.json"
    private const val FILE_NAME = "plugins.json"
    private const val ASSET_SEED_VERSION = 1

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val lock = Any()

    @Volatile
    var revision: Int = 0
        private set

    @Volatile
    private var cache: MutableList<JReadPlugin> = mutableListOf()

    private val dir: File
        get() = File(appCtx.filesDir, "jread").apply { mkdirs() }

    private val file: File
        get() = File(dir, FILE_NAME)

    private val metaFile: File
        get() = File(dir, "seed.json")

    fun init(context: Context) {
        synchronized(lock) {
            if (!file.exists()) {
                seedFromAssets(context)
            }
            cache = runCatching { readFile() }.getOrElse {
                AppLog.put("JRead 插件仓库读取失败，使用空表\n${it.localizedMessage}", it)
                mutableListOf()
            }
            revision++
        }
    }

    fun all(): List<JReadPlugin> = cache.toList()

    fun enabled(): List<JReadPlugin> = cache.filter { it.enabled }

    fun byPluginId(pluginId: String?): JReadPlugin? =
        pluginId?.let { id -> cache.firstOrNull { it.pluginId == id } }

    fun byVoiceGlobalId(globalVoiceId: String?): Pair<JReadPlugin, String>? {
        val raw = globalVoiceId ?: return null
        val index = raw.indexOf('\n')
        if (index <= 0) return null
        val pluginId = raw.substring(0, index)
        val voiceId = raw.substring(index + 1)
        val plugin = byPluginId(pluginId) ?: return null
        return plugin to voiceId
    }

    fun setEnabled(pluginId: String, enabled: Boolean) = mutate { list ->
        list.map { if (it.pluginId == pluginId) it.copy(enabled = enabled) else it }
    }

    fun setUserVars(pluginId: String, vars: Map<String, String>) = mutate { list ->
        list.map { if (it.pluginId == pluginId) it.copy(userVars = vars) else it }
    }

    fun setTuning(pluginId: String, speed: Float, volume: Float, pitch: Float) = mutate { list ->
        list.map {
            if (it.pluginId == pluginId) it.copy(speed = speed, volume = volume, pitch = pitch)
            else it
        }
    }

    fun remove(pluginId: String) = mutate { list -> list.filterNot { it.pluginId == pluginId } }

    /**
     * 导入插件包或单插件 JSON。同 [JReadPlugin.pluginId] 按版本号决定覆盖：
     * 大于等于现有版本即更新（用户明确导入即视为要更新），小于则拒绝并返回原因。
     */
    fun import(json: String): ImportResult {
        val trimmed = json.trim()
        val incoming = parseBundle(trimmed)
            ?: return ImportResult(false, "不是可识别的插件包（缺少 plugins 或 pluginId）")
        if (incoming.isEmpty()) return ImportResult(false, "插件包为空")
        var added = 0
        var updated = 0
        val rejected = mutableListOf<String>()
        mutate { list ->
            val result = list.toMutableList()
            incoming.forEach { plugin ->
                val index = result.indexOfFirst { it.pluginId == plugin.pluginId }
                if (index < 0) {
                    result += plugin.copy(enabled = true, userVars = plugin.userVars)
                    added++
                } else {
                    val existing = result[index]
                    if (plugin.version < existing.version) {
                        rejected += "${plugin.name}(v${plugin.version} < v${existing.version})"
                    } else {
                        result[index] = plugin.copy(
                            enabled = existing.enabled,
                            userVars = existing.userVars + plugin.userVars,
                            speed = existing.speed,
                            volume = existing.volume,
                            pitch = existing.pitch
                        )
                        updated++
                    }
                }
            }
            result
        }
        val message = buildString {
            append("新增 $added")
            if (updated > 0) append("，更新 $updated")
            if (rejected.isNotEmpty()) append("，跳过（版本更低）：${rejected.joinToString("、")}")
        }
        return ImportResult(true, message)
    }

    data class ImportResult(val success: Boolean, val message: String)

    private fun mutate(block: (List<JReadPlugin>) -> List<JReadPlugin>) {
        synchronized(lock) {
            cache = block(cache).toMutableList()
            writeFile()
            revision++
        }
    }

    private fun readFile(): MutableList<JReadPlugin> {
        val text = file.readText()
        if (text.isBlank()) return mutableListOf()
        return parseBundle(text)?.toMutableList() ?: mutableListOf()
    }

    private fun writeFile() {
        dir.mkdirs()
        file.writeText(gson.toJson(JReadBundle(format = "jread_voice_plugin_bundle", version = 2, plugins = cache)))
    }

    private fun seedFromAssets(context: Context) {
        val text = runCatching {
            context.assets.open(ASSET_BUNDLE).bufferedReader().use { it.readText() }
        }.getOrElse {
            AppLog.put("JRead 内置插件包缺失：$ASSET_BUNDLE", it)
            return
        }
        val parsed = parseBundle(text) ?: run {
            AppLog.put("JRead 内置插件包解析失败：$ASSET_BUNDLE")
            return
        }
        dir.mkdirs()
        file.writeText(gson.toJson(JReadBundle("jread_voice_plugin_bundle", 2, parsed)))
        metaFile.writeText(gson.toJson(mapOf("seedVersion" to ASSET_SEED_VERSION)))
        AppLog.put("JRead 内置插件已导入：${parsed.size} 个")
    }

    private fun parseBundle(text: String): List<JReadPlugin>? {
        val element = runCatching { JsonParser.parseString(text) }.getOrNull() ?: return null
        return runCatching {
            if (element.isJsonArray) {
                val array = element.asJsonArray
                array.mapNotNull { item ->
                    if (!item.isJsonObject) return@mapNotNull null
                    val obj = item.asJsonObject
                    if (!obj.has("code") || !obj.has("pluginId")) return@mapNotNull null
                    gson.fromJson(obj, JReadPlugin::class.java)
                }
            } else {
                val obj = element.asJsonObject
                if (obj.has("plugins")) {
                    gson.fromJson(obj, JReadBundle::class.java).plugins
                } else if (obj.has("code") && obj.has("pluginId")) {
                    listOf(gson.fromJson(obj, JReadPlugin::class.java))
                        .filterNotNull()
                } else {
                    null
                }
            }
        }.getOrNull()
    }
}
