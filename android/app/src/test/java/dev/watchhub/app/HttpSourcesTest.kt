package dev.watchhub.app

import dev.watchhub.app.hub.HashSource
import dev.watchhub.app.hub.JsonSource
import dev.watchhub.app.hub.RegexSource
import dev.watchhub.app.hub.RssSource
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * 真实 HTTP 集成测试：本地起一个极简 socket HTTP 服务，让采集器真的去抓。
 * 覆盖 网络层(Http) + 解析层(RSS/JSON/Regex/Hash)，全部不依赖 Android。
 */
class HttpSourcesTest {

    companion object {
        private lateinit var server: ServerSocket
        private var port = 0
        private val counter = AtomicInteger(0)
        @Volatile private var alive = true

        private val RSS = """<?xml version="1.0"?>
            <rss version="2.0"><channel><title>t</title>
            <item><title>第1话</title><link>http://x/1</link><guid>ep-1</guid><description>已更新 EP1</description></item>
            <item><title>第2话</title><link>http://x/2</link><guid>ep-2</guid><description>已更新 EP2</description></item>
            </channel></rss>"""

        private val ATOM = """<?xml version="1.0"?>
            <feed><title>a</title>
            <entry><title>Atom 条目</title><id>urn:1</id><link href="http://y/1"/><summary>摘要</summary></entry>
            </feed>"""

        private val JSON = """{"data":[{"id":11,"name_cn":"第一集","ep":1,"airdate":"2024-10-02"},""" +
            """{"id":12,"name_cn":"第二集","ep":2,"airdate":"2024-10-09"}]}"""

        private val HTML = """<html><body>""" +
            """<a href="/news/1">新闻一</a><a href="/news/2">新闻二</a></body></html>"""

        private fun respond(path: String): Pair<Int, String> = when (path) {
            "/feed.xml" -> 200 to RSS
            "/atom.xml" -> 200 to ATOM
            "/api.json" -> 200 to JSON
            "/page.html" -> 200 to HTML
            "/mutating" -> 200 to "version=${counter.incrementAndGet()}"
            else -> 404 to "nope"
        }

        @BeforeClass @JvmStatic
        fun start() {
            server = ServerSocket(0)
            port = server.localPort
            thread(isDaemon = true, name = "test-http") {
                while (alive) {
                    val sock: Socket = try { server.accept() } catch (e: Exception) { break }
                    thread(isDaemon = true) { handle(sock) }
                }
            }
        }

        private fun handle(sock: Socket) {
            sock.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val requestLine = reader.readLine() ?: return
                val path = requestLine.split(" ").getOrNull(1) ?: "/"
                while (true) {
                    val h = reader.readLine() ?: break
                    if (h.isEmpty()) break
                }
                val (code, body) = respond(path)
                val bytes = body.toByteArray()
                val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "Not Found"}\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
                s.getOutputStream().run {
                    write(head.toByteArray())
                    write(bytes)
                    flush()
                }
            }
        }

        @AfterClass @JvmStatic
        fun stop() {
            alive = false
            runCatching { server.close() }
        }
    }

    private fun url(p: String) = "http://127.0.0.1:$port$p"

    @Test
    fun `rss source parses items over real http`() {
        val src = RssSource("feed", JSONObject("""{"name":"feed","kind":"rss","url":"${url("/feed.xml")}","limit":10}"""))
        val items = src.fetch()
        assertEquals(2, items.size)
        assertEquals("第1话", items[0].title)
        assertEquals("http://x/1", items[0].url)
        assertTrue(items[0].key.startsWith("feed:"))
        val again = src.fetch()
        assertEquals(items.map { it.key }, again.map { it.key })
    }

    @Test
    fun `atom source parses entries`() {
        val src = RssSource("atom", JSONObject("""{"name":"atom","kind":"rss","url":"${url("/atom.xml")}"}"""))
        val items = src.fetch()
        assertEquals(1, items.size)
        assertEquals("Atom 条目", items[0].title)
        assertEquals("http://y/1", items[0].url)
    }

    @Test
    fun `json source maps fields and formats`() {
        val cfg = """{"name":"bgm","kind":"json","url":"${url("/api.json")}","list_path":"data",""" +
            """"fields":{"title":"name_cn","key":"id","body":"airdate"},""" +
            """"body_format":"{airdate} · 第{ep}话"}"""
        val items = JsonSource("bgm", JSONObject(cfg)).fetch()
        assertEquals(2, items.size)
        assertEquals("第一集", items[0].title)
        assertEquals("2024-10-02 · 第1话", items[0].body)
    }

    @Test
    fun `regex source extracts named groups`() {
        // 用 JSONObject 构造，避免在原始字符串里手写 JSON 转义
        val cfg = JSONObject().apply {
            put("name", "news")
            put("kind", "regex")
            put("url", url("/page.html"))
            put("pattern", """<a href="(?<url>[^"]+)">(?<title>[^<]+)</a>""")
        }
        val items = RegexSource("news", cfg).fetch()
        assertEquals(2, items.size)
        assertEquals("新闻一", items[0].title)
        assertEquals("/news/1", items[0].url)
    }

    @Test
    fun `hash source key changes when page changes`() {
        val src = HashSource("price", JSONObject("""{"name":"price","kind":"hash","url":"${url("/mutating")}","title":"变了"}"""))
        val a = src.fetch().single()
        val b = src.fetch().single()
        assertTrue("内容变化后 key 必须不同", a.key != b.key)
        assertEquals("变了", a.title)
    }

    @Test
    fun `http 404 raises a clear error`() {
        val src = RssSource("bad", JSONObject("""{"name":"bad","kind":"rss","url":"${url("/404")}"}"""))
        val e = runCatching { src.fetch() }.exceptionOrNull()
        assertTrue(e != null)
        assertTrue("错误信息应包含状态码: ${e?.message}", e!!.message!!.contains("404"))
    }

    @Test
    fun `unreachable host raises not hang`() {
        val src = RssSource("dead", JSONObject("""{"name":"dead","kind":"rss","url":"http://127.0.0.1:1/x"}"""))
        assertTrue(runCatching { src.fetch() }.exceptionOrNull() != null)
    }
}
