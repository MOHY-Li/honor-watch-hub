package dev.watchhub.app.testing

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 单元测试用的极简 HTTP 服务。
 *
 * 为什么不用 `com.sun.net.httpserver`：AGP 编译 Android 单测时给 Kotlin 传了 `-no-jdk`，
 * 测试代码的 classpath 只有 android.jar，看不到 com.sun.*（运行时 JVM 有，编译期没有）。
 * 而 `java.net.ServerSocket` 在 android.jar 里，所以用裸 socket 实现一个够用的 HTTP/1.1
 * 服务，覆盖真实的「连得上 / 404 / gzip / POST」路径。
 *
 * 用法：
 * ```
 * val server = TestHttpServer()
 * server.route("/feed.xml", rssXml)
 * server.route("/echo") { req -> TestHttpServer.Response(200, req.body) }
 * val url = server.baseUrl + "/feed.xml"
 * ...
 * server.close()
 * ```
 */
class TestHttpServer : AutoCloseable {

    data class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    data class Response(
        val status: Int = 200,
        val body: ByteArray = ByteArray(0),
        val headers: Map<String, String> = emptyMap(),
    ) {
        companion object {
            fun text(body: String, status: Int = 200, headers: Map<String, String> = emptyMap()) =
                Response(status, body.toByteArray(Charsets.UTF_8), headers)
        }
    }

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val routes = ConcurrentHashMap<String, (Request) -> Response>()
    private val alive = AtomicBoolean(true)

    val port: Int get() = server.localPort
    val baseUrl: String get() = "http://127.0.0.1:$port"

    init {
        thread(isDaemon = true, name = "test-http-accept") { acceptLoop() }
    }

    fun route(path: String, handler: (Request) -> Response) {
        routes[path] = handler
    }

    fun route(path: String, body: String, status: Int = 200, headers: Map<String, String> = emptyMap()) {
        routes[path] = { Response.text(body, status, headers) }
    }

    override fun close() {
        alive.set(false)
        runCatching { server.close() }
    }

    private fun acceptLoop() {
        while (alive.get()) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            thread(isDaemon = true, name = "test-http-conn") { handle(socket) }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            val input = s.getInputStream()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrElse(0) { "GET" }
            val path = parts.getOrElse(1) { "/" }.substringBefore('?')

            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLength > 0) readExactly(input, contentLength) else ByteArray(0)

            val response = routes[path]?.invoke(Request(method, path, headers, body))
                ?: Response.text("not found", 404)
            write(s.getOutputStream(), response)
        }
    }

    private fun readLine(input: InputStream): String? {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("UTF-8")
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
            if (out.size() > 16384) break
        }
        return out.toString("UTF-8")
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n < 0) break
            read += n
        }
        return if (read == length) buf else buf.copyOf(read)
    }

    private fun write(out: OutputStream, response: Response) {
        val reason = when (response.status) {
            200 -> "OK"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            else -> "OK"
        }
        val head = StringBuilder()
            .append("HTTP/1.1 ${response.status} $reason\r\n")
            .append("Content-Length: ${response.body.size}\r\n")
            .append("Connection: close\r\n")
        response.headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        out.write(response.body)
        out.flush()
    }
}
