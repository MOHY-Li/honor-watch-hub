package dev.watchhub.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/**
 * 去重表。feed 类事件的 key 落在这里，同一 key 只推一次。
 *
 * 保留 30 天：够覆盖绝大多数源的更新周期，又不会无限增长。
 */
class SeenStore(private val db: Db) {

    fun isSeen(key: String): Boolean {
        if (key.isBlank()) return false
        return db.readableDatabase.rawQuery("SELECT 1 FROM seen WHERE key=?", arrayOf(key))
            .use { it.moveToFirst() }
    }

    fun markSeen(key: String, at: Long) {
        if (key.isBlank()) return
        db.writableDatabase.insertWithOnConflict(
            "seen", null,
            ContentValues().apply {
                put("key", key.take(256))
                put("ts", at)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun markAllSeen(keys: List<String>, at: Long) {
        if (keys.isEmpty()) return
        val db = db.writableDatabase
        db.beginTransaction()
        try {
            keys.forEach { markSeen(it, at) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun prune(olderThanMs: Long = RETAIN_MS, now: Long = System.currentTimeMillis()) {
        db.writableDatabase.execSQL("DELETE FROM seen WHERE ts < ?", arrayOf(now - olderThanMs))
    }

    fun count(): Int = db.readableDatabase.rawQuery("SELECT COUNT(*) FROM seen", null)
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    companion object {
        const val RETAIN_MS = 30L * 24 * 3600 * 1000
    }
}
