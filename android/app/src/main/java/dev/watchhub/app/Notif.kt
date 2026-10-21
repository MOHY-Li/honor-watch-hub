package dev.watchhub.app

import org.json.JSONObject

/** 服务端 Notification 的客户端镜像。字段和 docs/PROTOCOL.md 一一对应。 */
data class Notif(
    val id: Long,
    val ts: String,
    val title: String,
    val body: String,
    val detail: String,
    val priority: String,
    val tags: List<String>,
    val url: String,
    val source: String,
) {
    val isHigh: Boolean get() = priority == "high"

    fun fingerprint(): String = "$id|$title|$body"

    companion object {
        fun fromJson(o: JSONObject): Notif {
            val tags = mutableListOf<String>()
            o.optJSONArray("tags")?.let {
                for (i in 0 until it.length()) tags.add(it.optString(i))
            }
            return Notif(
                id = o.optLong("id"),
                ts = o.optString("ts"),
                title = o.optString("title", "（无标题）"),
                body = o.optString("body"),
                detail = o.optString("detail"),
                priority = o.optString("priority", "default"),
                tags = tags,
                url = o.optString("url"),
                source = o.optString("source"),
            )
        }
    }
}
