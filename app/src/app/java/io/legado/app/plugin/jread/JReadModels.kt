package io.legado.app.plugin.jread

import com.google.gson.annotations.SerializedName

/**
 * Z·阅读（JRead）语音插件模型。
 *
 * 字段与 jread_voice_plugins.json 一一对应，不自行增删语义：
 * 插件代码在本机 Rhino 中执行，[pluginId] 是引擎唯一标识（跨版本更新靠它匹配），
 * [userVars] 是用户在「设置变量」里填写的值，[defVars] 是插件声明的变量描述。
 */
data class JReadPlugin(
    val id: String,
    val pluginId: String,
    val name: String,
    val author: String? = null,
    /** 插件包里的 version 是字符串（如 "14-jread1"），按 Int 解析会让整包反序列化失败。 */
    val version: String = "1",
    val iconUrl: String? = null,
    val code: String,
    val defVars: Map<String, JReadVarSpec> = emptyMap(),
    val userVars: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val pitch: Float = 1f,
    val postSpeed: Float = 0f,
    val postVolume: Float = 0f,
    val postPitch: Float = 0f
) {
    /** 传给插件 getAudio 的 rate（0..100，50 为原速），叠加插件自带的语速系数。 */
    fun rateArg(baseRate: Int): Int = (baseRate * speed).toInt().coerceIn(0, 100)

    fun volumeArg(baseVolume: Int): Int = (baseVolume * volume).toInt().coerceIn(0, 100)

    fun pitchArg(basePitch: Int): Int = (basePitch * pitch).toInt().coerceIn(0, 100)
}

/**
 * 插件变量描述（PluginJS.vars 的每一项）。
 * [loginUrl] + [binding] 表示「登录」按钮：打开网页，登录后按 [binding] 提取 cookie。
 */
data class JReadVarSpec(
    val label: String? = null,
    val hint: String? = null,
    val loginUrl: String? = null,
    val binding: String? = null,
    val loginDesc: String? = null,
    val ua: String? = null,
    @SerializedName("default") val defaultValue: String? = null,
    val type: String? = null,
    val options: List<String>? = null
)

/** 音色条目：插件内 [id] 唯一，跨插件用 [pluginKey]（pluginId）区分。 */
data class JReadVoiceEntry(
    val pluginKey: String,
    val pluginName: String,
    val id: String,
    val name: String,
    val locale: String,
    /** 插件自带的性别标记（Male/Female 等），没有则为 null，交由上层推断。 */
    val gender: String? = null,
    /** 音色所属分组（Z·阅读 用分组当 locale 参数，如「女声」「方言」）。 */
    val group: String? = null
) {
    /** 对 legadoC 全局唯一的音色 id：插件内置引擎 + 音色的复合编码，与 AI 选角绑定格式一致。 */
    val globalId: String get() = "$pluginKey\n$id"
}

/** 导入/内置包格式：{format, version, plugins[]}。 */
data class JReadBundle(
    val format: String? = null,
    val version: Int = 1,
    val plugins: List<JReadPlugin> = emptyList()
)

/** 合成结果：字节 + 音频类型 + 采样率，播放侧据此落盘与变速。 */
data class JReadAudio(
    val bytes: ByteArray,
    val contentType: String,
    val sampleRate: Int
) {
    val isEmpty: Boolean get() = bytes.isEmpty()
}
