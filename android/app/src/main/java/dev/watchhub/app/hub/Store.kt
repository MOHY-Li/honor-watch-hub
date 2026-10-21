package dev.watchhub.app.hub

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.watchhub.app.Notif

/**
 * 本地存储：通知历史 + 去重表 + 源状态。
 *
 * 用 SQLite 而不是 SharedPreferences，因为：
 *  - 去重表可能有几千条 key
 *  - 需要「按 id 倒序查最近 N 条」给 UI 用
 *  - 手机重启后必须保留（否则会重复推历史）
 */
class Store(ctx: Context) : SQLiteOpenHelper(ctx, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE notifications (
                 id       INTEGER PRIMARY KEY AUTOINCREMENT,
                 ts       INTEGER NOT NULL,
                 title    TEXT NOT NULL,
                 body     TEXT NOT NULL DEFAULT '',
                 detail   TEXT NOT NULL DEFAULT '',
                 priority TEXT NOT NULL DEFAULT 'default',
                 tags     TEXT NOT NULL DEFAULT '[]',
                 url      TEXT NOT NULL DEFAULT '',
                 source   TEXT NOT NULL DEFAULT '',
                 key      TEXT NOT NULL DEFAULT ''
               )"""
        )
        db.execSQL("CREATE INDEX idx_notif_key ON notifications(key)")
        db.execSQL("CREATE TABLE seen (key TEXT PRIMARY KEY, ts INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE source_state (name TEXT PRIMARY KEY, last_check INTEGER, last_ok INTEGER, last_error TEXT, items_seen INTEGER DEFAULT 0, new_last_run INTEGER DEFAULT 0, seeded INTEGER DEFAULT 0)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("DROP TABLE IF EXISTS notifications")
        db.execSQL("DROP TABLE IF EXISTS seen")
        db.execSQL("DROP TABLE IF EXISTS source_state")
        onCreate(db)
    }

    // ---------- 去重 / 入库 ----------

    /** 已见过就返回 false；否则入库并返回 true。key 为空则不去重。 */
    fun insertIfNew(item: Item): Long? {
        if (item.key.isBlank()) return insert(item)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cur = db.rawQuery("SELECT 1 FROM seen WHERE key=?", arrayOf(item.key))
            val exists = cur.use { it.moveToFirst() }
            if (exists) {
                db.setTransactionSuccessful()
                return null
            }
            val id = insert(item, db)
            db.insertWithOnConflict("seen", null, ContentValues().apply {
                put("key", item.key)
                put("ts", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    /** 只标记已见、不入库。用于首轮播种。 */
    fun markSeen(key: String) {
        if (key.isBlank()) return
        writableDatabase.insertWithOnConflict("seen", null, ContentValues().apply {
            put("key", key)
            put("ts", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun isSeen(key: String): Boolean {
        if (key.isBlank()) return false
        return readableDatabase.rawQuery("SELECT 1 FROM seen WHERE key=?", arrayOf(key))
            .use { it.moveToFirst() }
    }

    private fun insert(item: Item, db: SQLiteDatabase = writableDatabase): Long {
        val tagsJson = item.tags.joinToString(",", "[", "]") { "\"" + it.replace("\"", "\\\"") + "\"" }
        return db.insert("notifications", null, ContentValues().apply {
            put("ts", System.currentTimeMillis())
            put("title", item.title.take(200))
            put("body", item.body.take(500))
            put("detail", item.detail.take(8000))
            put("priority", item.priority)
            put("tags", tagsJson)
            put("url", item.url.take(2000))
            put("source", item.source.take(64))
            put("key", item.key.take(256))
        })
    }

    fun recent(limit: Int = 50): List<Notif> {
        val out = ArrayList<Notif>()
        readableDatabase.rawQuery(
            "SELECT id,title,body,detail,priority,tags,url,source FROM notifications ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Notif(
                        id = c.getLong(0),
                        ts = "",
                        title = c.getString(1),
                        body = c.getString(2),
                        detail = c.getString(3),
                        priority = c.getString(4),
                        tags = parseTags(c.getString(5)),
                        url = c.getString(6),
                        source = c.getString(7),
                    )
                )
            }
        }
        return out
    }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM notifications", null)
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun prune(keep: Int = 500) {
        writableDatabase.execSQL(
            "DELETE FROM notifications WHERE id <= (SELECT MAX(id) FROM notifications) - ?",
            arrayOf(keep)
        )
        writableDatabase.execSQL(
            "DELETE FROM seen WHERE ts < ?", arrayOf(System.currentTimeMillis() - 30L * 24 * 3600 * 1000)
        )
    }

    // ---------- 源状态 ----------

    fun stateOf(name: String): JSONObj = readableDatabase.rawQuery(
        "SELECT last_check,last_ok,last_error,items_seen,new_last_run,seeded FROM source_state WHERE name=?",
        arrayOf(name)
    ).use { c ->
        if (!c.moveToFirst()) JSONObj(0, 0, null, 0, 0, false)
        else JSONObj(c.getLong(0), c.getLong(1), c.getString(2), c.getInt(3), c.getInt(4), c.getInt(5) == 1)
    }

    fun saveState(name: String, s: JSONObj) {
        writableDatabase.insertWithOnConflict("source_state", null, ContentValues().apply {
            put("name", name)
            put("last_check", s.lastCheck)
            put("last_ok", s.lastOk)
            put("last_error", s.lastError)
            put("items_seen", s.itemsSeen)
            put("new_last_run", s.newLastRun)
            put("seeded", if (s.seeded) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    data class JSONObj(
        val lastCheck: Long,
        val lastOk: Long,
        val lastError: String?,
        val itemsSeen: Int,
        val newLastRun: Int,
        val seeded: Boolean,
    )

    private fun parseTags(s: String?): List<String> {
        if (s.isNullOrBlank()) return emptyList()
        return Regex("\"([^\"]*)\"").findAll(s).map { it.groupValues[1] }.toList()
    }

    companion object {
        private const val DB_NAME = "watchhub.db"
        private const val DB_VERSION = 1
    }
}

