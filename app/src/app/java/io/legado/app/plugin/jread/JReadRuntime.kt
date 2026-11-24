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

    /** 音色表缓存：插件取音色可能触发 onLoadData 的网络请求，不能每次渲染都跑一遍。 */
    private val voiceCache = LinkedHashMap<String, List<JReadVoiceEntry>>()

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

    /**
     * 取全部音色。
     *
     * 与 Z·阅读 的三处差异必须照做，否则大量插件取不到音色：
     * 1. 先跑 [EditorJS.onLoadData]（微软系列音色表是运行时从网络/缓存加载的，初始为 {}）；
     * 2. `getLocales()` 返回的可能是**分组 key**（recommend/female/male…），不是语言码，
     *    需要按分组逐个调 `getVoices(group)` 再合并；
     * 3. 音色值可能是 `{name, icon}` 对象，也可能是字符串，两种都要解。
     */
    fun voices(
        plugin: JReadPlugin,
        locale: String = DEFAULT_LOCALE,
        activeVoice: String = "",
        forceRefresh: Boolean = false
    ): List<JReadVoiceEntry> = synchronized(lock) {
        if (!forceRefresh) {
            voiceCache[plugin.pluginId]?.let { return it }
        }
        val loaded = load(plugin, locale, activeVoice) ?: return emptyList()
        val editor = loaded.editorJs ?: return emptyList()
        val list = withContext { cx ->
            runCatching {
                (ScriptableObject.getProperty(editor, "onLoadData") as? Function)
                    ?.call(cx, loaded.scope, editor, emptyArray<Any?>())
            }.onFailure {
                AppLog.put("JRead onLoadData 失败：${plugin.name}\n${it.localizedMessage}")
            }
            loaded.pluginJs?.let { pluginJs ->
                runCatching {
                    (ScriptableObject.getProperty(pluginJs, "onLoad") as? Function)
                        ?.call(cx, loaded.scope, pluginJs, emptyArray<Any?>())
                }.onFailure {
                    AppLog.put("JRead onLoad 失败（可能缺变量）：${plugin.name}\n${it.localizedMessage}")
                }
            }
            val getVoices = ScriptableObject.getProperty(editor, "getVoices") as? Function
                ?: return@withContext emptyList()
            val groups = runCatching {
                scriptableKeys(ScriptableObject.getProperty(editor, "getLocales") as? Function, cx, loaded, editor)
            }.getOrDefault(emptyList()).ifEmpty { listOf(locale) }
            val collected = LinkedHashMap<String, JReadVoiceEntry>()
            groups.forEach { group ->
                val raw = runCatching {
                    getVoices.call(cx, loaded.scope, editor, arrayOf<Any?>(group))
                }.getOrElse {
                    AppLog.put("JRead 取音色失败：${plugin.name} / $group\n${it.localizedMessage}")
                    return@forEach
                }
                val source = raw as? Scriptable ?: return@forEach
                source.ids.forEach { id ->
                    val key = Context.toString(id)
                    val value = ScriptableObject.getProperty(source, key)
                    if (value == Scriptable.NOT_FOUND || value is Undefined) return@forEach
                    val (name, gender) = voiceNameAndGender(value)
                    if (name.isBlank()) return@forEach
                    val entry = JReadVoiceEntry(
                        pluginKey = plugin.pluginId,
                        pluginName = plugin.name,
                        id = key,
                        name = name,
                        locale = locale,
                        gender = gender,
                        group = group
                    )
                    collected.putIfAbsent(entry.globalId, entry)
                }
            }
            collected.values.toList()
        }
        voiceCache[plugin.pluginId] = list
        list
    }

    /** getLocales 既可能是数组也可能是分组对象，统一点成 key 列表。 */
    private fun scriptableKeys(
        function: Function?,
        cx: RhinoContext,
        loaded: Loaded,
        editor: Scriptable
    ): List<String> {
        val raw = function?.call(cx, loaded.scope, editor, emptyArray<Any?>()) ?: return emptyList()
        val scriptable = raw as? Scriptable ?: return emptyList()
        return if (scriptable is org.mozilla.javascript.NativeArray) {
            (0 until scriptable.length.toInt()).mapNotNull { index ->
                val item = ScriptableObject.getProperty(scriptable, index)
                if (item == Scriptable.NOT_FOUND || item is Undefined) null else Context.toString(item)
            }
        } else {
            scriptable.ids.map { Context.toString(it) }
        }
    }

    /** 音色值解包：`{name, icon}` 取 name/icon，字符串直接用；名称压平成单行并截断。 */
    private fun voiceNameAndGender(value: Any?): Pair<String, String?> {
        fun clean(text: String): String = text.replace(Regex("\\s+"), " ").trim()
        if (value is Scriptable) {
            fun read(field: String): String? {
                val item = ScriptableObject.getProperty(value, field)
                return if (item == Scriptable.NOT_FOUND || item is Undefined) null else Context.toString(item)
            }
            val name = (read("name") ?: read("label") ?: read("title")).orEmpty()
            val gender = read("icon") ?: read("gender")
            return clean(name) to gender?.trim()?.lowercase()?.takeIf { it == "male" || it == "female" }
        }
        return clean(Context.toString(value)) to null
    }

    fun onLoad(plugin: JReadPlugin, locale: String = DEFAULT_LOCALE, activeVoice: String = "") {
        synchronized(lock) {
            val loaded = load(plugin, locale, activeVoice) ?: return
            val pluginJs = loaded.pluginJs ?: return
            withContext { cx ->
                val function = ScriptableObject.getProperty(pluginJs, "onLoad") as? Function
                if (function != null) {
                    function.call(cx, loaded.scope, pluginJs, emptyArray<Any?>())
                }
            }
        }
    }

    fun onStop(plugin: JReadPlugin, locale: String = DEFAULT_LOCALE, activeVoice: String = "") {
        synchronized(lock) {
            val loaded = load(plugin, locale, activeVoice) ?: return
            val pluginJs = loaded.pluginJs ?: return
            withContext { cx ->
                val function = ScriptableObject.getProperty(pluginJs, "onStop") as? Function
                if (function != null) {
                    runCatching { function.call(cx, loaded.scope, pluginJs, emptyArray<Any?>()) }
                }
            }
        }
    }

    data class SynthesisResult(val audio: JReadAudio, val needDecode: Boolean)

    /**
     * 合成一段文本。
     *
     * 两条通道，与 Z·阅读 一致：
     * - 同步：`PluginJS.getAudio(text, locale, voice, rate, volume, pitch)` 直接返回音频字节；
     * - 异步：插件只实现 `getAudioV2(request, callback)`（WS/流式插件，如豆包 374/745），
     *   由 callback.write 多次回传分片，callback.close 结束。
     * 只实现 V2 的插件在同步通道下会报「缺少 getAudio」，所以必须先探测再走对应通道。
     */
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
        val rate = plugin.rateArg(baseRate)
        val volume = plugin.volumeArg(baseVolume)
        val pitch = plugin.pitchArg(basePitch)
        val getAudio = withContext { cx -> ScriptableObject.getProperty(pluginJs, "getAudio") as? Function }
        var bytes = ByteArray(0)
        var needV2 = getAudio == null
        if (getAudio != null) {
            val outcome = withContext { cx ->
                runCatching {
                    getAudio.call(cx, loaded.scope, pluginJs, arrayOf<Any?>(text, locale, voiceId, rate, volume, pitch))
                }
            }
            val result = outcome.getOrNull()
            if (result != null) {
                bytes = JReadTtsrv.bytesOf(result)
            }
            val failure = outcome.exceptionOrNull()
            if (bytes.isEmpty() && failure != null) {
                // Z·阅读 约定：同步通道不可用时会抛这个标记，此时转 V2 异步通道
                if (failure.message.orEmpty().contains(NO_GET_AUDIO_MARK)) {
                    needV2 = true
                } else {
                    throw failure
                }
            }
        }
        if (needV2) {
            bytes = synthesizeV2(loaded, pluginJs, text, locale, voiceId, rate, volume, pitch)
        }
        if (bytes.isEmpty()) throw IllegalStateException("插件未返回音频：${plugin.name} / $voiceId")
        JReadTtsrv.persistData(loaded.ttsrv, loaded.storage)
        JReadAudio(
            bytes = bytes,
            contentType = JReadAudioProbe.contentType(bytes),
            sampleRate = JReadAudioProbe.sampleRate(bytes)
        )
    }

    private const val NO_GET_AUDIO_MARK = "__JREAD_NO_GET_AUDIO__"

    /** 异步通道：getAudioV2(__jreadRequest, __jreadCallback)，等回调写完再收。 */
    private fun synthesizeV2(
        loaded: Loaded,
        pluginJs: Scriptable,
        text: String,
        locale: String,
        voiceId: String,
        rate: Int,
        volume: Int,
        pitch: Int
    ): ByteArray {
        val collector = AudioCollector()
        withContext { cx ->
            val getAudioV2 = ScriptableObject.getProperty(pluginJs, "getAudioV2") as? Function
                ?: throw IllegalStateException("插件既没有 getAudio 也没有 getAudioV2")
            val request = cx.newObject(loaded.scope)
            ScriptableObject.putProperty(request, "text", text)
            ScriptableObject.putProperty(request, "locale", locale)
            ScriptableObject.putProperty(request, "voice", voiceId)
            ScriptableObject.putProperty(request, "rate", rate)
            ScriptableObject.putProperty(request, "speed", rate)
            ScriptableObject.putProperty(request, "volume", volume)
            ScriptableObject.putProperty(request, "pitch", pitch)
            val dataCopy = cx.newObject(loaded.scope)
            val tts = ScriptableObject.getProperty(loaded.ttsrv, "tts") as? Scriptable
            val dataObj = tts?.let { ScriptableObject.getProperty(it, "data") as? Scriptable }
            dataObj?.ids?.forEach { id ->
                val key = Context.toString(id)
                val value = ScriptableObject.getProperty(dataObj, key)
                if (value != Scriptable.NOT_FOUND && value !is Undefined) {
                    ScriptableObject.putProperty(dataCopy, key, value)
                }
            }
            ScriptableObject.putProperty(request, "data", dataCopy)
            listOf("contextTexts", "clonePresetIndex").forEach { key ->
                val value = dataObj?.let { ScriptableObject.getProperty(it, key) }
                if (value != null && value != Scriptable.NOT_FOUND && value !is Undefined) {
                    ScriptableObject.putProperty(request, key, value)
                }
            }
            val callback = cx.newObject(loaded.scope)
            ScriptableObject.putProperty(callback, "write", callbackFunction(cx, loaded.scope, "write") { args ->
                collector.write(JReadTtsrv.bytesOf(args.getOrNull(0)))
                null
            })
            ScriptableObject.putProperty(callback, "close", callbackFunction(cx, loaded.scope, "close") {
                collector.finish(null)
                null
            })
            ScriptableObject.putProperty(callback, "error", callbackFunction(cx, loaded.scope, "error") { args ->
                collector.finish(args.joinToString(" ") { Context.toString(it) })
                null
            })
            getAudioV2.call(cx, loaded.scope, pluginJs, arrayOf(request, callback))
        }
        collector.await()
        collector.error?.let { throw IllegalStateException("插件回调报错：$it") }
        return collector.toByteArray()
    }

    private fun callbackFunction(
        cx: Context,
        scope: Scriptable,
        name: String,
        body: (Array<out Any?>) -> Any?
    ): BaseFunction = object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
        override fun getFunctionName(): String = name
        override fun call(
            cx: Context,
            scope: Scriptable,
            thisObj: Scriptable,
            args: Array<out Any?>
        ): Any = body(args) ?: Context.getUndefinedValue()
    }

    /** 收集 getAudioV2 分片；插件可能在自己的线程里 write，所以用独立锁 + volatile 完成标志。 */
    private class AudioCollector {
        private val buffer = java.io.ByteArrayOutputStream()
        private val monitor = Object()

        @Volatile
        var finished: Boolean = false
            private set

        @Volatile
        var error: String? = null
            private set

        fun write(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            synchronized(buffer) { buffer.write(bytes) }
        }

        fun finish(reason: String?) {
            error = reason
            finished = true
            synchronized(monitor) { monitor.notifyAll() }
        }

        fun await() {
            val deadline = System.currentTimeMillis() + 90_000
            synchronized(monitor) {
                while (!finished && System.currentTimeMillis() < deadline) {
                    monitor.wait(50)
                }
            }
            if (!finished) {
                throw IllegalStateException("插件合成超时（90s 未收到音频或完成回调）")
            }
        }

        fun toByteArray(): ByteArray = synchronized(buffer) { buffer.toByteArray() }
    }

    fun invalidate(pluginId: String) {
        synchronized(lock) {
            scopes.remove(pluginId)
            voiceCache.remove(pluginId)
        }
    }

    fun clear() {
        synchronized(lock) {
            scopes.clear()
            voiceCache.clear()
        }
    }

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
        fun connect(args: Array<out Any?>): Any {
            val url = args.getOrNull(0)?.let { Context.toString(it) }
                ?: throw IllegalArgumentException("Websocket: 缺少 url")
            val headers = LinkedHashMap<String, String>()
            (args.getOrNull(1) as? Scriptable)?.let { headerObj ->
                headerObj.ids.forEach { id ->
                    val key = Context.toString(id)
                    val value = ScriptableObject.getProperty(headerObj, key)
                    if (value != Scriptable.NOT_FOUND && value !is Undefined) {
                        headers[key] = Context.toString(value)
                    }
                }
            }
            return Context.javaToJS(JReadWebSocket(url, headers, scope), scope)
        }
        put("Websocket", bridge("Websocket") { args -> connect(args) })
        put("WebSocket", bridge("WebSocket") { args -> connect(args) })
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
