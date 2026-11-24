package io.legado.app.plugin

import android.content.Context
import io.legado.app.plugin.jread.JReadEnginePlugin
import io.legado.app.plugin.jread.JReadPluginStore
import io.legado.app.plugin.jread.JReadVoiceDirectory

/**
 * 自用构建（app flavor）插件引导。
 *
 * 目前注册两项：
 * - 「Z阅读插件引擎」朗读引擎（一个引擎承载全部 Z·阅读 语音插件，可导入/更新）；
 * - AI 选角发音人目录（把该引擎的音色并入全书选角候选）。
 * 开源构建不编译本源集，主代码在空注册表下正常运行。
 */
object AppPlugins {

    fun init(context: Context) {
        JReadPluginStore.init(context)
        ReadAloudEngines.register(JReadEnginePlugin)
        TtsVoiceDirectories.register(JReadVoiceDirectory)
    }
}
