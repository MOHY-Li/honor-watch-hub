package dev.watchhub.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import dev.watchhub.app.core.model.Alert
import dev.watchhub.app.core.model.AlertKind
import dev.watchhub.app.core.model.DeliveryState
import dev.watchhub.app.core.model.Priority

/**
 * alerts 表同时是历史记录和 outbox。`state=PENDING` 就是「还没成功发出去」，
 * 服务重启 / 看门狗唤醒后由 Deliverer 补发。
 */
class AlertStore(private val db: Db) {

    /** 插入一条待送达提醒。key 已存在时返回 null（同一件事不重复落库）。 */
    fun insert(alert: Alert): Long? {
        val id = db.writableDatabase.insertWithOnConflict(TABLE, null, values(alert), SQLiteDatabase.CONFLICT_IGNORE)
        return if (id == -1L) null else id
    }

    fun pending(limit: Int = 50): List<Alert> = query(
        "SELECT * FROM $TABLE WHERE state='PENDING' AND attempts < ? ORDER BY at ASC LIMIT ?",
        arrayOf(MAX_ATTEMPTS.toString(), limit.toString()),
    )

    fun recent(limit: Int = 100): List<Alert> = query(
        "SELECT * FROM $TABLE ORDER BY id DESC LIMIT ?",
        arrayOf(limit.toString()),
    )

    fun byId(id: Long): Alert? = query("SELECT * FROM $TABLE WHERE id=?", arrayOf(id.toString())).firstOrNull()

    fun markDelivered(id: Long, at: Long) {
        db.writableDatabase.execSQL(
            "UPDATE $TABLE SET state='DELIVERED', attempts=attempts+1, delivered_at=?, last_error=NULL WHERE id=?",
            arrayOf(at, id),
        )
    }

    /** 通知失败：累加重试次数，达到上限后标 FAILED（体检页会显示）。 */
    fun markAttemptFailed(id: Long, error: String) {
        db.writableDatabase.execSQL(
            "UPDATE $TABLE SET attempts=attempts+1, last_error=?, " +
                "state=CASE WHEN attempts+1 >= ? THEN 'FAILED' ELSE 'PENDING' END WHERE id=?",
            arrayOf(error.take(300), MAX_ATTEMPTS, id),
        )
    }

    fun retryFailed() {
        db.writableDatabase.execSQL("UPDATE $TABLE SET state='PENDING', attempts=0 WHERE state='FAILED'")
    }

    fun pendingCount(): Int = count("state='PENDING'")

    fun failedCount(): Int = count("state='FAILED'")

    /** 某类自 since 起的条数（健康分析统计「本周规则触发次数」用）。 */
    fun countSince(kind: String, since: Long): Int =
        db.readableDatabase
            .rawQuery("SELECT COUNT(*) FROM $TABLE WHERE kind=? AND at>=?", arrayOf(kind, since.toString()))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun totalCount(): Int = count(null)

    fun lastDeliveredAt(): Long = db.readableDatabase
        .rawQuery("SELECT MAX(delivered_at) FROM $TABLE", null)
        .use { if (it.moveToFirst()) it.getLong(0) else 0L }

    /** 只保留最近 [keep] 条，避免历史无限增长。 */
    fun prune(keep: Int = 500) {
        db.writableDatabase.execSQL(
            "DELETE FROM $TABLE WHERE id NOT IN (SELECT id FROM $TABLE ORDER BY id DESC LIMIT ?)",
            arrayOf(keep.toString()),
        )
    }

    private fun count(where: String?): Int {
        val sql = if (where == null) "SELECT COUNT(*) FROM $TABLE" else "SELECT COUNT(*) FROM $TABLE WHERE $where"
        return db.readableDatabase.rawQuery(sql, null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    private fun query(sql: String, args: Array<String>): List<Alert> {
        val out = ArrayList<Alert>()
        db.readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) out.add(map(c))
        }
        return out
    }

    private fun map(c: android.database.Cursor) = Alert(
        title = c.getString(c.getColumnIndexOrThrow("title")),
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        key = c.getString(c.getColumnIndexOrThrow("key")),
        kind = runCatching { AlertKind.valueOf(c.getString(c.getColumnIndexOrThrow("kind"))) }
            .getOrDefault(AlertKind.FEED),
        source = c.getString(c.getColumnIndexOrThrow("source")),
        body = c.getString(c.getColumnIndexOrThrow("body")),
        detail = c.getString(c.getColumnIndexOrThrow("detail")),
        url = c.getString(c.getColumnIndexOrThrow("url")),
        priority = Priority.parse(c.getString(c.getColumnIndexOrThrow("priority"))),
        tags = tagsFromJson(c.getString(c.getColumnIndexOrThrow("tags"))),
        at = c.getLong(c.getColumnIndexOrThrow("at")),
        state = runCatching { DeliveryState.valueOf(c.getString(c.getColumnIndexOrThrow("state"))) }
            .getOrDefault(DeliveryState.PENDING),
        attempts = c.getInt(c.getColumnIndexOrThrow("attempts")),
        deliveredAt = c.getLong(c.getColumnIndexOrThrow("delivered_at")),
        lastError = c.getStringOrNull("last_error"),
        taskId = c.getLongOrNull("task_id"),
    )

    private fun values(a: Alert) = ContentValues().apply {
        put("key", a.key.take(256))
        put("kind", a.kind.name)
        put("source", a.source.take(64))
        put("title", a.title.take(200))
        put("body", a.body.take(500))
        put("detail", a.detail.take(8000))
        put("url", a.url.take(2000))
        put("priority", a.priority.name)
        put("tags", tagsToJson(a.tags))
        put("at", a.at)
        put("state", a.state.name)
        put("attempts", a.attempts)
        put("delivered_at", a.deliveredAt)
        putNullable("last_error", a.lastError)
        if (a.taskId == null) putNull("task_id") else put("task_id", a.taskId)
    }

    companion object {
        private const val TABLE = "alerts"
        const val MAX_ATTEMPTS = 5
    }
}

private fun android.database.Cursor.getStringOrNull(name: String): String? {
    val i = getColumnIndex(name)
    return if (i < 0 || isNull(i)) null else getString(i)
}

private fun android.database.Cursor.getLongOrNull(name: String): Long? {
    val i = getColumnIndex(name)
    return if (i < 0 || isNull(i)) null else getLong(i)
}
