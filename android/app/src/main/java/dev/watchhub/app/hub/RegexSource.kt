package dev.watchhub.app.hub

import org.json.JSONObject

/** 网页抓取：用命名组的正则从 HTML 里抠条目。 */
class RegexSource(override val name: String, override val raw: JSONObject) : Source() {

    override fun fetch(): List<Item> {
        val enc = raw.optString("encoding", "utf-8")
        val html = Http.getBytes(raw.optString("url")).toString(charset(enc))
        val flags = if (raw.optBoolean("multiline", false))
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)
        else setOf(RegexOption.DOT_MATCHES_ALL)

        val rx = Regex(raw.optString("pattern"), flags)
        val limit = raw.optInt("limit", 20)
        val out = ArrayList<Item>(limit)

        for (m in rx.findAll(html)) {
            val g = m.groups
            fun named(n: String): String =
                runCatching { g[n]?.value.orEmpty() }.getOrDefault("")
            val title = named("title").ifBlank { m.value }
            val key = named("key").ifBlank { title }
            out.add(
                Item(
                    key = "$name:${RssSource.sha1(key)}",
                    title = Source.clip(title, 120),
                    body = Source.clip(named("body"), 400),
                    detail = Source.clip(named("body").ifBlank { title }, 4000),
                    url = named("url"),
                    priority = priority,
                    tags = tags.ifEmpty { listOf(name) },
                    source = name,
                )
            )
            if (out.size >= limit) break
        }
        return out
    }
}
