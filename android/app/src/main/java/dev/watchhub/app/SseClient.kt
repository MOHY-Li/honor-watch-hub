package dev.watchhub.app

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 极简 SSE 客户端。没有用 OkHttp（少一个依赖）。
 *
 * 契约（见 docs/PROTOCOL.md）：
 *  - 服务端每条事件是 3~4 行：id / event / data / 空行
 *  - 心跳是 `: ping` 注释行，忽略
 *  - 断线由 [SseService] 负责退避重连，本类只负责「读一次连接直到断开」
 */
class SseClient(
    private val cfg: Config,
    private val onEvent: (event: String, id: Long, data: String) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: (Throwable?) -> Unit,
) {

    @Volatile
    private var conn: HttpURLConnection? = null

    @Volatile
    var isRunning = false
        private set

    fun connect(lastId: Long) {
        isRunning = true
        var error: Throwable? = null
        try {
            val url = URL(cfg.eventsUrl(lastId))
            val c = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 90_000          // > 服务端 15s 心跳 * 3，容忍抖动
                requestMethod = "GET"
                setRequestProperty("Accept", "text/event-stream")
                setRequestProperty("Cache-Control", "no-cache")
                if (lastId > 0) setRequestProperty("Last-Event-ID", lastId.toString())
            }
            conn = c
            val code = c.responseCode
            if (code != 200) {
                error = IllegalStateException("HTTP $code")
                onDisconnected(error)
                return
            }
            onConnected()
            val parser = SseParser()
            BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { reader ->
                while (isRunning) {
                    val line = reader.readLine() ?: break
                    parser.feed(line)?.let { e -> onEvent(e.event, e.id, e.data) }
                }
            }
        } catch (t: Throwable) {
            error = t
            Log.w(TAG, "sse error: ${t.message}")
        } finally {
            isRunning = false
            try { conn?.disconnect() } catch (_: Exception) {}
            conn = null
            onDisconnected(error)
        }
    }

    fun close() {
        isRunning = false
        try { conn?.disconnect() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "SseClient"

        /** 一次性探活，用于「测试连接」。 */
        fun probe(cfg: Config): Pair<Boolean, String> = try {
            val c = (URL(cfg.healthUrl()).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 6_000
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            c.disconnect()
            if (code in 200..299) {
                val j = JSONObject(text)
                true to "OK · v${j.optString("version")} · 源 ${j.optInt("sources_ok")}/${j.optInt("sources_total")} · 在线 ${j.optInt("sse_clients")}"
            } else {
                false to "HTTP $code"
            }
        } catch (t: Throwable) {
            false to (t.message ?: t.javaClass.simpleName)
        }
    }
}
