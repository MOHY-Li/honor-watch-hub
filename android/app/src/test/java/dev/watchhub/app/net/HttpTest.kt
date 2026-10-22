package dev.watchhub.app.net

import dev.watchhub.app.testing.TestHttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class HttpTest {

    private lateinit var server: TestHttpServer

    @Before
    fun setUp() {
        server = TestHttpServer()
        server.route("/text", "hello")
        server.route("/gzip") {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write("gzip body 中文".toByteArray()) }
            TestHttpServer.Response(200, out.toByteArray(), mapOf("Content-Encoding" to "gzip"))
        }
        server.route("/404", "nope", status = 404)
        server.route("/echo") { req -> TestHttpServer.Response(200, req.body) }
        server.route("/huge", "x".repeat(64 * 1024))
        server.route("/header") { req ->
            val seen = req.headers["x-watchhub"]
            TestHttpServer.Response.text(seen ?: "missing")
        }
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun getText_ok() {
        assertEquals("hello", Http.getText("${server.baseUrl}/text"))
    }

    @Test
    fun getText_handlesGzip() {
        assertEquals("gzip body 中文", Http.getText("${server.baseUrl}/gzip"))
    }

    @Test
    fun execute_postEchoesBody() {
        val res = Http.execute("POST", "${server.baseUrl}/echo", body = "post-data".toByteArray())
        assertEquals("post-data", res.text())
    }

    @Test
    fun execute_sendsCustomHeaders() {
        assertEquals("yes", Http.getText("${server.baseUrl}/header", mapOf("X-WatchHub" to "yes")))
    }

    @Test
    fun httpError_throwsWithCode() {
        val e = runCatching { Http.getText("${server.baseUrl}/404") }.exceptionOrNull()
        assertTrue(e is Http.HttpException)
        assertTrue(e!!.message!!.contains("404"))
    }

    @Test
    fun maxBytes_enforced() {
        val e = runCatching {
            Http.execute("GET", "${server.baseUrl}/huge", maxBytes = 1024)
        }.exceptionOrNull()
        assertTrue(e is Http.HttpException)
        assertTrue(e!!.message!!.contains("上限"))
    }

    @Test
    fun connectionRefused_throwsHttpException() {
        // 占一个端口再关掉，保证这个端口没人监听
        val socket = java.net.ServerSocket(0)
        val port = socket.localPort
        socket.close()
        val e = runCatching { Http.getText("http://127.0.0.1:$port/none", timeoutMs = 1500) }.exceptionOrNull()
        assertTrue(e is Http.HttpException)
    }

    @Test
    fun invalidScheme_rejected() {
        val e = runCatching { Http.getText("file:///etc/passwd") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    @Test
    fun emptyResponse_throwsHttpException() {
        // 200 但 body 为空
        server.route("/empty") { TestHttpServer.Response(200, ByteArray(0)) }
        val e = runCatching { Http.getText("${server.baseUrl}/empty") }.exceptionOrNull()
        assertTrue(e is Http.HttpException)
    }
}
