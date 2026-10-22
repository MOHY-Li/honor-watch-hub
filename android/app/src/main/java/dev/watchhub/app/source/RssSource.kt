package dev.watchhub.app.source

import android.annotation.SuppressLint
import dev.watchhub.app.core.Text
import dev.watchhub.app.core.model.FeedItem
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
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
                publishedAt = publishedAtOf(fields),
            )
        }
    }

    companion object {

        /** 发布时间的候选字段，按优先级排列（本地名已小写）。 */
        private val DATE_KEYS = listOf("pubdate", "published", "updated", "date")

        private val RFC_1123: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME

        /**
         * 宽松的 RFC 1123：跳过「星期, 」前缀，只解析日期 + 时间 + 时区。
         *
         * 为什么要放宽：源站偶尔把星期写错（例如日期对不上星期），严格解析会直接失败；
         * 星期本身对时间戳没有信息量，丢掉更稳。
         */
        // core library desugaring 会在 API 24/25 上提供 java.time；lint 的 NewApi 没感知 desugar，
        // 这里是对 false positive 的抑制，不是真的用了新系统 API。
        @SuppressLint("NewApi")
        private val RFC_1123_LENIENT: DateTimeFormatter = DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("dd MMM yyyy HH:mm:ss ")
            .appendZoneOrOffsetId()
            .toFormatter(Locale.ENGLISH)

        private val ISO_OFFSET: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        private val ISO_LOCAL_DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
        private val ISO_LOCAL_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

        /** 从 item 字段里取第一个能解析出的发布时间，都没有则返回 0。 */
        private fun publishedAtOf(fields: Map<String, String>): Long {
            for (key in DATE_KEYS) {
                val raw = fields[key] ?: continue
                val at = parseDate(raw)
                if (at > 0) return at
            }
            return 0
        }

        /**
         * 解析发布时间为 epoch millis。支持 RFC 1123、ISO-8601（含 Z / 偏移）、
         * 以及可选的 `yyyy-MM-dd`（按 UTC 当天零点解释）。
         *
         * 解析失败一律返回 0，绝不抛异常——脏日期不应该让整轮采集失败。
         */
        internal fun parseDate(raw: String?): Long {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return 0
            // ISO-8601：2026-10-21T09:30:00Z / 2026-10-21T17:30:00+08:00
            runCatching { return OffsetDateTime.parse(s, ISO_OFFSET).toInstant().toEpochMilli() }
            runCatching { return Instant.parse(s).toEpochMilli() }
            // RFC 1123：Mon, 21 Oct 2026 09:30:00 GMT（也兼容 +0800 这类偏移）
            runCatching {
                val rest = if (s.contains(',')) s.substringAfter(',').trim() else s
                return ZonedDateTime.parse(rest, RFC_1123_LENIENT).toInstant().toEpochMilli()
            }
            runCatching { return Instant.from(RFC_1123.parse(s)).toEpochMilli() }
            // 只有日期：2026-10-21
            runCatching {
                return LocalDate.parse(s, ISO_LOCAL_DATE)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli()
            }
            // 没写时区的本地时间：2026-10-21T09:30:00，按 UTC 兜底
            runCatching {
                return LocalDateTime.parse(s, ISO_LOCAL_DATE_TIME)
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli()
            }
            return 0
        }
    }
}
