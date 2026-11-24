package io.legado.app.plugin.jread

import com.script.ScriptBindings
import com.script.rhino.RhinoContext
import com.script.rhino.RhinoScriptEngine
import io.legado.app.constant.AppLog
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/**
 * Z·阅读 语音插件运行时。
 *
 * 与 Z·阅读 原实现（JReadVoicePluginRuntime）对齐的三件事：
 * 1. `preparePluginCode` 的 10 条源码重写——这是第三方 TTS Server 插件能在
 *    Android Rhino 上跑的全部兼容层，顺序敏感，必须原样复刻；
 * 2. 作用域注入 `ttsrv`（33 方法 + 字段 + 子对象）与 console/logger/Buffer/线程/atob 等全局；
 * 3. 插件代码包进 IIFE 取 `PluginJS/EditorJS`，闭包存活、不重复求值。
 *
 * Rhino 非线程安全，所有执行串行化；一个插件一个作用域，LRU 4。
 */
object JReadRuntime {

    private const val MAX_SCOPES = 4
    private const val DEFAULT_LOCALE = "zh-CN"

    private val lock = Any()

    private class Loaded(
        val pluginId: String,
        val signature: Int,
        val scope: Scriptable,
        val ttsrv: Scriptable,
        val pluginJs: Scriptable?,
        val editorJs: Scriptable?,
        val storage: JReadStorage
    )

    private val scopes = LinkedHashMap<String, Loaded>()

    /** Z·阅读 的源码兼容重写；顺序敏感：先长后短，不可调换。 */
    fun preparePluginCode(raw: String): String {
        var code = raw
        if (code.startsWith("@js:")) code = code.substring(4)
        code = code.trim()
        code = code.replace("new java.lang.Thread", "new __jreadThread")
        code = code.replace("java.lang.Thread.sleep", "__jreadThreadSleep")
        code = code.replace("new android.widget.Switch(", "new JSwitch(")
        code = code.replace("new Switch(", "new JSwitch(")
        code = code.replace("new java.lang.Runnable(", "new __jreadRunnable(")
        code = code.replace("new Runnable(", "new __jreadRunnable(")
        code = code.replace("new android.text.TextWatcher(", "new __jreadTextWatcher(")
        code = code.replace("new TextWatcher(", "new __jreadTextWatcher(")
        return code
    }

