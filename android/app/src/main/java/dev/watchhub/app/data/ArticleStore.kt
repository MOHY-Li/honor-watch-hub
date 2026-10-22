package dev.watchhub.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import dev.watchhub.app.core.model.Article
import dev.watchhub.app.core.model.FeedItem

/**
 * 订阅文章库。
 *
 * 和 alerts 表分开的原因：alert 是「发到手表通知栏的那一条」（短、会被裁剪、用于去重）；
 * article 是「留在手机上可回看的记录」（长、带已读状态、按发布时间排序）。
 * 同一个 feed 条目会同时产生 alert 和 article，但生命周期不同。
 */
class ArticleStore(private val db: Db) {

    /** 插入一条文章。已存在（同 source+key）返回 null。 */
    fun insertIfNew(item: FeedItem, fetchedAt: Long): Long? {
        val key = item.key.ifBlank { "${item.source}:${item.title}" }
        val id = db.writableDatabase.insertWithOnConflict(
            TABLE, null,
            ContentValues().apply {
                put("source", item.source.take(64))
                put("key", key.take(256))
                put("title", item.title.take(300))
                put("url", item.url.take(2000))
                // item.body 是为手表裁到 120 字的版本；文章摘要用 detail（完整正文）
                put("summary", item.detail.ifBlank { item.body }.take(500))
                put("published_at", item.publishedAt)
                put("fetched_at", fetchedAt)
                put("read_at", 0)
                put("starred", 0)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        return if (id == -1L) null else id
    }

    /**
     * 文章列表。按发布时间倒序；published_at 为 0 的用抓取时间兜底排序。
     */
    fun recent(limit: Int = 200, source: String? = null, unreadOnly: Boolean = false): List<Article> {
        val where = StringBuilder()
        val args = ArrayList<String>()
        if (!source.isNullOrBlank()) {
            where.append("source = ?")
            args.add(source)
        }
        if (unreadOnly) {
            if (where.isNotEmpty()) where.append(" AND ")
            where.append("read_at = 0")
        }
        args.add((limit.coerceIn(1, 1000)).toString())
        val sql = "SELECT * FROM $TABLE" +
            (if (where.isNotEmpty()) " WHERE $where" else "") +
            " ORDER BY COALESCE(NULLIF(published_at,0), fetched_at) DESC LIMIT ?"
        val out = ArrayList<Article>()
        db.readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) out.add(map(c))
        }
        return out
    }

    fun byId(id: Long): Article? {
        db.readableDatabase.rawQuery("SELECT * FROM $TABLE WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (!c.moveToFirst()) return null
            return map(c)
        }
    }

    fun unreadCount(source: String? = null): Int {
        val sql = if (source.isNullOrBlank()) {
            "SELECT COUNT(*) FROM $TABLE WHERE read_at = 0"
        } else {
            "SELECT COUNT(*) FROM $TABLE WHERE read_at = 0 AND source = ?"
        }
        val args = if (source.isNullOrBlank()) null else arrayOf(source)
        return db.readableDatabase.rawQuery(sql, args).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun totalCount(): Int =
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun markRead(id: Long, at: Long) {
        db.writableDatabase.execSQL(
            "UPDATE $TABLE SET read_at = ? WHERE id = ? AND read_at = 0",
            arrayOf(at, id),
        )
    }

    fun markAllRead(source: String?, at: Long): Int {
        val sql = if (source.isNullOrBlank()) {
            "UPDATE $TABLE SET read_at = ? WHERE read_at = 0"
        } else {
            "UPDATE $TABLE SET read_at = ? WHERE read_at = 0 AND source = ?"
        }
        val args = if (source.isNullOrBlank()) arrayOf(at) else arrayOf(at, source)
        val countSql = if (source.isNullOrBlank()) {
            "SELECT COUNT(*) FROM $TABLE WHERE read_at = 0"
        } else {
            "SELECT COUNT(*) FROM $TABLE WHERE read_at = 0 AND source = ?"
        }
        val countArgs = if (source.isNullOrBlank()) null else arrayOf(source)
        val n = db.readableDatabase.rawQuery(countSql, countArgs)
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        db.writableDatabase.execSQL(sql, args)
        return n
    }

    /** 只保留最近 [retainDays] 天的文章，避免无限增长（未读也保留 60 天足够）。 */
    fun prune(now: Long, retainDays: Int = 60) {
        db.writableDatabase.execSQL(
            "DELETE FROM $TABLE WHERE COALESCE(NULLIF(published_at,0), fetched_at) < ?",
            arrayOf(now - retainDays * 24L * 3600 * 1000),
        )
    }

    private fun map(c: android.database.Cursor) = Article(
        source = c.getString(c.getColumnIndexOrThrow("source")),
        key = c.getString(c.getColumnIndexOrThrow("key")),
        title = c.getString(c.getColumnIndexOrThrow("title")),
        url = c.getString(c.getColumnIndexOrThrow("url")),
        summary = c.getString(c.getColumnIndexOrThrow("summary")),
        publishedAt = c.getLong(c.getColumnIndexOrThrow("published_at")),
        fetchedAt = c.getLong(c.getColumnIndexOrThrow("fetched_at")),
        readAt = c.getLong(c.getColumnIndexOrThrow("read_at")),
        starred = c.getInt(c.getColumnIndexOrThrow("starred")) == 1,
        id = c.getLong(c.getColumnIndexOrThrow("id")),
    )

    companion object {
        private const val TABLE = "articles"
    }
}
