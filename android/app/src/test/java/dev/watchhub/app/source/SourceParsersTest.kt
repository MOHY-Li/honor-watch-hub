package dev.watchhub.app.source

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 采集器解析层的纯 JVM 单测：不联网，直接喂文本给各 Source 的 parse()。
 * HTTP 行为在 HttpSourcesTest 里用本地 HttpServer 覆盖。
 */
class SourceParsersTest {

    private fun def(json: String): SourceDef =
        requireNotNull(SourceDef.parse(JSONObject(json))) { "测试配置不合法: $json" }

    /** 构造 JSON 配置时在结尾补一段额外字段，extra 以逗号开头，例如 `""","limit":1"""`。 */
    private fun rss(extra: String = ""): RssSource =
        RssSource(def("""{"name":"feed","kind":"rss","url":"http://example.com/feed.xml"$extra}"""))

    private fun json(name: String, extra: String = ""): JsonSource =
        JsonSource(def("""{"name":"$name","kind":"json","url":"http://example.com/api"$extra}"""))

    private fun regex(name: String, pattern: String, extra: String = ""): RegexSource =
        RegexSource(
            def(
                """{"name":"$name","kind":"regex","url":"http://example.com/page","pattern":""" +
                    JSONObject.quote(pattern) + extra + "}"
            )
        )

    private fun hash(name: String, extra: String = ""): HashSource =
        HashSource(def("""{"name":"$name","kind":"hash","url":"http://example.com/page"$extra}"""))

    private val RSS2 = """
        <?xml version="1.0" encoding="utf-8"?>
        <rss version="2.0"><channel>
          <title>测试源</title>
          <item>
            <title>第1话 开播</title>
            <link>http://example.com/1</link>
            <guid>ep-1</guid>
            <description>&lt;p&gt;已更新 EP1&lt;/p&gt;</description>
          </item>
          <item>
            <title>第2话</title>
            <link>http://example.com/2</link>
            <guid>ep-2</guid>
            <description>已更新 EP2</description>
          </item>
        </channel></rss>
    """.trimIndent()

    private val ATOM = """
        <?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>Atom 源</title>
          <entry>
            <title>Atom 条目</title>
            <id>urn:1</id>
            <link rel="alternate" href="http://example.com/a1"/>
            <summary>摘要内容</summary>
          </entry>
        </feed>
    """.trimIndent()

    private val JSON_API = """
        {"data":[
          {"id":11,"name_cn":"第一集","ep":1,"airdate":"2024-10-02"},
          {"id":12,"name_cn":"第二集","ep":2,"airdate":"2024-10-09"}
        ]}
    """.trimIndent()

    private val HTML = """
        <html><body>
        <a href="/news/1">新闻一</a>
        <a href="/news/2">新闻二</a>
        <a href="/news/3">新闻三</a>
        </body></html>
    """.trimIndent()

    private val LINK_PATTERN = """<a href="(?<url>[^"]+)">(?<title>[^<]+)</a>"""

    // ---------------------------------------------------------------- RSS / Atom

    @Test
    fun rss2ParsesItems() {
        val items = rss(""","limit":10""").parse(RSS2)
        assertEquals(2, items.size)

        val first = items[0]
        assertEquals("第1话 开播", first.title)
        assertEquals("已更新 EP1", first.body)
        assertEquals("http://example.com/1", first.url)
        assertEquals("feed:ep-1", first.key)
        assertEquals("feed", first.source)
        assertEquals("已更新 EP1", first.detail)
    }

    @Test
    fun atomParsesEntries() {
        val items = rss().parse(ATOM)
        assertEquals(1, items.size)
        val item = items.single()
        assertEquals("Atom 条目", item.title)
        assertEquals("摘要内容", item.body)
        assertEquals("http://example.com/a1", item.url)
        assertEquals("feed:urn:1", item.key)
    }

    @Test
    fun rssHonorsLimit() {
        val items = rss(""","limit":1""").parse(RSS2)
        assertEquals(1, items.size)
        assertEquals("第1话 开播", items[0].title)
    }

    @Test
    fun rssFallsBackToLinkWhenTitleMissing() {
        val xml = """<rss><channel><item><link>http://example.com/x</link></item></channel></rss>"""
        val item = rss().parse(xml).single()
        assertEquals("http://example.com/x", item.title)
        assertEquals("feed:http://example.com/x", item.key)
    }

    @Test
    fun rssRejectsMalformedXml() {
        val e = runCatching { rss().parse("<rss><channel>") }.exceptionOrNull()
        assertTrue("非法 XML 必须抛异常", e != null)
    }

