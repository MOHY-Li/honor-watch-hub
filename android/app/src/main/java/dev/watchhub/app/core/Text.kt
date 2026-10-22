package dev.watchhub.app.core

/**
 * 文本处理。手表的镜像通道对长度极其敏感，所有送出去的文字必须经过这里。
 *
 * 规则见 AGENTS.md：标题 ≤40 字符、正文 ≤120 字符。
 */
object Text {

    const val TITLE_MAX = 40
    const val BODY_MAX = 120

    /** 去 HTML 标签、压缩空白。用于 RSS/正则抓回来的脏文本。 */
    fun plain(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        return s.replace(Regex("<[^>]+>"), " ")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("&amp;"), "&")
            .replace(Regex("&lt;"), "<")
            .replace(Regex("&gt;"), ">")
            .replace(Regex("&quot;"), "\"")
            .replace(Regex("&#39;"), "'")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** 裁剪并加省略号。超出时保留 n-1 个字符 + 一个省略号，保证总长 ≤ n。 */
    fun clip(s: String?, n: Int): String {
        val clean = plain(s)
        if (n <= 0) return ""
        if (clean.length <= n) return clean
        return clean.substring(0, n - 1) + "…"
    }

    fun title(s: String?): String = clip(s, TITLE_MAX)

    fun body(s: String?): String = clip(s, BODY_MAX)

    /** 通知 key 的安全化：去掉空白和可能破坏日志的字符。 */
    fun key(s: String?): String =
        (s ?: "").replace(Regex("\\s+"), "-").take(256)
}
