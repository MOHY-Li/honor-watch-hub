package dev.watchhub.app.source

import dev.watchhub.app.core.model.FeedItem

/** HTML 抓取：命名组正则（title/body/url/key）。 */
class RegexSource(def: SourceDef) : BaseSource(def) {

    override fun fetch(): List<FeedItem> {
        val url = requireUrl()
        // v1 的 encoding 配置继续生效：GBK 站点按 GBK 解码，未知编码回退 UTF-8
        val encoding = conf.optString("encoding", "utf-8").trim().ifBlank { "utf-8" }
        val bytes = fetchBytes(url)
        val text = runCatching { String(bytes, charset(encoding)) }
            .getOrDefault(String(bytes, Charsets.UTF_8))
        return parse(text)
    }

    /** 解析一段文本（通常是 HTML）。 */
    internal fun parse(text: String): List<FeedItem> {
        val patternText = conf.optString("pattern")
        if (patternText.isBlank()) throw IllegalStateException("缺少 pattern")

        val flags = if (conf.optBoolean("multiline", false)) {
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)
        } else {
            setOf(RegexOption.DOT_MATCHES_ALL)
        }
        val rx = Regex(patternText, flags)

        // MatchGroupCollection.get(String) requires API 26。
        // minSdk 24 上自己把命名组映射到 1-based 下标。
        val nameIdx = nameGroupIndices(patternText)
        val limit = conf.optInt("limit", 20).coerceAtLeast(0)

        fun named(m: MatchResult, n: String): String {
            val idx = nameIdx[n] ?: -1
            if (idx < 0) return ""
            return m.groups[idx]?.value.orEmpty()
        }

        val out = ArrayList<FeedItem>(minOf(limit, 64))
        for (m in rx.findAll(text)) {
            val title = named(m, "title").ifBlank { m.value }
            val body = named(m, "body")
            val key = named(m, "key").ifBlank { title }
            out.add(
                item(
                    key = key,
                    title = title,
                    body = body,
                    detail = body.ifBlank { title },
                    url = named(m, "url"),
                )
            )
            if (out.size >= limit) break
        }
        return out
    }

    /** 命名组名 → 1-based 捕获组序号。跳过转义、字符类和非捕获/环视/内联旗标组。 */
    private fun nameGroupIndices(pattern: String): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        var groupNo = 0
        var i = 0
        var inClass = false
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> i += 2
                inClass -> {
                    if (c == ']') inClass = false
                    i++
                }
                c == '[' -> {
                    inClass = true
                    i++
                }
                c == '(' -> {
                    if (i + 1 < pattern.length && pattern[i + 1] == '?') {
                        // (?<name>...) 是命名捕获；(?<= / (?<! 是环视，(?: / (?i) 等不占捕获组
                        val isNamed = i + 2 < pattern.length && pattern[i + 2] == '<' &&
                            i + 3 < pattern.length && pattern[i + 3] != '=' && pattern[i + 3] != '!'
                        val end = if (isNamed) pattern.indexOf('>', i + 3) else -1
                        if (end > 0) {
                            groupNo++
                            out[pattern.substring(i + 3, end)] = groupNo
                            i = end + 1
                        } else {
                            i += 2
                        }
                    } else {
                        groupNo++
                        i++
                    }
                }
                else -> i++
            }
        }
        return out
    }
}