    // ---------------------------------------------------------------------- JSON

    @Test
    fun jsonListPathFieldsAndTemplates() {
        val src = json(
            "bgm",
            """
            ,"list_path":"data",
            "fields":{"title":"name_cn","key":"id","body":"airdate"},
            "title_format":"{name_cn} · 第{ep}话",
            "body_format":"{airdate} · 第{ep}话",
            "detail_format":"{airdate} {name_cn}"
            """.trimIndent().let { it.replace("\n", "") }
        )
        val items = src.parse(JSON_API)
        assertEquals(2, items.size)
        assertEquals("第一集 · 第1话", items[0].title)
        assertEquals("2024-10-02 · 第1话", items[0].body)
        assertEquals("2024-10-02 第一集", items[0].detail)
        assertEquals("bgm:11", items[0].key)
        assertEquals("bgm:12", items[1].key)
    }

    @Test
    fun jsonRootArrayWithEmptyListPath() {
        val src = json("notes", ""","fields":{"title":"title","key":"id"}""")
        val items = src.parse("""[{"id":"n1","title":"第一条"},{"id":"n2","title":"第二条"}]""")
        assertEquals(2, items.size)
        assertEquals("notes:n1", items[0].key)
        assertEquals("第一条", items[0].title)
        // detail 缺省取 body，body 为空时取 title
        assertEquals("第一条", items[0].detail)
    }

    @Test
    fun jsonKeyFallsBackToTitle() {
        val src = json("nokey", ""","fields":{"title":"title"}""")
        val item = src.parse("""[{"title":"只有标题"}]""").single()
        assertEquals("nokey:只有标题", item.key)
    }

    @Test
    fun jsonHonorsLimit() {
        val src = json("limited", ""","fields":{"title":"title"},"limit":1""")
        assertEquals(1, src.parse("""[{"title":"a"},{"title":"b"}]""").size)
    }

    @Test
    fun jsonInvalidListPathThrows() {
        val src = json("bad", ""","list_path":"nope","fields":{"title":"t"}""")
        val e = runCatching { src.parse(JSON_API) }.exceptionOrNull()
        assertTrue("list_path 不存在必须抛异常", e is IllegalStateException)
    }

    @Test
    fun jsonDigHandlesNestedPaths() {
        val root = JSONObject("""{"data":{"items":[{"t":"a"},{"t":"b"}]}}""")
        val arr = JsonSource.dig(root, "data.items") as JSONArray
        assertEquals(2, arr.length())
        assertEquals("b", JsonSource.dig(root, "data.items.1.t"))
        assertNull(JsonSource.dig(root, "data.missing.x"))
        assertNull(JsonSource.dig(null, "a.b"))
    }

    // --------------------------------------------------------------------- Regex

    @Test
    fun regexParsesNamedGroups() {
        val items = regex("news", LINK_PATTERN, ""","limit":2""").parse(HTML)
        assertEquals(2, items.size)
        assertEquals("新闻一", items[0].title)
        assertEquals("/news/1", items[0].url)
        assertEquals("news:新闻一", items[0].key)
        assertEquals("新闻二", items[1].title)
        assertEquals("/news/2", items[1].url)
    }

    @Test
    fun regexHonorsLimit() {
        val items = regex("news", LINK_PATTERN, ""","limit":1""").parse(HTML)
        assertEquals(1, items.size)
    }

    @Test
    fun regexFallsBackToWholeMatchAndTitle() {
        val pattern = """<b>(?<body>第\d+条)</b>"""
        val item = regex("fallback", pattern).parse("<b>第1条</b>").single()
        // 缺 title 时用整段匹配；展示前仍会经 Text.plain 去标签
        assertEquals("第1条", item.title)
        assertEquals("第1条", item.body)
        assertEquals("fallback:<b>第1条</b>", item.key)
    }

    @Test
    fun regexNamedGroupIndicesSkipLookaroundAndNonCapturingGroups() {
        // 环视和非捕获组不占捕获组序号；命名组必须映射到正确的下标
        val pattern = """(?<=>)(?:<i>)?(?<title>[^<]+)(?:</i>)?(?=</p>)"""
        val html = "<p><i>标题A</i></p><p><i>标题B</i></p>"
        val items = regex("idx", pattern).parse(html)
        assertEquals(listOf("标题A", "标题B"), items.map { it.title })
    }

