package dev.watchhub.app.hub

import org.json.JSONArray
import org.json.JSONObject

/** 采集到的一条候选通知（还没入库）。 */
data class Item(
    val key: String,
    val title: String,
    val body: String = "",
    val detail: String = "",
    val url: String = "",
    val priority: String = "default",
    val tags: List<String> = emptyList(),
    val source: String = "",
)

/** 源的状态，用于 UI 展示。 */
data class SourceStatus(
    val name: String,
    val kind: String,
    val enabled: Boolean,
    val intervalSec: Int,
    val lastCheck: Long = 0L,
    val lastOk: Long = 0L,
    val lastError: String? = null,
    val itemsSeen: Int = 0,
    val newLastRun: Int = 0,
)

/**
 * 一个「源」= 一段配置 + 一个抓取策略。
 *
 * 五种 kind，和 PC 版服务端一一对应：
 *   rss   RSS/Atom
 *   json  任意 JSON API（支持字段映射与模板）
 *   regex 从 HTML 里用正则抠条目
 *   hash  整页指纹，变了就报（抢票/补货/公告）
 *   cron  定时提醒（本地计算，不联网）
 */
sealed class Source {
    abstract val name: String
    abstract val raw: JSONObject

    val enabled: Boolean get() = raw.optBoolean("enabled", true)
    val intervalSec: Int get() = raw.optInt("interval", 900).coerceAtLeast(60)
    val priority: String get() = raw.optString("priority", "default")
    val tags: List<String> get() = _tags(raw.optJSONArray("tags"))

    /** 首次运行是否推送已有条目。默认 false（只播种，避免历史刷屏）。cron 恒为 true。 */
    open val notifyOnFirstRun: Boolean get() = raw.optBoolean("notify_on_first_run", false)

    abstract fun fetch(): List<Item>

    companion object {
        fun from(o: JSONObject): Source? {
            val name = o.optString("name").ifBlank { return null }
            return when (o.optString("kind", "rss")) {
                "rss" -> RssSource(name, o)
                "json" -> JsonSource(name, o)
                "regex" -> RegexSource(name, o)
                "hash" -> HashSource(name, o)
                "cron" -> CronSource(name, o)
                else -> null
            }
        }

        fun fromArray(a: JSONArray?): List<Source> {
            if (a == null) return emptyList()
            val out = ArrayList<Source>(a.length())
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                from(o)?.let { out.add(it) }
            }
            return out
        }

        internal fun _tags(a: JSONArray?): List<String> {
            if (a == null) return emptyList()
            return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
        }

        fun clip(s: String?, n: Int): String {
            if (s == null) return ""
            val clean = s.replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            return if (clean.length <= n) clean else clean.substring(0, n - 1) + "…"
        }

        /** 把数据行按 `{a.b}` 模板填充。 */
        fun fmt(template: String, row: JSONObject): String =
            Regex("\\{([\\w.]+)\\}").replace(template) { m ->
                JsonSource.dig(row, m.groupValues[1])?.toString() ?: ""
            }
    }
}
