package dev.watchhub.app.source

import dev.watchhub.app.testing.TestHttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/**
 * 真实 HTTP 集成测试：用 [TestHttpServer]（裸 socket 的极简 HTTP/1.1 服务）让采集器走完整的
 * net.Http + 解析链路。全部纯 JVM，不依赖 Android，也不使用 com.sun.net.httpserver
 * （AGP 给单测 Kotlin 编译传了 -no-jdk，测试 classpath 里没有 com.sun.*）。
 */
class HttpSourcesTest {

    private lateinit var server: TestHttpServer
    private val counter = AtomicInteger(0)

    companion object {
        private val RSS = """<?xml version="1.0" encoding="utf-8"?>
            <rss version="2.0"><channel><title>t</title>
            <item><title>第1话</title><link>http://x/1</link><guid>ep-1</guid><description>已更新 EP1</description></item>
            <item><title>第2话</title><link>http://x/2</link><guid>ep-2</guid><description>已更新 EP2</description></item>
            </channel></rss>"""

        private val ATOM = """<?xml version="1.0" encoding="utf-8"?>
            <feed><title>a</title>
            <entry><title>Atom 条目</title><id>urn:1</id><link href="http://y/1"/><summary>摘要</summary></entry>
            </feed>"""

        private val JSON = """{"data":[{"id":11,"name_cn":"第一集","ep":1,"airdate":"2024-10-02"},""" +
            """{"id":12,"name_cn":"第二集","ep":2,"airdate":"2024-10-09"}]}"""

        private val HTML = """<html><body>""" +
            """<a href="/news/1">新闻一</a><a href="/news/2">新闻二</a></body></html>"""
    }

    @Before
    fun setUp() {
        server = TestHttpServer()
        server.route("/feed.xml", RSS)
        server.route("/atom.xml", ATOM)
        server.route("/api.json", JSON)
        server.route("/page.html", HTML)
        // 每次请求内容都变，用于验证 hash 源
        server.route("/version.txt") {
            TestHttpServer.Response.text("version=${counter.incrementAndGet()}")
        }
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun url(path: String) = server.baseUrl + path

    private fun def(kind: String, path: String, extra: String = ""): SourceDef =
        requireNotNull(
            SourceDef.parse(JSONObject("""{"name":"$kind-src","kind":"$kind","url":"${url(path)}"$extra}"""))
        )

    @Test
    fun rssSourceParsesOverHttp() {
        val items = RssSource(def("rss", "/feed.xml")).fetch()
        assertEquals(2, items.size)
        assertEquals("第1话", items[0].title)
        assertEquals("http://x/1", items[0].url)
        assertTrue(items[0].key.startsWith("rss-src:"))
        // 同一内容重复抓取 key 稳定
        assertEquals(items.map { it.key }, RssSource(def("rss", "/feed.xml")).fetch().map { it.key })
    }

    @Test
    fun atomSourceParsesEntriesOverHttp() {
        val item = RssSource(def("rss", "/atom.xml")).fetch().single()
        assertEquals("Atom 条目", item.title)
        assertEquals("http://y/1", item.url)
        assertEquals("摘要", item.body)
    }

    @Test
    fun jsonSourceMapsFieldsAndFormatsOverHttp() {
        val src = JsonSource(
            def(
                "json", "/api.json",
                ""","list_path":"data","fields":{"title":"name_cn","key":"id","body":"airdate"},""" +
                    """"body_format":"{airdate} · 第{ep}话""""
            )
        )
        val items = src.fetch()
        assertEquals(2, items.size)
        assertEquals("第一集", items[0].title)
        assertEquals("2024-10-02 · 第1话", items[0].body)
        assertTrue(items[0].key.startsWith("json-src:"))
    }

    @Test
    fun regexSourceExtractsNamedGroupsOverHttp() {
        val src = RegexSource(
            def(
                "regex", "/page.html",
                ""","pattern":""" + JSONObject.quote("""<a href="(?<url>[^"]+)">(?<title>[^<]+)</a>""")
            )
        )
        val items = src.fetch()
        assertEquals(2, items.size)
        assertEquals("新闻一", items[0].title)
        assertEquals("/news/1", items[0].url)
        assertTrue(items[0].key.startsWith("regex-src:"))
    }

    @Test
    fun hashSourceProducesStableKeyForSamePage() {
        val def = def("hash", "/version.txt", ""","title":"变了"""")
        // /version.txt 每次请求内容都变，key 必须跟着变
        val a = HashSource(def).fetch().single()
        val b = HashSource(def).fetch().single()
        assertEquals("变了", a.title)
        assertTrue(Regex("^hash-src:[0-9a-f]{16}$").matches(a.key))
        assertNotEquals("内容变化后 key 必须不同", a.key, b.key)
    }

    @Test
    fun http404RaisesClearError() {
        val src = RssSource(def("rss", "/missing"))
        val e = runCatching { src.fetch() }.exceptionOrNull()
        assertTrue("404 必须抛异常", e != null)
        assertTrue("错误信息应包含状态码: ${e?.message}", e!!.message!!.contains("404"))
    }

    @Test
    fun connectionRefusedThrowsInsteadOfHanging() {
        val deadPort = ServerSocket(0).use { it.localPort }
        val src = RssSource(
            requireNotNull(
                SourceDef.parse(JSONObject("""{"name":"dead","kind":"rss","url":"http://127.0.0.1:$deadPort/x"}"""))
            )
        )
        val e = runCatching { src.fetch() }.exceptionOrNull()
        assertTrue("连接拒绝必须抛异常", e != null)
    }
}