    @Test
    fun regexSupportsMultilineConfig() {
        val pattern = """^<b>(?<title>[^<]+)</b>$"""
        val text = "<b>第一行</b>\n<b>第二行</b>"
        val without = regex("noml", pattern).parse(text)
        assertTrue("未开 multiline 时 ^/$ 不应按行匹配", without.size != 2)
        val with = regex("ml", pattern, ""","multiline":true""").parse(text)
        assertEquals(listOf("第一行", "第二行"), with.map { it.title })
    }

    @Test
    fun regexMissingPatternThrows() {
        val src = regex("empty", "")
        val e = runCatching { src.parse(HTML) }.exceptionOrNull()
        assertTrue(e is IllegalStateException)
    }

    // ---------------------------------------------------------------------- Hash

    @Test
    fun hashSourceHashesWholePage() {
        val a = hash("price").parse("v1").single()
        val b = hash("price").parse("v1").single()
        val c = hash("price").parse("v2").single()

        assertEquals("price 有更新", a.title)
        assertEquals("页面内容发生变化", a.body)
        assertEquals("price", a.source)
        assertEquals(a.key, b.key)
        assertTrue("内容变化后 key 必须不同", a.key != c.key)
        assertTrue("key 必须是源名 + 16 位十六进制", Regex("^price:[0-9a-f]{16}$").matches(a.key))
        assertEquals("http://example.com/page", a.url)
    }

    @Test
    fun hashSourceUsesConfiguredTitleAndBody() {
        val item = hash("stock", ",\"title\":\"补货了\",\"body\":\"库存有变化\",\"detail\":\"详情\"")
            .parse("x")
            .single()
        assertEquals("补货了", item.title)
        assertEquals("库存有变化", item.body)
        assertEquals("详情", item.detail)
    }

    // ------------------------------------------------------- 文本裁剪与 key 前缀约束

    @Test
    fun titleAndBodyAreClippedToWatchLimits() {
        val longTitle = "标".repeat(100)
        val longBody = "正".repeat(300)
        val xml = """<rss><channel><item><title>$longTitle</title>""" +
            """<guid>k</guid><description>$longBody</description></item></channel></rss>"""

        val item = rss().parse(xml).single()
        assertEquals(40, item.title.length)
        assertTrue(item.title.endsWith("…"))
        assertEquals(120, item.body.length)
        assertTrue(item.body.endsWith("…"))
        // detail 保留长文本，不参与手表裁剪
        assertEquals(300, item.detail.length)
    }

    @Test
    fun keyCarriesSourceNamePrefix() {
        val items = listOf(
            rss().parse(RSS2).first(),
            json("json-src", ""","fields":{"title":"title"}""").parse("""[{"title":"a"}]""").first(),
            regex("regex-src", LINK_PATTERN).parse(HTML).first(),
            hash("hash-src").parse("x").first(),
        )
        assertEquals(
            listOf("feed", "json-src", "regex-src", "hash-src"),
            items.map { it.key.substringBefore(':') },
        )
        items.forEach { assertTrue("key 必须带源名前缀: ${it.key}", it.key.contains(':')) }
    }

    @Test
    fun missingUrlThrowsForEverySourceKind() {
        assertThrows(IllegalArgumentException::class.java) {
            RssSource(def("""{"name":"a","kind":"rss"}""")).fetch()
        }
        assertThrows(IllegalArgumentException::class.java) {
            JsonSource(def("""{"name":"b","kind":"json"}""")).fetch()
        }
        assertThrows(IllegalArgumentException::class.java) {
            RegexSource(def("""{"name":"c","kind":"regex","pattern":"x"}""")).fetch()
        }
        assertThrows(IllegalArgumentException::class.java) {
            HashSource(def("""{"name":"d","kind":"hash"}""")).fetch()
        }
        // 非 http(s) 的 url 同样拒绝
        assertThrows(IllegalArgumentException::class.java) {
            RssSource(def("""{"name":"e","kind":"rss","url":"ftp://example.com/x"}""")).fetch()
        }
    }

    @Test
    fun sourceFactoryBuildsTheRightClass() {
        val kinds = mapOf(
            "rss" to RssSource::class.java,
            "json" to JsonSource::class.java,
            "regex" to RegexSource::class.java,
            "hash" to HashSource::class.java,
        )
        for ((kind, cls) in kinds) {
            val src = SourceFactory.create(
                def("""{"name":"x","kind":"$kind","url":"http://example.com/x"}""")
            )
            assertEquals(cls, src.javaClass)
        }
    }
}
