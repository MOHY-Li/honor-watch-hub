package dev.watchhub.app

import dev.watchhub.app.update.UpdateCheck
import dev.watchhub.app.update.Updater
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.AfterClass
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 端到端：真起一个 HTTP 服务提供最新版本清单，让真实的 Updater.check 去读。
 * 覆盖「清单生成 -> HTTP -> 解析 -> 版本比较」整条链路。
 */
class UpdateCheckE2ETest {

    companion object {
        private lateinit var server: ServerSocket
        private var port = 0
        @Volatile private var alive = true

        @Volatile private var body: String = ""

        @BeforeClass @JvmStatic
        fun start() {
            server = ServerSocket(0)
            port = server.localPort
            thread(isDaemon = true) {
                while (alive) {
                    val s: Socket = try { server.accept() } catch (e: Exception) { break }
                    thread(isDaemon = true) {
                        s.use { sock ->
                            val inS = BufferedReader(InputStreamReader(sock.getInputStream()))
                            inS.readLine()
                            while (true) { val h = inS.readLine() ?: break; if (h.isEmpty()) break }
                            val bytes = body.toByteArray()
                            sock.getOutputStream().run {
                                write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                                write(bytes)
                                flush()
                            }
                        }
                    }
                }
            }
        }

        @AfterClass @JvmStatic
        fun stop() {
            alive = false
            runCatching { server.close() }
        }
    }

    private fun url() = "http://127.0.0.1:$port/latest.json"

    private fun manifestJson(code: Int, name: String = "9.9.9", minSdk: Int = 24) =
        JSONObject().apply {
            put("versionCode", code)
            put("versionName", name)
            put("apkUrl", "https://example.com/WatchHub.apk")
            put("sha256", "c".repeat(64))
            put("sizeBytes", 5_000_000)
            put("changelog", "- 修了 A\n- 加了 B")
            put("minSdk", minSdk)
        }.toString()

    @Test
    fun `detects newer version over real http`() {
        body = manifestJson(99)
        val r = Updater.check(url(), currentCode = 2, sdkInt = 34)
        assertTrue("期望 Available，实际 $r", r is UpdateCheck.Available)
        val m = (r as UpdateCheck.Available).manifest
        assertEquals(99L, m.versionCode)
        assertEquals("9.9.9", m.versionName)
    }

    @Test
    fun `same or older version reports up to date`() {
        body = manifestJson(2)
        assertTrue(Updater.check(url(), 2, sdkInt = 34) is UpdateCheck.UpToDate)

        body = manifestJson(1)
        assertTrue(Updater.check(url(), 2, sdkInt = 34) is UpdateCheck.UpToDate)
    }

    @Test
    fun `higher minSdk reports unsupported`() {
        body = manifestJson(99, minSdk = 99)
        assertTrue(Updater.check(url(), 2, sdkInt = 34) is UpdateCheck.Unsupported)
    }

    @Test
    fun `html response reports failure with reason`() {
        body = "<!DOCTYPE html><html>404: Not Found</html>"
        val r = Updater.check(url(), 2, sdkInt = 34)
        assertTrue("期望 Failed，实际 $r", r is UpdateCheck.Failed)
    }

    @Test
    fun `network error reports failure not crash`() {
        val r = Updater.check("http://127.0.0.1:1/latest.json", 2, sdkInt = 34)
        assertTrue("期望 Failed，实际 $r", r is UpdateCheck.Failed)
    }
}
