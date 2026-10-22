package dev.watchhub.app.source

import dev.watchhub.app.testing.TestHttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RSS/Atom 订阅源自动发现。
 *
 * 纯函数部分（parseLinks/resolveUrl/looksLikeFeed）直接喂文本；
 * [FeedDiscovery.discover] 的网络用例用 [TestHttpServer] 起本地 HTTP 服务。
 */
class FeedDiscoveryTest {

    private lateinit var server: TestHttpServer

    companion object {
        private const val RSS_XML =
            """<?xml version="1.0"?><rss version="2.0"><channel><title>t</title>""" +
                """<item><title>a</title></item></channel></rss>"""
    }

    @Before
    fun setUp() {
        server = TestHttpServer()
    }

    @After
    fun tearDown() {
        server.close()
    }

    // ------------------------------------------------------------------ parseLinks

    @Test
    fun rssLinkIsDiscoveredAndRelativeHrefResolved() {
        val html = """<html><head>""" +
            """<link rel="alternate" type="application/rss+xml" title="RSS" href="/feed.xml">""" +
            """</head><body></body></html>"""
        assertEquals(
            listOf("https://example.com/feed.xml"),
            FeedDiscovery.parseLinks(html, "https://example.com/blog/index.html"),
        )
    }

    @Test
    fun atomLinkIsDiscovered() {
        val html = """<html><head>""" +
            """<link rel="alternate" type="application/atom+xml" href="https://example.com/atom.xml">""" +
            """</head></html>"""
        assertEquals(
            listOf("https://example.com/atom.xml"),
            FeedDiscovery.parseLinks(html, "https://example.com/blog/"),
        )
    }

    @Test
    fun singleQuotedAttributesAreParsed() {
        val html = """<html><head>""" +
            """<link rel='alternate' type='application/atom+xml' href='../atom.xml'>""" +
            """</head></html>"""
        assertEquals(
            listOf("https://example.com/blog/atom.xml"),
            FeedDiscovery.parseLinks(html, "https://example.com/blog/post/"),
        )
    }

    @Test
    fun multiValuedRelContainingAlternateIsAccepted() {
        val html = """<html><head>""" +
            """<link rel="alternate stylesheet" type="application/rss+xml" href="/feed.xml">""" +
            """</head></html>"""
        assertEquals(
            listOf("https://example.com/feed.xml"),
            FeedDiscovery.parseLinks(html, "https://example.com/blog/"),
        )
    }

    @Test
    fun nonAlternateOrNonFeedLinksAreIgnored() {
        val html = """<html><head>""" +
            """<link rel="stylesheet" type="application/rss+xml" href="/style.xml">""" +
            """<link rel="canonical" type="application/rss+xml" href="/canonical.xml">""" +
            """<link rel="alternate" type="text/html" href="/page.html">""" +
            """<link rel="alternate" type="application/rss+xml">""" +
            """</head></html>"""
        assertTrue(FeedDiscovery.parseLinks(html, "https://example.com/blog/").isEmpty())
    }

    @Test
    fun duplicateLinksAreDeduplicated() {
        val html = """<html><head>""" +
            """<link rel="alternate" type="application/rss+xml" href="/feed.xml">""" +
            """<link rel="alternate" type="application/rss+xml" href="/feed.xml">""" +
            """</head></html>"""
        assertEquals(
            listOf("https://example.com/feed.xml"),
            FeedDiscovery.parseLinks(html, "https://example.com/blog/"),
        )
    }

    // ------------------------------------------------------------------- resolveUrl

    @Test
    fun resolveUrlHandlesRootRelativeRelativeAndAbsolute() {
        assertEquals(
            "https://example.com/feed.xml",
            FeedDiscovery.resolveUrl("https://example.com/blog/index.html", "/feed.xml"),
        )
        assertEquals(
            "https://example.com/blog/feed.xml",
            FeedDiscovery.resolveUrl("https://example.com/blog/", "feed.xml"),
        )
        assertEquals(
            "https://cdn.example.com/feed.xml",
            FeedDiscovery.resolveUrl("https://example.com/blog/", "https://cdn.example.com/feed.xml"),
        )
    }

    // ---------------------------------------------------------------- looksLikeFeed

    @Test
    fun looksLikeFeedDetectsByContentType() {
        assertTrue(FeedDiscovery.looksLikeFeed("application/rss+xml; charset=utf-8", "<html></html>"))
        assertTrue(FeedDiscovery.looksLikeFeed("application/atom+xml", "<html></html>"))
        assertTrue(FeedDiscovery.looksLikeFeed("application/xml", "{}"))
        assertTrue(FeedDiscovery.looksLikeFeed("text/xml", "not a feed body"))
    }

    @Test
    fun looksLikeFeedDetectsByBodyHead() {
        assertTrue(FeedDiscovery.looksLikeFeed(null, """<?xml version="1.0"?><rss version="2.0"></rss>"""))
        assertTrue(FeedDiscovery.looksLikeFeed("text/html", "\n  <feed xmlns=\"http://www.w3.org/2005/Atom\">"))
        assertTrue(FeedDiscovery.looksLikeFeed("text/plain", "<rss>"))
        assertFalse(FeedDiscovery.looksLikeFeed("text/html", "<!DOCTYPE html><html><body>hi</body></html>"))
    }

    // --------------------------------------------------------------------- discover

    @Test
    fun discoverReturnsSelfWhenContentTypeIsFeed() {
        server.route("/direct.xml") {
            TestHttpServer.Response.text(
                "anything",
                headers = mapOf("Content-Type" to "application/rss+xml; charset=utf-8"),
            )
        }
        val url = server.baseUrl + "/direct.xml"
        assertEquals(listOf(url), FeedDiscovery.discover(url))
    }

    @Test
    fun discoverReturnsSelfWhenBodyLooksLikeXmlFeed() {
        // 没有可用的 Content-Type，靠 body 头部识别
        server.route("/sniff", "\n  $RSS_XML")
        val url = server.baseUrl + "/sniff"
        assertEquals(listOf(url), FeedDiscovery.discover(url))
    }

    @Test
    fun discoverFindsFeedLinksOnHtmlPage() {
        server.route(
            "/blog/",
            """<html><head>""" +
                """<link rel="alternate" type="application/rss+xml" href="/feed.xml">""" +
                """<link rel="alternate" type="application/atom+xml" href="atom.xml">""" +
                """</head><body></body></html>""",
        )
        assertEquals(
            listOf(server.baseUrl + "/feed.xml", server.baseUrl + "/blog/atom.xml"),
            FeedDiscovery.discover(server.baseUrl + "/blog/"),
        )
    }

    @Test
    fun discoverReturnsEmptyForPageWithoutFeedLinks() {
        server.route("/plain", "<html><body><a href=\"/x\">x</a></body></html>")
        assertTrue(FeedDiscovery.discover(server.baseUrl + "/plain").isEmpty())
    }
}
