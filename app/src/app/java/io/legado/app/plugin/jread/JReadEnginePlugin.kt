package io.legado.app.plugin.jread

import io.legado.app.plugin.ReadAloudEnginePlugin
import io.legado.app.service.ReadAloudEngineType

/**
 * 「Z阅读插件引擎」在 legadoC 的注册入口。
 *
 * 语义是一个引擎：用户可以在这一个引擎里导入、更新、启停任意数量的
 * Z·阅读 语音插件；引擎自身的音色目录由 [JReadVoiceDirectory] 合并全部插件。
 * AI 选角（BookTtsCastingCoordinator）据此把角色绑定到 `插件/音色`。
 */
object JReadEnginePlugin : ReadAloudEnginePlugin {

    const val ENGINE_ID = "jread"

    override val engineId: String = ENGINE_ID

    override val engineLabel: String = "Z阅读插件引擎"

    override val serviceClass: Class<out io.legado.app.service.BaseReadAloudService> =
        JReadReadAloudService::class.java

    override val engineType: ReadAloudEngineType = ReadAloudEngineType.SCRIPT_TTS

    override val manageActivityClass = JReadPluginManageActivity::class.java

    override val supportsMultiRoleCasting: Boolean = true

    override val unavailableReason: String?
        get() = if (JReadPluginStore.enabled().isEmpty()) "未导入可用的语音插件" else null
}
