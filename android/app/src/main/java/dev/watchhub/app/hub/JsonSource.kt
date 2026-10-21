package dev.watchhub.app.hub

import org.json.JSONArray
import org.json.JSONObject

/** 任意 JSON API，用 list_path 定位数组、fields 做字段映射、*_format 做模板。 */
class JsonSource(override val name: String, override val raw: JSONObject) : Source() {

    override fun fetch(): List<Item> {
        val text = Http.getString(raw.optString("url"))
        val root: Any = if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)

        val listPath = raw.optString("list_path", "")
        val arrAny: Any? = if (listPath.isBlank()) root else dig(root, listPath)
        val rows: List<JSONObject> = when (arrAny) {
            is JSONArray -> (0 until arrAny.length()).mapNotNull { arrAny.optJSONObject(it) }
            is JSONObject -> arrAny.keys().asSequence().mapNotNull { arrAny.optJSONObject(it) }.toList()
            else -> throw IllegalStateException("list_path '$listPath' 不是数组")
        }

        val fields = raw.optJSONObject("fields")
        fun g(row: JSONObject, key: String): String {
            val path = fields?.optString(key).orEmpty()
            if (path.isBlank()) return ""
            return dig(row, path)?.toString().orEmpty()
        }

        val limit = raw.optInt("limit", 20)
        val out = ArrayList<Item>(minOf(rows.size, limit))
        for (row in rows.take(limit)) {
            var title = g(row, "title")
            var body = g(row, "body")
            val key = g(row, "key").ifBlank { g(row, "url") }.ifBlank { title }
            raw.optString("title_format").takeIf { it.isNotBlank() }?.let {
                title = Source.fmt(it, row)
            }
            raw.optString("body_format").takeIf { it.isNotBlank() }?.let {
                body = Source.fmt(it, row)
            }
            out.add(
                Item(
                    key = "$name:${RssSource.sha1(key)}",
                    title = Source.clip(title, 120),
                    body = Source.clip(body, 400),
                    detail = Source.clip(body.ifBlank { title }, 4000),
                    url = g(row, "url"),
                    priority = priority,
                    tags = tags.ifEmpty { listOf(name) },
                    source = name,
                )
            )
        }
        return out
    }

    companion object {
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
    }
}

