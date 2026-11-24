package io.legado.app.plugin.jread

import android.util.Base64
import io.legado.app.constant.AppLog
import io.legado.app.help.http.okHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 把 Z·阅读 插件运行时对象 `ttsrv` 装进 JS 作用域。
 *
 * 成员表照 Z·阅读 3.26.112318 的 JReadVoicePluginRuntime$RuntimeBridge 复刻：
 * 33 个方法 + 6 个字段（context/defVars/userVars/fs/http/tts）与三个子对象。
 * 与原文的差异只在本实现没有的能力上做显式空值（内置音效库、UI 布局），
 * 不做假数据、不吞异常；插件拿到的 data/userVars 都是真实可变对象。
 */
internal object JReadTtsrv {

    fun install(
        context: Context,
        scope: Scriptable,
        plugin: JReadPlugin,
        storage: JReadStorage,
        activeVoice: String,
        locale: String
    ): Scriptable {
        val ttsrv = context.newObject(scope)

        // ---- 字段 ----
        ScriptableObject.putProperty(ttsrv, "context", splitties.init.appCtx)
        val userVars = context.newObject(scope)
        plugin.userVars.forEach { (key, value) -> ScriptableObject.putProperty(userVars, key, value) }
        ScriptableObject.putProperty(ttsrv, "userVars", userVars)

        val defVars = context.newObject(scope)
        plugin.defVars.forEach { (key, spec) ->
            val obj = context.newObject(scope)
            spec.label?.let { ScriptableObject.putProperty(obj, "label", it) }
            spec.hint?.let { ScriptableObject.putProperty(obj, "hint", it) }
            spec.loginUrl?.let { ScriptableObject.putProperty(obj, "loginUrl", it) }
            spec.binding?.let { ScriptableObject.putProperty(obj, "binding", it) }
            spec.loginDesc?.let { ScriptableObject.putProperty(obj, "loginDesc", it) }
            spec.ua?.let { ScriptableObject.putProperty(obj, "ua", it) }
            spec.defaultValue?.let { ScriptableObject.putProperty(obj, "default", it) }
            ScriptableObject.putProperty(defVars, key, obj)
        }
        ScriptableObject.putProperty(ttsrv, "defVars", defVars)

        // ---- tts: TtsState ----
        val data = context.newObject(scope)
        storage.entries().forEach { (key, value) -> ScriptableObject.putProperty(data, key, value) }
        val tts = context.newObject(scope)
        ScriptableObject.putProperty(tts, "pluginId", plugin.pluginId)
        ScriptableObject.putProperty(tts, "voice", activeVoice)
        ScriptableObject.putProperty(tts, "locale", locale)
        ScriptableObject.putProperty(tts, "speed", plugin.speed.toDouble())
        ScriptableObject.putProperty(tts, "volume", plugin.volume.toDouble())
        ScriptableObject.putProperty(tts, "pitch", plugin.pitch.toDouble())
        ScriptableObject.putProperty(tts, "data", data)
        ScriptableObject.putProperty(tts, "defVars", defVars)
        ScriptableObject.putProperty(tts, "userVars", userVars)
        ScriptableObject.putProperty(ttsrv, "tts", tts)

        // ---- fs: FileFacade ----
        val fs = context.newObject(scope)
        fun fsFn(name: String, body: (Array<out Any?>) -> Any?) =
            ScriptableObject.putProperty(fs, name, function(context, scope, name, body))
        fsFn("exists") { args -> fileOf(args, 0).exists() }
        fsFn("readText") { args -> runCatching { fileOf(args, 0).readText() }.getOrDefault("") }
        fsFn("writeFile") { args ->
            runCatching {
                fileOf(args, 0).apply { parentFile?.mkdirs() }
                    .writeText(args.getOrNull(1)?.let { Context.toString(it) } ?: "")
                true
            }.getOrDefault(false)
        }
        ScriptableObject.putProperty(ttsrv, "fs", fs)

        // ---- http: HttpFacade ----
        val http = context.newObject(scope)
        fun httpFn(name: String, body: (Array<out Any?>) -> Any?) =
            ScriptableObject.putProperty(http, name, function(context, scope, name, body))
        httpFn("get") { args -> responseObject(context, scope, request(args, "GET", null)) }
        httpFn("post") { args ->
            val body = args.getOrNull(1)?.takeIf { !isNullish(it) }?.let { Context.toString(it) }
            responseObject(context, scope, request(args, "POST", body, headerIndex = 2))
        }
        ScriptableObject.putProperty(ttsrv, "http", http)

        // ---- 方法 ----
        fun fn(name: String, body: (Array<out Any?>) -> Any?) {
            ScriptableObject.putProperty(ttsrv, name, function(context, scope, name, body))
        }

        // 1-5 网络
        fn("httpGetString") { args -> request(args, "GET", null).string() ?: "" }
        fn("httpGetBytes") { args -> Context.javaToJS(request(args, "GET", null).bytes(), scope) }
        fn("httpGetStream") { args -> Context.javaToJS(request(args, "GET", null).bytes().inputStream(), scope) }
        fn("httpGet") { args -> responseObject(context, scope, request(args, "GET", null)) }
        fn("httpPost") { args ->
            val body = args.getOrNull(1)?.takeIf { !isNullish(it) }?.let { Context.toString(it) }
            responseObject(context, scope, request(args, "POST", body, headerIndex = 2))
        }

        // 6-9 文件（插件私有目录内的相对名，绝对路径原样使用）
        fn("fileExist") { args -> fileOf(args, 0).exists() }
        fn("exists") { args -> fileOf(args, 0).exists() }
        fn("readTxtFile") { args -> runCatching { fileOf(args, 0).readText() }.getOrDefault("") }
        fn("readText") { args -> runCatching { fileOf(args, 0).readText() }.getOrDefault("") }
        fn("writeTxtFile") { args ->
            runCatching {
                fileOf(args, 0).apply { parentFile?.mkdirs() }
                    .writeText(args.getOrNull(1)?.let { Context.toString(it) } ?: "")
                true
            }.getOrDefault(false)
        }
        fn("writeFile") { args ->
            runCatching {
                fileOf(args, 0).apply { parentFile?.mkdirs() }
                    .writeText(args.getOrNull(1)?.let { Context.toString(it) } ?: "")
                true
            }.getOrDefault(false)
        }
        fn("deleteFile") { args -> fileOf(args, 0).delete() }

        // 10-16 编码与摘要
        fn("getAudioSampleRate") { args -> JReadAudioProbe.sampleRate(bytesOf(args.getOrNull(0))) }
        fn("md5Encode") { args -> md5(Context.toString(args.getOrNull(0) ?: ""), 32) }
        fn("md5Encode16") { args -> md5(Context.toString(args.getOrNull(0) ?: ""), 16) }
        fn("createSymmetricCrypto") { args -> crypto(context, scope, args) }
        fn("strToBytes") { args ->
            val charset = args.getOrNull(1)?.let { Context.toString(it) } ?: "UTF-8"
            Context.javaToJS(Context.toString(args.getOrNull(0) ?: "").toByteArray(Charsets.UTF_8), scope)
                .also { charset }
        }
        fn("bytesToStr") { args -> String(bytesOf(args.getOrNull(0)), Charsets.UTF_8) }
        fn("randomUUID") { UUID.randomUUID().toString() }

        // 17-19 base64
        fn("base64Encode") { args ->
            val flags = args.getOrNull(1)?.let { Context.toNumber(it).toInt() } ?: Base64.DEFAULT
            Base64.encodeToString(bytesOf(args.getOrNull(0)), flags)
        }
        fn("base64Decode") { args ->
            val flags = args.getOrNull(1)?.let { Context.toNumber(it).toInt() } ?: Base64.DEFAULT
            String(runCatching { Base64.decode(Context.toString(args.getOrNull(0) ?: ""), flags) }
                .getOrDefault(ByteArray(0)), Charsets.UTF_8)
        }
        fn("base64DecodeToBytes") { args ->
            val flags = args.getOrNull(1)?.let { Context.toNumber(it).toInt() } ?: Base64.DEFAULT
            Context.javaToJS(
                runCatching { Base64.decode(Context.toString(args.getOrNull(0) ?: ""), flags) }
                    .getOrDefault(ByteArray(0)),
                scope
            )
        }

        // 20-21 播放：本实现由朗读服务统一出声，这里只做试听
        fn("playAudio") { args -> JReadPreview.play(bytesOf(args.getOrNull(0))); null }
        fn("playAudioChunk") { args -> JReadPreview.play(bytesOf(args.getOrNull(0))); null }

        // 22 UI 工具：本实现无插件编辑器 UI
        fn("setMargins") { null }

        // 23-28 内置音效：legadoC 无内置音效库，明确返回空
        fn("localSoundFastPath") { null }
        fn("downloadLocalSoundCache") { args -> fileOf(args, 1).absolutePath }
        fn("downloadLocalSoundVariant") { null }
        fn("readBuiltInAudiosEffect") { null }
        fn("builtInAudiosEffectAssetPath") { "" }
        fn("ensureBuiltInAudiosEffectMapLoaded") { null }

        // 29-33 路径与工具
        fn("readablePluginFile") { args -> Context.javaToJS(fileOf(args, 0), scope) }
        fn("localPluginFile") { args -> Context.javaToJS(fileOf(args, 0), scope) }
        fn("externalPluginFileForRead") { args -> Context.javaToJS(fileOf(args, 0), scope) }
        fn("pluginStorageId") { plugin.pluginId }
        fn("isLocalSoundPlugin") { false }
        fn("extractLocalSoundName") { args ->
            val raw = Context.toString(args.getOrNull(0) ?: "")
            Regex("""\[\[sound:([^]]+)]]""").find(raw)?.groupValues?.getOrNull(1) ?: ""
        }
        fn("floorMod") { args -> Math.floorMod(Context.toNumber(args.getOrNull(0)).toInt(), Context.toNumber(args.getOrNull(1)).toInt()) }

        // 兼容扩展
        fn("log") { args ->
            AppLog.put("JRead[${plugin.name}] " + args.joinToString(" ") { Context.toString(it) })
            null
        }
        fn("sleep") { args ->
            val ms = args.getOrNull(0)?.let { Context.toNumber(it).toLong() } ?: 0L
            if (ms in 1..30000) Thread.sleep(ms)
            null
        }

        return ttsrv
    }

