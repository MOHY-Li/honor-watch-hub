package dev.watchhub.app.source

import dev.watchhub.app.core.model.FeedItem
import org.json.JSONArray
import org.json.JSONObject

/** 任意 JSON API：`list_path` 定位数组、`fields` 做字段映射、`*_format` 做模板。 */
class JsonSource(def: SourceDef) : BaseSource(def) {

    override fun fetch(): List<FeedItem> {
        val url = requireUrl()
        return parse(fetchText(url))
    }

    /** 解析一段 JSON 文本。 */
    internal fun parse(text: String): List<FeedItem> {
        val trimmed = text.trimStart()
        val root: Any = if (trimmed.startsWith("[")) JSONArray(text) else JSONObject(text)

        val listPath = conf.optString("list_path", "")
        val arrAny: Any? = if (listPath.isBlank()) root else dig(root, listPath)
        val rows: List<JSONObject> = when (arrAny) {
            is JSONArray -> (0 until arrAny.length()).mapNotNull { arrAny.optJSONObject(it) }
            is JSONObject -> arrAny.keys().asSequence().mapNotNull { arrAny.optJSONObject(it) }.toList()
            else -> throw IllegalStateException("list_path '$listPath' 不是数组")
        }

        val fields = conf.optJSONObject("fields")
        fun field(row: JSONObject, key: String): String {
            val path = fields?.optString(key).orEmpty()
            if (path.isBlank()) return ""
            return dig(row, path)?.toString().orEmpty()
        }

        val limit = conf.optInt("limit", 20).coerceAtLeast(0)
        val titleFormat = conf.optString("title_format")
        val bodyFormat = conf.optString("body_format")
        val detailFormat = conf.optString("detail_format")
        return rows.take(limit).map { row ->
            val rawTitle = field(row, "title")
            val rawBody = field(row, "body")
            // key 缺失时退回 title（模板结果不参与 key，保证同一行 key 稳定）
            val key = field(row, "key").ifBlank { rawTitle }
            val title = if (titleFormat.isNotBlank()) fmt(titleFormat, row) else rawTitle
            val body = if (bodyFormat.isNotBlank()) fmt(bodyFormat, row) else rawBody
            val detail = if (detailFormat.isNotBlank()) {
                fmt(detailFormat, row)
            } else {
                body.ifBlank { title }
            }
            item(
                key = key,
                title = title,
                body = body,
                detail = detail,
                url = field(row, "url"),
            )
        }
    }

    companion object {

        private val TEMPLATE = Regex("\\{([^{}]+)}")

        /** 支持 `a.b.0.c` 形式的路径。 */
        fun dig(obj: Any?, path: String): Any? {
            var cur: Any? = obj
            for (part in path.split('.').filter { it.isNotEmpty() }) {
                cur = when (cur) {
                    null -> return null
                    is JSONObject -> cur.opt(part)
                    is JSONArray -> {
                        val arr = cur
                        part.toIntOrNull()?.let { i ->
                            if (i in 0 until arr.length()) arr.opt(i) else null
                        }
                    }
                    else -> return null
                }
            }
            return cur
        }

        /** `{a.b}` 模板：占位符的取值走 [dig]，缺失时替换为空串。 */
        internal fun fmt(template: String, row: JSONObject): String =
            TEMPLATE.replace(template) { m -> dig(row, m.groupValues[1])?.toString().orEmpty() }
    }
}
