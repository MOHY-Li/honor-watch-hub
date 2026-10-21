package dev.watchhub.app.hub

import org.json.JSONObject

/** 整页指纹：内容哈希变了就报一条。抢票/补货/公告页的最佳兜底。 */
class HashSource(override val name: String, override val raw: JSONObject) : Source() {

    override fun fetch(): List<Item> {
        val bytes = Http.getBytes(raw.optString("url"))
        val h = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
        return listOf(
            Item(
                key = "$name:$h",
                title = raw.optString("title", "$name 有更新"),
                body = raw.optString("body", "页面内容发生变化"),
                detail = raw.optString("detail", ""),
                url = raw.optString("url"),
                priority = priority,
                tags = tags.ifEmpty { listOf(name) },
                source = name,
            )
        )
    }
}
