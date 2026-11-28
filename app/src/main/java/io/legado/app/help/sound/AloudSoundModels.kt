package io.legado.app.help.sound

import com.google.gson.annotations.SerializedName

/**
 * AI 朗读音效规则：朗读文本命中 [pattern] 时播放 [filePath] 指向的音频。
 *
 * 一条规则只描述「什么时候响、响什么」，不关心引擎；播放由 [SoundEffectPlayer] 负责。
 */
data class SoundEffectRule(
    @SerializedName("id")
    val id: String = "",
    /** 规则展示名，为空时用文件名兜底 */
    @SerializedName("name")
    val name: String = "",
    /** 匹配内容：多个关键词用 | 分隔；[isRegex] 为真时按正则解释 */
    @SerializedName("pattern")
    val pattern: String = "",
    @SerializedName("isRegex")
    val isRegex: Boolean = false,
    /** 本地绝对路径或 http(s) 地址；在线音效库条目为空 */
    @SerializedName("filePath")
    val filePath: String = "",
    /** 在线音效库内文件名；非空表示来自内置音效库，播放前按需下载缓存 */
    @SerializedName("libraryName")
    val libraryName: String = "",
    /** 0f..1f，最终再乘全局音效音量 */
    @SerializedName("volume")
    val volume: Float = 1f,
    /** 同一规则的最小触发间隔，避免一句话里反复触发 */
    @SerializedName("cooldownMs")
    val cooldownMs: Long = 1500L,
    @SerializedName("enabled")
    val enabled: Boolean = true
) {

    val isLibrary: Boolean
        get() = libraryName.isNotBlank()

    /** 规则是否可用：必须有匹配式，并且有本地文件或在线库条目 */
    val isUsable: Boolean
        get() = enabled && pattern.isNotBlank()
            && (filePath.isNotBlank() || libraryName.isNotBlank())
}

/**
 * 背景音乐曲目。整本书共用一组曲目，按章节推进顺序播放。
 */
data class BgmTrack(
    @SerializedName("id")
    val id: String = "",
    @SerializedName("name")
    val name: String = "",
    /** 本地绝对路径或 http(s) 地址 */
    @SerializedName("filePath")
    val filePath: String = "",
    /** 0f..1f，最终再乘全局音乐音量 */
    @SerializedName("volume")
    val volume: Float = 1f,
    /** 单曲循环；关闭时播完自动下一首 */
    @SerializedName("loop")
    val loop: Boolean = false,
    @SerializedName("enabled")
    val enabled: Boolean = true
) {

    val isUsable: Boolean
        get() = enabled && filePath.isNotBlank()
}

/**
 * 一本书的音效与背景音乐配置。
 *
 * 只按 bookUrl 关联，不写进书籍实体，避免 Room 迁移；曲目与规则都随书独立。
 */
data class BookAloudSoundConfig(
    @SerializedName("bookUrl")
    val bookUrl: String = "",
    /** 本书启用的背景音乐曲目；为空表示本书不播背景音乐 */
    @SerializedName("bgmTracks")
    val bgmTracks: List<BgmTrack> = emptyList(),
    /** 本书启用的音效规则；为空表示本书不播音效 */
    @SerializedName("effectRules")
    val effectRules: List<SoundEffectRule> = emptyList()
) {

    val hasBgm: Boolean
        get() = bgmTracks.any { it.isUsable }

    val hasEffect: Boolean
        get() = effectRules.any { it.isUsable }
}