    /** 合成结束/插件暂停时把插件私有数据落盘（tts.data）。 */
    fun persistData(ttsrv: Scriptable, storage: JReadStorage) {
        val tts = ScriptableObject.getProperty(ttsrv, "tts") as? Scriptable ?: return
        val data = ScriptableObject.getProperty(tts, "data") as? Scriptable ?: return
        val map = LinkedHashMap<String, String>()
        data.ids.forEach { id ->
            val key = Context.toString(id)
            val value = ScriptableObject.getProperty(data, key)
            if (value != Scriptable.NOT_FOUND && value !is Undefined) {
                map[key] = Context.toString(value)
            }
        }
        storage.replaceAll(map)
    }

    private fun function(
        context: Context,
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

    private fun isNullish(value: Any?): Boolean =
        value == null || value is Undefined || Context.toString(value) == "undefined"

    private fun responseObject(context: Context, scope: Scriptable, response: Response): Scriptable {
        val obj = context.newObject(scope)
        ScriptableObject.putProperty(obj, "code", function(context, scope, "code") { response.code })
        ScriptableObject.putProperty(obj, "text", function(context, scope, "text") { response.string() ?: "" })
        ScriptableObject.putProperty(obj, "string", function(context, scope, "string") { response.string() ?: "" })
        ScriptableObject.putProperty(obj, "statusCode", response.code)
        ScriptableObject.putProperty(obj, "json", function(context, scope, "json") {
            runCatching { org.json.JSONObject(response.string() ?: "") }.getOrElse { org.json.JSONObject() }
        })
        ScriptableObject.putProperty(obj, "bytes", function(context, scope, "bytes") {
            Context.javaToJS(response.bytes(), scope)
        })
        return obj
    }

    private class Response(val code: Int, private val body: ByteArray) {
        fun string(): String? = String(body, Charsets.UTF_8)
        fun bytes(): ByteArray = body
    }

    private fun request(
        args: Array<out Any?>,
        method: String,
        body: String?,
        headerIndex: Int = 1
    ): Response {
        val url = args.getOrNull(0)?.let { Context.toString(it) }
            ?: throw IllegalArgumentException("ttsrv: 缺少 url")
        val headers = headersOf(args.getOrNull(headerIndex))
        val builder = Request.Builder().url(url)
        headers.forEach { (key, value) -> builder.header(key, value) }
        if (method == "POST") {
            val contentType = headers["Content-Type"] ?: headers["content-type"] ?: "application/json"
            builder.post((body ?: "").toRequestBody(contentType.toMediaType()))
        } else {
            builder.get()
        }
        okHttpClient.newCall(builder.build()).execute().use { response ->
            return Response(response.code, response.body?.bytes() ?: ByteArray(0))
        }
    }

    private fun headersOf(value: Any?): Map<String, String> {
        val native = value as? Scriptable ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        native.ids.forEach { id ->
            val key = Context.toString(id)
            val item = ScriptableObject.getProperty(native, key)
            if (item != Scriptable.NOT_FOUND && item !is Undefined) out[key] = Context.toString(item)
        }
        return out
    }

    private fun crypto(context: Context, scope: Scriptable, args: Array<out Any?>): Scriptable {
        val algorithm = args.getOrNull(0)?.let { Context.toString(it) }?.uppercase() ?: "AES"
        val key = bytesOf(args.getOrNull(1))
        val iv = args.getOrNull(2)?.takeIf { !isNullish(it) }?.let { bytesOf(it) }
        val transformation = if (algorithm.contains("/")) algorithm else when (algorithm) {
            "DES" -> "DES/ECB/PKCS5Padding"
            else -> "AES/ECB/PKCS5Padding"
        }
        val cipher = Cipher.getInstance(transformation)
        val keySpec = SecretKeySpec(key, algorithm.substringBefore('/'))
        fun doFinal(mode: Int, input: ByteArray): ByteArray {
            cipher.init(mode, keySpec, iv?.let { IvParameterSpec(it) })
            return cipher.doFinal(input)
        }
        val obj = context.newObject(scope)
        ScriptableObject.putProperty(obj, "encrypt", function(context, scope, "encrypt") { callArgs ->
            Context.javaToJS(doFinal(Cipher.ENCRYPT_MODE, bytesOf(callArgs.getOrNull(0))), scope)
        })
        ScriptableObject.putProperty(obj, "decrypt", function(context, scope, "decrypt") { callArgs ->
            Context.javaToJS(doFinal(Cipher.DECRYPT_MODE, bytesOf(callArgs.getOrNull(0))), scope)
        })
        ScriptableObject.putProperty(obj, "encryptBase64", function(context, scope, "encryptBase64") { callArgs ->
            Base64.encodeToString(doFinal(Cipher.ENCRYPT_MODE, bytesOf(callArgs.getOrNull(0))), Base64.NO_WRAP)
        })
        ScriptableObject.putProperty(obj, "decryptStr", function(context, scope, "decryptStr") { callArgs ->
            val raw = String(bytesOf(callArgs.getOrNull(0)), Charsets.UTF_8)
            String(doFinal(Cipher.DECRYPT_MODE, Base64.decode(raw, Base64.DEFAULT)), Charsets.UTF_8)
        })
        return obj
    }

    private fun md5(value: String, length: Int): String {
        val hex = MessageDigest.getInstance("MD5").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return if (length == 16) hex.substring(8, 24) else hex
    }

    /** 插件私有目录内的相对路径落到 files/jread/plugin_files/<pluginId>/ 下。 */
    private fun fileOf(args: Array<out Any?>, index: Int): File {
        val raw = args.getOrNull(index)?.let { Context.toString(it) } ?: ""
        val file = File(raw)
        if (file.isAbsolute) return file
        val base = File(splitties.init.appCtx.filesDir, "jread/plugin_files")
        return File(base, raw)
    }

    fun bytesOf(value: Any?): ByteArray = when (value) {
        null -> ByteArray(0)
        is ByteArray -> value
        is org.mozilla.javascript.NativeArray -> {
            val length = value.length.toInt()
            ByteArray(length) { index ->
                Context.toNumber(ScriptableObject.getProperty(value, index)).toInt().toByte()
            }
        }
        is org.mozilla.javascript.NativeJavaArray -> {
            val unwrapped = value.unwrap()
            if (unwrapped is ByteArray) unwrapped else ByteArray(0)
        }
        is java.io.InputStream -> value.use { it.readBytes() }
        is String -> value.toByteArray(Charsets.UTF_8)
        else -> {
            val array = value as? Scriptable
            val length = array?.get("length", array) as? Number
            if (array != null && length != null) {
                ByteArray(length.toInt()) { index ->
                    Context.toNumber(ScriptableObject.getProperty(array, index)).toInt().toByte()
                }
            } else {
                ByteArray(0)
            }
        }
    }
}
