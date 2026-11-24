package io.legado.app.plugin.jread

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookTtsVoiceBinding
import io.legado.app.help.tts.BookTtsAutomationConfig
import io.legado.app.model.ReadBook
import splitties.init.appCtx

/**
 * 「Z阅读插件引擎」自身的设置：默认音色。
 *
 * 选角绑定不在这里另存：AI 角色分配与手动绑定都写进 legadoC 既有的
 * book_tts_voice_bindings 表（engineId = jread，speakerId = pluginId\nvoiceId），
 * 本类只负责按当前书把绑定读出来交给合成层，避免出现第二套绑定真相。
 */
object JReadSettings {

    private const val PREF_NAME = "jread_pref"
    private const val KEY_VOICE = "jread_voice_id"

    private fun prefs() = appCtx.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)

    /** 引擎当前音色（复合 id：pluginId\nvoiceId）。 */
    var voiceId: String?
        get() = prefs().getString(KEY_VOICE, null)
        set(value) {
            prefs().edit().putString(KEY_VOICE, value).apply()
        }

    /**
     * 当前书的绑定音色：叙述者绑定优先，其次取该书任意一条 jread 绑定。
     * 查不到返回 null，由调用方回退到 [voiceId] 或引擎首个音色。
     */
    fun resolveBoundVoice(): String? {
        val book = ReadBook.book ?: return null
        val workKey = BookTtsAutomationConfig.workKeyOf(book.name, book.author)
        val dao = appDb.bookRoleDao
        val narrator = runCatching {
            dao.getBinding(workKey, BookTtsVoiceBinding.TargetType.NARRATOR, 0L)
        }.getOrNull()
        if (narrator?.engineId == JReadEnginePlugin.ENGINE_ID && narrator.speakerId.isNotBlank()) {
            return narrator.speakerId
        }
        val all = runCatching { dao.getBindings(workKey) }.getOrNull().orEmpty()
        return all.firstOrNull {
            it.engineId == JReadEnginePlugin.ENGINE_ID && it.speakerId.isNotBlank()
        }?.speakerId
    }
}
