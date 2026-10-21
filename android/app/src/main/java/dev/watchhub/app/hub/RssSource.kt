package dev.watchhub.app.hub

import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** RSS / Atom。用 JDK 自带的 DOM 解析器（Android 上也自带），便于单测。 */
class RssSource(override val name: String, override val raw: JSONObject) : Source() {

    override fun fetch(): List<Item> {
        val bytes = Http.getBytes(raw.optString("url"))
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // 关掉外部实体，避免 XXE（虽然只抓自己的源，但没理由留口子）
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val limit = raw.optInt("limit", 20)

        // item（RSS2）和 entry（Atom）都按本地名匹配
        val nodes = doc.getElementsByTagName("*")
        val entries = ArrayList<Element>()
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            val ln = e.tagName.substringAfterLast(':').lowercase()
            if (ln == "item" || ln == "entry") entries.add(e)
        }

        val out = ArrayList<Item>(minOf(entries.size, limit))
        for (e in entries.take(limit)) {
            val d = HashMap<String, String>()
            var link = ""
            val kids = e.childNodes
            for (i in 0 until kids.length) {
                val c = kids.item(i) as? Element ?: continue
                val ln = c.tagName.substringAfterLast(':').lowercase()
                if (ln == "link") {
                    val t = c.textContent?.trim().orEmpty()
                    link = t.ifBlank { c.getAttribute("href") }
                    continue
                }
                if (!d.containsKey(ln)) {
                    val t = c.textContent?.trim().orEmpty()
                    if (t.isNotEmpty()) d[ln] = t
                }
            }
            val title = d["title"].orEmpty().ifBlank { link }
            val idKey = d["guid"] ?: d["id"] ?: link.ifBlank { title }
            val body = d["description"] ?: d["summary"] ?: d["content"] ?: ""
            out.add(
                Item(
                    key = "$name:${sha1(idKey)}",
                    title = Source.clip(title, 120),
                    body = Source.clip(body, 400),
                    detail = Source.clip(body, 4000),
                    url = link,
                    priority = priority,
                    tags = tags.ifEmpty { listOf(name) },
                    source = name,
                )
            )
        }
        return out
    }

    companion object {
        fun sha1(s: String): String =
            java.security.MessageDigest.getInstance("SHA-1")
                .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }
}
