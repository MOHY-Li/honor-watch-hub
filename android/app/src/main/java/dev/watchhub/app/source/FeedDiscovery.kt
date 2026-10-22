package dev.watchhub.app.source

import dev.watchhub.app.net.Http
import java.net.URI

/**
 * RSS/Atom 订阅源自动发现。
 *
 * 用户通常只会粘贴网站首页（`https://example.com/blog`），而不是 feed 直链。
 * 标准做法是读 HTML 的 `<link rel="alternate" type="application/rss+xml" href="...">`。
 * 这里实现最小版本：直链直接认，网页就解析 link 标签，相对地址用页面 URL 补全。
 *
 * 纯函数部分（[parseLinks]/[resolveUrl]/[looksLikeFeed]）有单测，不联网。
 */
object FeedDiscovery {

    private val LINK_TAG = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("""([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")

    /** 从 HTML 里找所有订阅源链接。结果去重且保持文档顺序。 */
    fun parseLinks(html: String, pageUrl: String): List<String> {
        val out = LinkedHashSet<String>()
        LINK_TAG.findAll(html).forEach { match ->
            val attrs = attributes(match.value)
            val rel = attrs["rel"].orEmpty().lowercase()
            val type = attrs["type"].orEmpty().lowercase()
            val href = attrs["href"].orEmpty()
            if (href.isBlank()) return@forEach
            if (!rel.split(Regex("\\s+")).contains("alternate")) return@forEach
            // 只认 RSS/Atom：RssSource 用 XML DOM 解析，JSON Feed 目前不支持，
            // 误报成可订阅会在抓取阶段一直失败，不如在发现阶段就跳过。
            val isFeed = type.contains("application/rss+xml") ||
                type.contains("application/atom+xml")
            if (!isFeed) return@forEach
            out.add(resolveUrl(pageUrl, href))
        }
        return out.toList()
    }

    /** 用页面 URL 补全相对地址，例如 `/feed.xml` → `https://host/feed.xml`。 */
    fun resolveUrl(base: String, href: String): String = try {
        URI(base).resolve(href.trim()).toString()
    } catch (_: Exception) {
        href
    }

    /** 粗略判断响应本身是不是 feed。 */
    fun looksLikeFeed(contentType: String?, bodyHead: String): Boolean {
        val ct = contentType?.lowercase().orEmpty()
        if (ct.contains("rss") || ct.contains("atom") || ct.contains("xml")) return true
        val head = bodyHead.trimStart().lowercase()
        return head.startsWith("<?xml") || head.startsWith("<rss") || head.startsWith("<feed")
    }

    /**
     * 抓一个地址并发现订阅源。阻塞网络调用，必须在后台线程使用。
     *
     * @return feed URL 列表；空列表表示没发现
     */
    fun discover(pageUrl: String, headers: Map<String, String> = emptyMap()): List<String> {
        val response = Http.execute("GET", pageUrl, headers)
        val text = response.text()
        if (looksLikeFeed(response.contentType, text.take(512))) return listOf(pageUrl)
        return parseLinks(text, pageUrl)
    }

    private fun attributes(tag: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ATTR.findAll(tag).forEach { m ->
            val name = m.groupValues[1].lowercase()
            val value = m.groupValues[2].ifEmpty { m.groupValues[3].ifEmpty { m.groupValues[4] } }
            if (name.isNotEmpty() && !out.containsKey(name)) out[name] = value
        }
        return out
    }
}
