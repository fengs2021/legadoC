package io.legado.app.plugin.jread

import com.script.rhino.RhinoContext
import io.legado.app.constant.AppLog
import io.legado.app.help.http.okHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 插件用的 WebSocket 宿主，契约照 Z·阅读：`new Websocket(url, headers)` 后
 * `.on('open'|'message'|'close'|'error', fn)`、`.send(string | uint8Array)`、`.close()`。
 *
 * 事件回调必须在该插件的 Rhino 作用域里执行，所以统一排到单线程 executor，
 * 避免 OkHttp 回调线程直接进 JS（Rhino 非线程安全）。
 */
internal class JReadWebSocket(
    url: String,
    private val headers: Map<String, String>,
    private val scope: Scriptable
) {

    private val handlers = LinkedHashMap<String, MutableList<Function>>()
    private val lock = Any()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var opened = false

    @Volatile
    private var closed = false

    init {
        val builder = Request.Builder().url(url)
        headers.forEach { (key, value) -> builder.header(key, value) }
        socket = runCatching {
            okHttpClient.newWebSocket(builder.build(), listener)
        }.getOrElse {
            AppLog.put("JRead WebSocket 建连失败\n${it.localizedMessage}", it)
            closed = true
            null
        }
    }

    fun on(event: String, callback: Function) {
        val name = event.lowercase()
        synchronized(lock) {
            handlers.getOrPut(name) { CopyOnWriteArrayList() }.add(callback)
        }
        if (name == "open" && opened) fire("open", emptyArray<Any?>())
        if (name == "close" && closed) fire("close", arrayOf<Any?>(1000, ""))
    }

    fun send(data: Any?) {
        val ws = socket ?: return
        when (data) {
            null -> ws.send("")
            is CharSequence -> ws.send(data.toString())
            is ByteArray -> ws.send(ByteString.of(*data))
            is NativeArray -> {
                val bytes = JReadTtsrv.bytesOf(data)
                ws.send(ByteString.of(*bytes))
            }
            else -> ws.send(Context.toString(data))
        }
    }

    fun close() {
        runCatching { socket?.close(1000, null) }
        closed = true
    }

    /** 插件常用 ws.cancel() 立即中断连接（不握手关闭）；缺这个方法会让插件内部 try/catch 静默失败。 */
    fun cancel() {
        runCatching { socket?.cancel() }
        closed = true
    }

    /** 个别插件用 terminate()，语义同 cancel。 */
    fun terminate() {
        cancel()
    }

    fun isOpen(): Boolean = opened && !closed

    private fun fire(event: String, args: Array<Any?>) {
        val callbacks = synchronized(lock) { handlers[event]?.toList() } ?: return
        if (callbacks.isEmpty()) return
        callbacks.forEach { callback ->
            executor.execute {
                val cx = Context.enter() as RhinoContext
                cx.allowScriptRun = true
                try {
                    callback.call(cx, scope, scope, args)
                } catch (error: Throwable) {
                    AppLog.put("JRead WebSocket 回调异常（$event）\n${error.localizedMessage}", error)
                } finally {
                    Context.exit()
                }
            }
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened = true
            fire("open", emptyArray<Any?>())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            fire("message", arrayOf<Any?>(text))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            fire("message", arrayOf<Any?>(bytes.toByteArray()))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closed = true
            fire("close", arrayOf<Any?>(code, reason))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed = true
            fire("close", arrayOf<Any?>(code, reason))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            closed = true
            AppLog.put("JRead WebSocket 失败\n${t.localizedMessage}")
            fire("error", arrayOf<Any?>(t.localizedMessage ?: "websocket error", null))
        }
    }

    companion object {
        /** 所有插件的 WS 事件串行在一条线程上执行，避免 Rhino 跨线程问题。 */
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "jread-ws").apply { isDaemon = true }
        }
    }
}
