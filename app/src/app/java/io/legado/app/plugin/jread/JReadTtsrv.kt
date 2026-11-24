package io.legado.app.plugin.jread

import io.legado.app.constant.AppLog
import io.legado.app.help.http.okHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

/**
 * 把 Z·阅读 插件运行时对象 `ttsrv` 装进指定 JS 作用域。
 *
 * 只实现插件实际用到的能力（14 个内置插件统计），不额外发明接口：
 * 网络（get/post/getString/getBytes/getStream）、文件读写、编码转换、md5、
 * 对称加解密、采样率探测、插件私有键值存储（tts.data）、变量（userVars/defVars）。
 * 播放相关（playAudio/playAudioChunk）不在本层出声：音频统一回传给朗读服务播放。
 */
internal object JReadTtsrv {

    fun install(context: Context, scope: Scriptable, plugin: JReadPlugin, storage: JReadStorage): Scriptable {
        val ttsrv = context.newObject(scope)

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

        val tts = context.newObject(scope)
        val data = context.newObject(scope)
        storage.entries().forEach { (key, value) -> ScriptableObject.putProperty(data, key, value) }
        ScriptableObject.putProperty(tts, "data", data)
        ScriptableObject.putProperty(tts, "userVars", userVars)
        ScriptableObject.putProperty(ttsrv, "tts", tts)

        fun fn(name: String, body: (Array<out Any?>) -> Any?) {
            ScriptableObject.putProperty(ttsrv, name, function(context, scope, name, body))
        }

        fn("httpGet") { args -> http(context, scope, args, "GET", null) }
        fn("httpPost") { args ->
            val body = args.getOrNull(1)?.let { Context.toString(it) }
            http(context, scope, args, "POST", body)
        }
        fn("httpGetString") { args ->
            val response = execute(args, "GET", null)
            ScriptableObject.putProperty(response, "__text", response.string("text"))
            response.string("text") ?: ""
        }
        fn("httpGetBytes") { args ->
            val response = execute(args, "GET", null)
            Context.javaToJS(response.bytes(), scope)
        }
        fn("httpGetStream") { args ->
            val response = execute(args, "GET", null)
            Context.javaToJS(response.bytes().inputStream(), scope)
        }
        fn("readTxtFile") { args ->
            val path = args.getOrNull(0)?.let { Context.toString(it) } ?: ""
            runCatching { File(path).readText() }.getOrDefault("")
        }
        fn("writeTxtFile") { args ->
            val path = args.getOrNull(0)?.let { Context.toString(it) } ?: ""
            val text = args.getOrNull(1)?.let { Context.toString(it) } ?: ""
            runCatching {
                File(path).apply { parentFile?.mkdirs() }.writeText(text)
                true
            }.getOrDefault(false)
        }
        fn("fileExist") { args ->
            val path = args.getOrNull(0)?.let { Context.toString(it) } ?: ""
            File(path).exists()
        }
        fn("strToBytes") { args ->
            val text = args.getOrNull(0)?.let { Context.toString(it) } ?: ""
            Context.javaToJS(text.toByteArray(Charsets.UTF_8), scope)
        }
        fn("bytesToStr") { args -> String(bytesOf(args.getOrNull(0)), Charsets.UTF_8) }
        fn("base64DecodeToBytes") { args ->
            val text = args.getOrNull(0)?.let { Context.toString(it) } ?: ""
            Context.javaToJS(runCatching { Base64.decode(text, Base64.DEFAULT) }.getOrDefault(ByteArray(0)), scope)
        }
        fn("md5Encode") { args ->
            val text = args.getOrNull(0)?.let { Context.toString(it) } ?: ""
            MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        fn("randomUUID") { UUID.randomUUID().toString() }
        fn("getAudioSampleRate") { args -> JReadAudioProbe.sampleRate(bytesOf(args.getOrNull(0))) }
        fn("createSymmetricCrypto") { args -> crypto(context, scope, args) }
        fn("playAudio") { null }
        fn("playAudioChunk") { null }
        fn("setMargins") { null }
        fn("log") { args ->
            AppLog.put("JRead[${plugin.name}] ${args.joinToString(" ") { Context.toString(it) }}")
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
            if (value != Scriptable.NOT_FOUND && value !is org.mozilla.javascript.Undefined) {
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

    private fun http(
        context: Context,
        scope: Scriptable,
        args: Array<out Any?>,
        method: String,
        body: String?
    ): Scriptable {
        val response = execute(args, method, body)
        val obj = context.newObject(scope)
        val text = response.string("text")
        ScriptableObject.putProperty(obj, "code", function(context, scope, "code") { response.code })
        ScriptableObject.putProperty(obj, "text", function(context, scope, "text") { text ?: "" })
        ScriptableObject.putProperty(obj, "json", function(context, scope, "json") {
            runCatching { org.json.JSONObject(text ?: "") }.getOrElse { org.json.JSONObject() }
        })
        ScriptableObject.putProperty(obj, "bytes", function(context, scope, "bytes") {
            Context.javaToJS(response.bytes(), scope)
        })
        return obj
    }

    private class HttpResponse(val code: Int, private val body: ByteArray) {
        fun string(field: String): String? = String(body, Charsets.UTF_8)

        fun bytes(): ByteArray = body
    }

    private fun execute(args: Array<out Any?>, method: String, body: String?): HttpResponse {
        val url = args.getOrNull(0)?.let { Context.toString(it) }
            ?: throw IllegalArgumentException("ttsrv: 缺少 url")
        val headers = headersOf(args.getOrNull(if (body == null) 1 else 2))
        val builder = Request.Builder().url(url)
        headers.forEach { (key, value) -> builder.header(key, value) }
        if (method == "POST") {
            val contentType = headers["Content-Type"] ?: headers["content-type"] ?: "application/json"
            builder.post((body ?: "").toRequestBody(contentType.toMediaType()))
        } else {
            builder.get()
        }
        okHttpClient.newCall(builder.build()).execute().use { response ->
            return HttpResponse(response.code, response.body?.bytes() ?: ByteArray(0))
        }
    }

    private fun headersOf(value: Any?): Map<String, String> {
        val native = value as? Scriptable ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        native.ids.forEach { id ->
            val key = Context.toString(id)
            val item = ScriptableObject.getProperty(native, key)
            if (item != Scriptable.NOT_FOUND && item !is org.mozilla.javascript.Undefined) {
                out[key] = Context.toString(item)
            }
        }
        return out
    }

    private fun crypto(context: Context, scope: Scriptable, args: Array<out Any?>): Scriptable {
        val algorithm = args.getOrNull(0)?.let { Context.toString(it) }?.uppercase() ?: "AES"
        val key = bytesOf(args.getOrNull(1))
        val iv = args.getOrNull(2)?.let { bytesOf(it) }
        val transformation = when {
            algorithm.contains("/") -> algorithm
            algorithm == "AES" -> "AES/ECB/PKCS5Padding"
            algorithm == "DES" -> "DES/ECB/PKCS5Padding"
            else -> "$algorithm/ECB/PKCS5Padding"
        }
        val cipher = Cipher.getInstance(transformation)
        val keySpec = SecretKeySpec(
            if (algorithm == "DES") key.copyOf(8) else key.copyOf(16.coerceAtMost(key.size).coerceAtLeast(key.size)),
            algorithm
        )
        val obj = context.newObject(scope)
        ScriptableObject.putProperty(obj, "encrypt", function(context, scope, "encrypt") { callArgs ->
            val plain = bytesOf(callArgs.getOrNull(0))
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, iv?.let { IvParameterSpec(it) })
            Context.javaToJS(cipher.doFinal(plain), scope)
        })
        ScriptableObject.putProperty(obj, "decrypt", function(context, scope, "decrypt") { callArgs ->
            val encrypted = bytesOf(callArgs.getOrNull(0))
            cipher.init(Cipher.DECRYPT_MODE, keySpec, iv?.let { IvParameterSpec(it) })
            Context.javaToJS(cipher.doFinal(encrypted), scope)
        })
        return obj
    }

    fun bytesOf(value: Any?): ByteArray = when (value) {
        null -> ByteArray(0)
        is ByteArray -> value
        is org.mozilla.javascript.NativeArray -> ByteArray(value.length.toInt()) { index ->
            Context.toNumber(ScriptableObject.getProperty(value, index)).toByte()
        }
        is org.mozilla.javascript.NativeJavaArray -> {
            val array = value.array
            if (array is ByteArray) array else ByteArray(0)
        }
        is String -> value.toByteArray(Charsets.UTF_8)
        else -> {
            val length = runCatching { (value as? Scriptable)?.get("length", null as Scriptable?) }.getOrNull()
            if (length is Number) {
                val array = value as Scriptable
                ByteArray(length.toInt()) { index ->
                    Context.toNumber(ScriptableObject.getProperty(array, index.toDouble())).toByte()
                }
            } else {
                ByteArray(0)
            }
        }
    }
}
