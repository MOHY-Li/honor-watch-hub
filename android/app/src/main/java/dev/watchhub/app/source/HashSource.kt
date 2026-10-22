package dev.watchhub.app.source

import dev.watchhub.app.core.model.FeedItem
import java.security.MessageDigest

/** 整页指纹：内容哈希变了就报一条。抢票/补货/公告页的最佳兜底。 */
class HashSource(def: SourceDef) : BaseSource(def) {

    override fun fetch(): List<FeedItem> {
        val url = requireUrl()
        return parse(fetchText(url))
    }

    /** 对整段文本取 SHA-256 前 16 位作为 key。 */
    internal fun parse(text: String): List<FeedItem> {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val hash = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(16)
        return listOf(
            item(
                key = hash,
                title = conf.optString("title", "$name 有更新"),
                body = conf.optString("body", "页面内容发生变化"),
                detail = conf.optString("detail", ""),
                url = conf.optString("url").trim(),
            )
        )
    }
}
