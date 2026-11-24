package io.legado.app.plugin.jread

import com.script.ScriptBindings
import com.script.rhino.RhinoContext
import com.script.rhino.RhinoScriptEngine
import io.legado.app.constant.AppLog
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/**
 * Z·阅读 语音插件运行时。
 *
 * 每个插件在自己的 Rhino 作用域里执行，作用域里注入 `ttsrv` 与本机 java.* 包
 * （scope 的原型是 initStandardObjects，插件用到的 java.nio.ByteBuffer 等可直接调用）。
 * 插件代码被包进一层 IIFE，因此 `let PluginJS/EditorJS` 这类块级声明也能取到，
 * 并且闭包保持存活——不被重新求值。
 *
 * 一个插件一个作用域并做 LRU 缓存；Rhino 不是线程安全的，[lock] 串行化所有调用。
 */
object JReadRuntime {

    private const val MAX_SCOPES = 4

    private val lock = Any()

    private class Loaded(
        val pluginId: String,
        val signature: Int,
        val bindings: ScriptBindings,
        val scope: Scriptable,
        val ttsrv: Scriptable,
        val pluginJs: Scriptable?,
        val editorJs: Scriptable?,
        val storage: JReadStorage
    ) {
        val hasPlugin: Boolean get() = pluginJs != null
    }

    private val scopes = LinkedHashMap<String, Loaded>()

    /**
     * 取全部音色。必须先能取到 EditorJS.getVoices；插件没实现时返回空表，
     * 由上层把该插件标为「无音色」而不是伪造音色。
     */
    fun voices(plugin: JReadPlugin, locale: String = "zh-CN"): List<JReadVoiceEntry> = synchronized(lock) {
        val loaded = load(plugin) ?: return emptyList()
        val editor = loaded.editorJs ?: return emptyList()
        withContext { cx ->
            val function = ScriptableObject.getProperty(editor, "getVoices") as? Function ?: return@withContext emptyList()
            val raw = runCatching {
                function.call(cx, loaded.scope, editor, arrayOf<Any?>(locale))
            }.getOrElse {
                AppLog.put("JRead 取音色失败：${plugin.name}\n${it.localizedMessage}", it)
                return@withContext emptyList()
            }
            val source = raw as? Scriptable ?: return@withContext emptyList()
            source.ids.mapNotNull { id ->
                val key = Context.toString(id)
                val value = ScriptableObject.getProperty(source, key)
                if (value == Scriptable.NOT_FOUND || value is Undefined) return@mapNotNull null
                JReadVoiceEntry(
                    pluginKey = plugin.pluginId,
                    pluginName = plugin.name,
                    id = key,
                    name = Context.toString(value),
                    locale = locale
                )
            }
        }
    }

    /** 可选：插件 onLoad 自检（缺 Cookie / 缺密钥时插件会抛异常，这里原样抛给调用方）。 */
    fun onLoad(plugin: JReadPlugin) = synchronized(lock) {
        val loaded = load(plugin) ?: return
        val pluginJs = loaded.pluginJs ?: return
        withContext { cx ->
            val function = ScriptableObject.getProperty(pluginJs, "onLoad") as? Function ?: return@withContext
            function.call(cx, loaded.scope, pluginJs, emptyArray<Any?>())
        }
    }

