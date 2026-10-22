package dev.watchhub.app.source

import dev.watchhub.app.core.Text
import dev.watchhub.app.core.model.FeedItem
import dev.watchhub.app.core.model.Priority
import dev.watchhub.app.net.Http
import org.json.JSONArray
import org.json.JSONObject

/** 四种采集器。定时提醒不再是采集器，而是 v2 的 Reminder（见 core.model.Reminder）。 */
enum class SourceKind(val wire: String) {
    RSS("rss"),
    JSON("json"),
    REGEX("regex"),
    HASH("hash");

    companion object {
        fun parse(v: String?): SourceKind? =
            entries.firstOrNull { it.wire == v?.trim()?.lowercase() }
    }
}

/**
 * 采集器的配置。直接在 SQLite 里存原始 JSON 文本（用户可以在 App 里手改），
 * 这里只解析出通用字段；各 kind 的专属字段留在 [raw] 里。
 */
data class SourceDef(
    val name: String,
    val kind: SourceKind,
    val enabled: Boolean,
    val intervalSec: Int,
    val priority: Priority,
    val tags: List<String>,
    val notifyOnFirstRun: Boolean,
    val headers: Map<String, String>,
    val timeoutSec: Int,
    val raw: JSONObject,
) {
    fun toJson(): String = raw.toString(2)

    companion object {
        const val MIN_INTERVAL_SEC = 60

        fun parse(raw: JSONObject): SourceDef? {
            val name = raw.optString("name").trim()
            if (name.isBlank()) return null
            val kind = SourceKind.parse(raw.optString("kind")) ?: return null
            val tags = raw.optJSONArray("tags")?.let { a ->
                (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
            } ?: emptyList()
            val headers = raw.optJSONObject("headers")?.let { o ->
                o.keys().asSequence().associateWith { k -> o.optString(k) }
            } ?: emptyMap()
            return SourceDef(
                name = name,
                kind = kind,
                enabled = raw.optBoolean("enabled", true),
                intervalSec = raw.optInt("interval", 900).coerceAtLeast(MIN_INTERVAL_SEC),
                priority = Priority.parse(raw.optString("priority", "default")),
                tags = tags,
                notifyOnFirstRun = raw.optBoolean("notify_on_first_run", false),
                headers = headers,
                timeoutSec = raw.optInt("timeout", 20).coerceIn(5, 120),
                raw = raw,
            )
        }

        fun parseArray(json: String): List<SourceDef> = try {
            Source.fromArray(JSONArray(json))
        } catch (_: Exception) {
            emptyList()
        }

        fun parseAll(json: String): List<SourceDef> {
            val arr = try {
                JSONArray(json)
            } catch (e: Exception) {
                throw IllegalArgumentException("不是合法的 JSON 数组: ${e.message}")
            }
            val out = ArrayList<SourceDef>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(parse(o) ?: throw IllegalArgumentException("第 ${i + 1} 条缺少 name 或 kind 不合法"))
            }
            return out
        }
    }
}

/** 采集器统一接口。只产出 [FeedItem]，绝不直接发通知。 */
interface Source {
    val name: String
    val kind: SourceKind
    val enabled: Boolean
    val intervalSec: Int
    val priority: Priority
    val tags: List<String>
    val notifyOnFirstRun: Boolean

    /** 拉一轮。抛异常表示本轮失败（调用方记录 lastError，不弹通知）。 */
    fun fetch(): List<FeedItem>

    companion object {
        /**
         * 把配置数组解析成 [SourceDef]。注意：这里保留 v1 的行为——非法条目直接跳过，
         * 用于宽松读取（例如迁移旧配置）；严格校验走 [SourceDef.parseAll]。
         */
        fun fromArray(arr: JSONArray?): List<SourceDef> {
            if (arr == null) return emptyList()
            val out = ArrayList<SourceDef>(arr.length())
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { SourceDef.parse(it) }?.let { out.add(it) }
            }
            return out
        }
    }
}

/** 所有采集器的基类：统一 HTTP 入口和 FeedItem 构造，子类只关心解析。 */
abstract class BaseSource(protected val def: SourceDef) : Source {

    final override val name: String = def.name
    final override val kind: SourceKind = def.kind
    final override val enabled: Boolean = def.enabled
    final override val intervalSec: Int = def.intervalSec
    final override val priority: Priority = def.priority
    final override val tags: List<String> = def.tags
    final override val notifyOnFirstRun: Boolean = def.notifyOnFirstRun

    protected val conf: JSONObject get() = def.raw

    protected fun requireUrl(): String {
        val url = conf.optString("url").trim()
        require(url.startsWith("http://") || url.startsWith("https://")) { "缺少合法 url" }
        return url
    }

    protected fun fetchText(url: String): String =
        Http.getText(url, def.headers, def.timeoutSec * 1000)

    /** 原始字节。给需要按 `encoding` 解码的采集器（目前是 regex）用。 */
    protected fun fetchBytes(url: String): ByteArray =
        Http.execute("GET", url, def.headers, timeoutMs = def.timeoutSec * 1000).body

    /**
     * 构造一条事件。key 会加源名前缀，保证不同源不会互相覆盖去重。
     * title/body 在这里就按手表长度裁剪，入库即最终形态。
     */
    protected fun item(
        key: String,
        title: String,
        body: String = "",
        detail: String = "",
        url: String = "",
        publishedAt: Long = 0,
    ): FeedItem = FeedItem(
        source = def.name,
        key = if (key.isBlank()) "" else "${def.name}:${Text.key(key)}",
        title = Text.title(title),
        body = Text.body(body),
        detail = detail.take(8000),
        url = url.take(2000),
        priority = def.priority,
        tags = def.tags,
        publishedAt = publishedAt,
    )
}

/** 根据配置决定用哪个采集器。 */
object SourceFactory {
    fun create(def: SourceDef): Source = when (def.kind) {
        SourceKind.RSS -> RssSource(def)
        SourceKind.JSON -> JsonSource(def)
        SourceKind.REGEX -> RegexSource(def)
        SourceKind.HASH -> HashSource(def)
    }
}