    fun voices(plugin: JReadPlugin, locale: String = DEFAULT_LOCALE, activeVoice: String = ""): List<JReadVoiceEntry> =
        synchronized(lock) {
            val loaded = load(plugin, locale, activeVoice) ?: return emptyList()
            val editor = loaded.editorJs ?: return emptyList()
            withContext { cx ->
                val function = ScriptableObject.getProperty(editor, "getVoices") as? Function
                    ?: return@withContext emptyList()
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
                    JReadVoiceEntry(plugin.pluginId, plugin.name, key, Context.toString(value), locale)
                }
            }
        }

    fun onLoad(plugin: JReadPlugin, locale: String = DEFAULT_LOCALE, activeVoice: String = "") = synchronized(lock) {
        val loaded = load(plugin, locale, activeVoice) ?: return
        val pluginJs = loaded.pluginJs ?: return
        withContext { cx ->
            val function = ScriptableObject.getProperty(pluginJs, "onLoad") as? Function ?: return@withContext
            function.call(cx, loaded.scope, pluginJs, emptyArray<Any?>())
        }
    }

    fun onStop(plugin: JReadPlugin, locale: String = DEFAULT_LOCALE, activeVoice: String = "") = synchronized(lock) {
        val loaded = load(plugin, locale, activeVoice) ?: return
        val pluginJs = loaded.pluginJs ?: return
        withContext { cx ->
            val function = ScriptableObject.getProperty(pluginJs, "onStop") as? Function ?: return@withContext
            runCatching { function.call(cx, loaded.scope, pluginJs, emptyArray<Any?>()) }
        }
    }

    data class SynthesisResult(val audio: JReadAudio, val needDecode: Boolean)

    fun synthesize(
        plugin: JReadPlugin,
        text: String,
        voiceId: String,
        baseRate: Int = 50,
        baseVolume: Int = 50,
        basePitch: Int = 50,
        locale: String = DEFAULT_LOCALE
    ): JReadAudio = synchronized(lock) {
        val loaded = load(plugin, locale, voiceId) ?: throw IllegalStateException("插件不可用：${plugin.name}")
        val pluginJs = loaded.pluginJs ?: throw IllegalStateException("插件缺少 PluginJS：${plugin.name}")
        val function = withContext { cx ->
            ScriptableObject.getProperty(pluginJs, "getAudio") as? Function
        } ?: throw IllegalStateException("插件缺少 getAudio：${plugin.name}")
        val result = withContext { cx ->
            function.call(
                cx,
                loaded.scope,
                pluginJs,
                arrayOf<Any?>(
                    text,
                    locale,
                    voiceId,
                    plugin.rateArg(baseRate),
                    plugin.volumeArg(baseVolume),
                    plugin.pitchArg(basePitch)
                )
            )
        }
        val bytes = JReadTtsrv.bytesOf(result)
        if (bytes.isEmpty()) throw IllegalStateException("插件未返回音频：${plugin.name} / $voiceId")
        JReadTtsrv.persistData(loaded.ttsrv, loaded.storage)
        JReadAudio(
            bytes = bytes,
            contentType = JReadAudioProbe.contentType(bytes),
            sampleRate = JReadAudioProbe.sampleRate(bytes)
        )
    }

    fun invalidate(pluginId: String) = synchronized(lock) { scopes.remove(pluginId) }

    fun clear() = synchronized(lock) { scopes.clear() }

    private fun load(plugin: JReadPlugin, locale: String, activeVoice: String): Loaded? {
        val signature = plugin.code.hashCode() * 31 +
            plugin.userVars.hashCode() +
            locale.hashCode() * 17 +
            activeVoice.hashCode()
        scopes[plugin.pluginId]?.let { cached ->
            if (cached.signature == signature) return cached
            scopes.remove(plugin.pluginId)
        }
        val loaded = runCatching { create(plugin, locale, activeVoice, signature) }.getOrElse {
            AppLog.put("JRead 插件加载失败：${plugin.name}\n${it.localizedMessage}", it)
            null
        } ?: return null
        if (scopes.size >= MAX_SCOPES) {
            scopes.keys.firstOrNull()?.let { scopes.remove(it) }
        }
        scopes[plugin.pluginId] = loaded
        return loaded
    }

    private fun create(plugin: JReadPlugin, locale: String, activeVoice: String, signature: Int): Loaded {
        val bindings = ScriptBindings()
        val scope = RhinoScriptEngine.getRuntimeScope(bindings)
        val storage = JReadStorage(plugin.pluginId)
        val ttsrv = withContext { cx ->
            JReadTtsrv.install(cx, scope, plugin, storage, activeVoice, locale)
        }
        bindings["ttsrv"] = ttsrv
        withContext { cx -> installGlobals(cx, scope) }

        val code = preparePluginCode(plugin.code)
        val wrapped = buildString {
            append("(function(){\n")
            append(code)
            append("\n;return { p: (typeof PluginJS !== 'undefined' ? PluginJS : null),")
            append(" e: (typeof EditorJS !== 'undefined' ? EditorJS : null) };\n})()")
        }
        val result = RhinoScriptEngine.eval(wrapped, scope) as? Scriptable
            ?: throw IllegalStateException("插件代码未返回对象：${plugin.name}")
        val pluginJs = ScriptableObject.getProperty(result, "p").asScriptableOrNull()
        val editorJs = ScriptableObject.getProperty(result, "e").asScriptableOrNull()
        return Loaded(plugin.pluginId, signature, scope, ttsrv, pluginJs, editorJs, storage)
    }

    /**
     * 插件通用全局。与 Z·阅读 的 installPluginGlobals/installPluginCompatShims 对应：
     * 有真实实现的（console/logger/Buffer/atob/btoa/线程）给真实实现；
     * 依赖宿主 UI 的（JSwitch/UI 别名）给最小可调用壳，插件用到时不会 ReferenceError。
     */
    private fun installGlobals(cx: Context, scope: Scriptable) {
        val fnProto = ScriptableObject.getFunctionPrototype(scope)

        fun put(name: String, value: Any?) = ScriptableObject.putProperty(scope, name, value)

        fun bridge(name: String, body: (Array<out Any?>) -> Any?) = put(
            name,
            object : BaseFunction(scope, fnProto) {
                override fun getFunctionName(): String = name
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<out Any?>
                ): Any = body(args) ?: Context.getUndefinedValue()
            }
        )

        fun loggerFn(level: String) = bridge(level) { args ->
            AppLog.put("[JRead/$level] " + args.joinToString(" ") { Context.toString(it) })
            null
        }

        val console = cx.newObject(scope)
        listOf("log" to "d", "info" to "i", "warn" to "w", "error" to "e").forEach { (name, level) ->
            ScriptableObject.putProperty(console, name, loggerFn(level))
        }
        put("console", console)

        val logger = cx.newObject(scope)
        listOf("i", "d", "w", "e", "info", "debug", "warn", "error").forEach { name ->
            ScriptableObject.putProperty(logger, name, loggerFn(name))
        }
        put("logger", logger)

        val buffer = cx.newObject(scope)
        ScriptableObject.putProperty(buffer, "from", bridge("from") { args ->
            val value = args.getOrNull(0)
            Context.javaToJS(
                when (value) {
                    is String -> value.toByteArray(Charsets.UTF_8)
                    else -> JReadTtsrv.bytesOf(value)
                },
                scope
            )
        })
        put("Buffer", buffer)

        put("__jreadThread", bridge("__jreadThread") { args ->
            val runnable = args.getOrNull(0) as? Function
            if (runnable != null) {
                Thread { runCatching { runnable.call(cx, scope, scope, emptyArray<Any?>()) } }.start()
            }
            null
        })
        put("__jreadThreadSleep", bridge("__jreadThreadSleep") { args ->
            val ms = args.getOrNull(0)?.let { Context.toNumber(it).toLong() } ?: 0L
            if (ms in 1..60000) Thread.sleep(ms)
            null
        })
        put("__jreadRunnable", bridge("__jreadRunnable") { args -> args.getOrNull(0) })
        put("__jreadTextWatcher", bridge("__jreadTextWatcher") { null })
        put("JSwitch", bridge("JSwitch") { null })

        put("atob", bridge("atob") { args ->
            val raw = Context.toString(args.getOrNull(0) ?: "")
            String(runCatching { android.util.Base64.decode(raw, android.util.Base64.DEFAULT) }
                .getOrDefault(ByteArray(0)), Charsets.ISO_8859_1)
        })
        put("btoa", bridge("btoa") { args ->
            val raw = Context.toString(args.getOrNull(0) ?: "")
            android.util.Base64.encodeToString(raw.toByteArray(Charsets.ISO_8859_1), android.util.Base64.NO_WRAP)
        })
        put("Websocket", bridge("Websocket") { args -> null })
        put("WebSocket", bridge("WebSocket") { args -> null })
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
}