    /**
     * 合成一段文本。返回插件的原始音频字节（WAV/MP3/AAC…），不猜测、不转码；
     * 采样率与 contentType 供播放侧决定落盘后缀与变速参数。
     */
    fun synthesize(
        plugin: JReadPlugin,
        text: String,
        voiceId: String,
        baseRate: Int,
        baseVolume: Int,
        basePitch: Int
    ): JReadAudio = synchronized(lock) {
        val loaded = load(plugin) ?: throw IllegalStateException("插件不可用：${plugin.name}")
        val pluginJs = loaded.pluginJs ?: throw IllegalStateException("插件缺少 PluginJS：${plugin.name}")
        val result = withContext { cx ->
            val function = ScriptableObject.getProperty(pluginJs, "getAudio") as? Function
                ?: throw IllegalStateException("插件缺少 getAudio：${plugin.name}")
            function.call(
                cx,
                loaded.scope,
                pluginJs,
                arrayOf<Any?>(
                    text,
                    "zh-CN",
                    voiceId,
                    plugin.rateArg(baseRate),
                    plugin.volumeArg(baseVolume),
                    plugin.pitchArg(basePitch)
                )
            )
        }
        val bytes = JReadTtsrv.bytesOf(result)
        if (bytes.isEmpty()) {
            throw IllegalStateException("插件未返回音频数据：${plugin.name} / $voiceId")
        }
        JReadTtsrv.persistData(loaded.ttsrv, loaded.storage)
        JReadAudio(
            bytes = bytes,
            contentType = JReadAudioProbe.contentType(bytes),
            sampleRate = JReadAudioProbe.sampleRate(bytes)
        )
    }

    /** 变量或代码变更后让作用域失效。 */
    fun invalidate(pluginId: String) = synchronized(lock) {
        scopes.remove(pluginId)
    }

    fun clear() = synchronized(lock) {
        scopes.clear()
    }

    private fun load(plugin: JReadPlugin): Loaded? {
        val signature = plugin.code.hashCode() * 31 + plugin.userVars.hashCode()
        scopes[plugin.pluginId]?.let { cached ->
            if (cached.signature == signature) return cached
            scopes.remove(plugin.pluginId)
        }
        val loaded = runCatching { create(plugin, signature) }.getOrElse {
            AppLog.put("JRead 插件加载失败：${plugin.name}\n${it.localizedMessage}", it)
            null
        } ?: return null
        if (scopes.size >= MAX_SCOPES) {
            val oldest = scopes.keys.firstOrNull() ?: return loaded
            scopes.remove(oldest)
        }
        scopes[plugin.pluginId] = loaded
        return loaded
    }

    private fun create(plugin: JReadPlugin, signature: Int): Loaded {
        val bindings = ScriptBindings()
        val scope = RhinoScriptEngine.getRuntimeScope(bindings)
        val storage = JReadStorage(plugin.pluginId)
        val ttsrv = withContext { cx -> JReadTtsrv.install(cx, scope, plugin, storage) }
        bindings["ttsrv"] = ttsrv
        bindings["console"] = ConsoleBridge

        val wrapped = buildString {
            append("(function(){\n")
            append(plugin.code)
            append("\n;return { p: (typeof PluginJS !== 'undefined' ? PluginJS : null),")
            append(" e: (typeof EditorJS !== 'undefined' ? EditorJS : null) };\n})()")
        }
        val result = RhinoScriptEngine.eval(wrapped, scope) as? Scriptable
            ?: throw IllegalStateException("插件代码未返回对象：${plugin.name}")
        val pluginJs = ScriptableObject.getProperty(result, "p").asScriptableOrNull()
        val editorJs = ScriptableObject.getProperty(result, "e").asScriptableOrNull()
        return Loaded(plugin.pluginId, signature, bindings, scope, ttsrv, pluginJs, editorJs, storage)
    }

    private fun Any?.asScriptableOrNull(): Scriptable? =
        if (this is Scriptable && this !is Undefined) this else null

    private inline fun <T> withContext(block: (RhinoContext) -> T): T {
        val cx = Context.enter() as RhinoContext
        cx.allowScriptRun = true
        try {
            return block(cx)
        } finally {
            Context.exit()
        }
    }

    /** 插件里的 console.log 统一进 AppLog，避免无声失败。 */
    private object ConsoleBridge : ScriptableObject() {
        override fun getClassName(): String = "Console"

        override fun get(name: String, start: Scriptable?): Any? = when (name) {
            "log", "info", "warn", "error" -> object : org.mozilla.javascript.BaseFunction(
                this@ConsoleBridge,
                ScriptableObject.getFunctionPrototype(this@ConsoleBridge)
            ) {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<out Any?>
                ): Any {
                    AppLog.put("[JRead] " + args.joinToString(" ") { Context.toString(it) })
                    return Context.getUndefinedValue()
                }
            }
            else -> super.get(name, start)
        }
    }
}
