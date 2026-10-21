package dev.watchhub.app.hub

import org.json.JSONObject

/** HTML scraper using named-group regex. */
class RegexSource(override val name: String, override val raw: JSONObject) : Source() {

    override fun fetch(): List<Item> {
        val enc = raw.optString("encoding", "utf-8")
        val html = Http.getBytes(raw.optString("url")).toString(charset(enc))

        val flags = if (raw.optBoolean("multiline", false))
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)
        else setOf(RegexOption.DOT_MATCHES_ALL)

        val patternText = raw.optString("pattern")
        val rx = Regex(patternText, flags)

        // MatchGroupCollection.get(String) requires API 26.
        // On API 24/25 we map names to indices ourselves via java.util.regex.Pattern.
        val nameIdx = nameGroupIndices(patternText)

        val limit = raw.optInt("limit", 20)
        val out = ArrayList<Item>(limit)

        for (m in rx.findAll(html)) {
            fun named(n: String): String {
                val idx = nameIdx[n] ?: -1
                if (idx < 0) return ""
                return runCatching { m.groups[idx]?.value.orEmpty() }.getOrDefault("")
            }
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

    /** Map named group names to 1-based positions. */
    private fun nameGroupIndices(pattern: String): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        var groupNo = 0
        var k = 0
        while (k < pattern.length) {
            val c = pattern[k]
            if (c == '\\') { k += 2; continue }
            if (c == '(') {
                if (k + 1 < pattern.length && pattern[k + 1] == '?') {
                    if (k + 2 < pattern.length && pattern[k + 2] == '<') {
                        val end = pattern.indexOf('>', k + 3)
                        if (end > 0) {
                            groupNo++
                            out[pattern.substring(k + 3, end)] = groupNo
                        }
                    }
                } else {
                    groupNo++
                }
            }
            k++
        }
        return out
    }
}
