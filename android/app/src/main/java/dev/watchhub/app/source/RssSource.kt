package dev.watchhub.app.source

import dev.watchhub.app.core.Text
import dev.watchhub.app.core.model.FeedItem
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/** RSS 2.0 / Atom。用 JDK 自带的 DOM 解析器（Android 上也自带），便于纯 JVM 单测。 */
class RssSource(def: SourceDef) : BaseSource(def) {

    override fun fetch(): List<FeedItem> {
        val url = requireUrl()
        return parse(fetchText(url))
    }

    /** 解析一段 XML。RSS 的 `<item>` 和 Atom 的 `<entry>` 都支持。 */
    internal fun parse(xml: String): List<FeedItem> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // 关掉外部实体，避免 XXE（虽然只抓自己的源，但没理由留口子）
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))

        // item（RSS2）和 entry（Atom）都按本地名匹配
        val nodes = doc.getElementsByTagName("*")
        val entries = ArrayList<Element>()
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            val ln = e.tagName.substringAfterLast(':').lowercase()
            if (ln == "item" || ln == "entry") entries.add(e)
        }

        val limit = conf.optInt("limit", 20).coerceAtLeast(0)
        return entries.take(limit).map { e ->
            var link = ""
            val fields = HashMap<String, String>()
            val kids = e.childNodes
            for (i in 0 until kids.length) {
                val c = kids.item(i) as? Element ?: continue
                val ln = c.tagName.substringAfterLast(':').lowercase()
                if (ln == "link") {
                    val t = c.textContent?.trim().orEmpty()
                    link = t.ifBlank { c.getAttribute("href") }
                    continue
                }
                if (!fields.containsKey(ln)) {
                    val t = c.textContent?.trim().orEmpty()
                    if (t.isNotEmpty()) fields[ln] = t
                }
            }

            val title = fields["title"].orEmpty().ifBlank { link }
            // v1 语义：guid → id → link → title
            val key = fields["guid"] ?: fields["id"] ?: link.ifBlank { title }
            val body = fields["description"] ?: fields["summary"] ?: fields["content"] ?: ""
            item(
                key = key,
                title = title,
                body = body,
                detail = Text.plain(body),
                url = link,
            )
        }
    }
}
