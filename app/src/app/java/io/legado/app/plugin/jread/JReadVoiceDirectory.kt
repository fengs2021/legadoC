package io.legado.app.plugin.jread

import io.legado.app.constant.AppLog
import io.legado.app.plugin.TtsVoiceDirectory
import io.legado.app.plugin.TtsVoiceInfo

/**
 * AI 选角使用的发音人目录：把「Z阅读插件引擎」全部已启用插件的音色
 * 以 `pluginId\nvoiceId` 复合 id 暴露给 legadoC 的选角与播放路由。
 *
 * 目录按 [JReadPluginStore.revision] 失效重建；某个插件取音色抛异常时，
 * 该插件整体不出现并记录日志，不用其它插件的音色顶替。
 */
object JReadVoiceDirectory : TtsVoiceDirectory {

    @Volatile
    private var cachedRevision: Int = -1

    @Volatile
    private var cachedList: List<TtsVoiceInfo> = emptyList()

    @Volatile
    private var cachedIndex: Map<String, TtsVoiceInfo> = emptyMap()

    override fun listVoices(): List<TtsVoiceInfo> {
        ensure()
        return cachedList
    }

    override fun voiceById(id: String): TtsVoiceInfo? {
        ensure()
        return cachedIndex[id]
    }

    fun refresh() {
        cachedRevision = -1
        ensure()
    }

    private fun ensure() {
        val revision = JReadPluginStore.revision
        if (revision == cachedRevision && cachedList.isNotEmpty()) return
        synchronized(this) {
            if (revision == cachedRevision && cachedList.isNotEmpty()) return
            val voices = mutableListOf<TtsVoiceInfo>()
            JReadPluginStore.enabled().forEach { plugin ->
                val entries = runCatching { JReadRuntime.voices(plugin) }.getOrElse {
                    AppLog.put("JRead 读取插件音色失败：${plugin.name}\n${it.localizedMessage}", it)
                    emptyList()
                }
                entries.forEach { entry ->
                    voices += TtsVoiceInfo(
                        id = entry.globalId,
                        name = "${plugin.name} / ${entry.name}",
                        desc = null,
                        gender = JReadGender.infer(entry.name, entry.id),
                        locale = entry.locale
                    )
                }
            }
            cachedList = voices
            cachedIndex = voices.associateBy { it.id }
            cachedRevision = revision
            AppLog.put("JRead 音色目录已刷新：${voices.size} 个音色 / ${JReadPluginStore.enabled().size} 个插件")
        }
    }
}

/**
 * 性别推断：AI 选角 payload 只认 male / female / unknown。
 * 先看中文/英文显式词，再看 Edge 系常见发音人前缀，最后退 unknown（不猜）。
 */
internal object JReadGender {

    private val femaleTokens = listOf("女", "female", "woman", "girl", "小姐", "姐姐", "妹妹", "夫人")
    private val maleTokens = listOf("男", "male", "man", "boy", "先生", "哥哥", "弟弟", "大叔")

    private val femalePrefixes = listOf(
        "xiaoxiao", "xiaoyi", "xiaochen", "xiaohan", "xiaomeng", "xiaomo", "xiaorui", "xiaoshuang",
        "xiaoxuan", "xiaoyan", "xiaoyou", "xiaozhen", "xiaoqiu", "xiaosang", "xiaobei", "yan", "yating",
        "nuannuan", "jing", "jingya", "sakura", "nanami", "aoi", "mayu", "shiori", "emma", "ava", "aria",
        "jenny", "michelle", "serena", "clara", "elizabeth", "libby", "natasha", "sonia", "maisie"
    )

    private val malePrefixes = listOf(
        "yunxi", "yunyang", "yunjian", "yunfeng", "yunhao", "yunye", "yunze", "yunxia", "kangkang",
        "zhiwei", "jiawei", "wayne", "harry", "liam", "guy", "davis", "tony", "jason", "brandon",
        "christopher", "eric", "roger", "steffan", "thomas", "william", "keita", "daichi", "naoki"
    )

    fun infer(name: String, id: String): String {
        val text = (name + " " + id).lowercase()
        if (femaleTokens.any { text.contains(it) }) return "female"
        if (maleTokens.any { text.contains(it) }) return "male"
        if (femalePrefixes.any { text.contains(it) }) return "female"
        if (malePrefixes.any { text.contains(it) }) return "male"
        return "unknown"
    }
}
