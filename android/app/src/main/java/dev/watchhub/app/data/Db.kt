package dev.watchhub.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 本地数据库。所有状态都在这里，进程被杀后能完整恢复。
 *
 * v1 的库（DB_VERSION=1）里只有 notifications / seen / source_state；v2 升级时：
 *  - notifications 迁成 alerts（标记为 DELIVERED），保留历史
 *  - seen 原样保留（去重表跨版本可复用）
 *  - source_state 补一列 fails
 *  - 源配置从 SharedPreferences 迁移，见 engine/Migrator.kt
 */
class Db(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, NAME, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createAll(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) migrateV1toV2(db)
    }

    private fun createAll(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS alerts (
                 id          INTEGER PRIMARY KEY AUTOINCREMENT,
                 key         TEXT NOT NULL DEFAULT '',
                 kind        TEXT NOT NULL DEFAULT 'FEED',
                 source      TEXT NOT NULL DEFAULT '',
                 title       TEXT NOT NULL,
                 body        TEXT NOT NULL DEFAULT '',
                 detail      TEXT NOT NULL DEFAULT '',
                 url         TEXT NOT NULL DEFAULT '',
                 priority    TEXT NOT NULL DEFAULT 'NORMAL',
                 tags        TEXT NOT NULL DEFAULT '[]',
                 at          INTEGER NOT NULL DEFAULT 0,
                 state       TEXT NOT NULL DEFAULT 'PENDING',
                 attempts    INTEGER NOT NULL DEFAULT 0,
                 delivered_at INTEGER NOT NULL DEFAULT 0,
                 last_error  TEXT,
                 task_id     INTEGER
               )"""
        )
        // key 为空表示不做唯一约束（例如手动测试通知）
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_alerts_key ON alerts(key) WHERE key <> ''")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_alerts_state ON alerts(state, at)")
        db.execSQL("CREATE TABLE IF NOT EXISTS seen (key TEXT PRIMARY KEY, ts INTEGER NOT NULL)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS sources (
                 name    TEXT PRIMARY KEY,
                 spec    TEXT NOT NULL,
                 enabled INTEGER NOT NULL DEFAULT 1
               )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS source_state (
                 name       TEXT PRIMARY KEY,
                 last_check INTEGER NOT NULL DEFAULT 0,
                 last_ok    INTEGER NOT NULL DEFAULT 0,
                 last_error TEXT,
                 fails      INTEGER NOT NULL DEFAULT 0,
                 items_seen INTEGER NOT NULL DEFAULT 0,
                 seeded     INTEGER NOT NULL DEFAULT 0
               )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS reminders (
                 id       INTEGER PRIMARY KEY AUTOINCREMENT,
                 name     TEXT NOT NULL UNIQUE,
                 title    TEXT NOT NULL,
                 body     TEXT NOT NULL DEFAULT '',
                 priority TEXT NOT NULL DEFAULT 'NORMAL',
                 times    TEXT NOT NULL DEFAULT '',
                 days     TEXT NOT NULL DEFAULT '',
                 enabled  INTEGER NOT NULL DEFAULT 1,
                 calendar INTEGER NOT NULL DEFAULT 0
               )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS tasks (
                 id               INTEGER PRIMARY KEY AUTOINCREMENT,
                 reminder_id      INTEGER NOT NULL REFERENCES reminders(id) ON DELETE CASCADE,
                 title            TEXT NOT NULL,
                 body             TEXT NOT NULL DEFAULT '',
                 priority         TEXT NOT NULL DEFAULT 'NORMAL',
                 due_at           INTEGER NOT NULL,
                 status           TEXT NOT NULL DEFAULT 'OPEN',
                 created_at       INTEGER NOT NULL DEFAULT 0,
                 alerted_at       INTEGER NOT NULL DEFAULT 0,
                 snoozed_until    INTEGER NOT NULL DEFAULT 0,
                 done_at          INTEGER NOT NULL DEFAULT 0,
                 calendar_event_id INTEGER NOT NULL DEFAULT 0
               )"""
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_tasks_unique ON tasks(reminder_id, due_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_due ON tasks(status, due_at)")
        db.execSQL("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    private fun migrateV1toV2(db: SQLiteDatabase) {
        createAll(db)
        if (hasTable(db, "notifications")) {
            db.execSQL(
                """INSERT OR IGNORE INTO alerts
                     (key, kind, source, title, body, detail, url, priority, tags, at, state, attempts, delivered_at)
                   SELECT key,
                          'FEED',
                          source,
                          title,
                          body,
                          detail,
                          url,
                          CASE WHEN lower(priority) = 'high' THEN 'HIGH'
                               WHEN lower(priority) = 'low'  THEN 'LOW'
                               ELSE 'NORMAL' END,
                          tags,
                          ts,
                          'DELIVERED',
                          1,
                          ts
                   FROM notifications"""
            )
            db.execSQL("DROP TABLE notifications")
        }
        // v1 的 source_state 没有 fails 列
        if (hasTable(db, "source_state") && !hasColumn(db, "source_state", "fails")) {
            db.execSQL("ALTER TABLE source_state ADD COLUMN fails INTEGER NOT NULL DEFAULT 0")
        }
    }

    private fun hasTable(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table))
            .use { it.moveToFirst() }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            while (c.moveToNext()) if (c.getString(1) == column) return true
            false
        }

    companion object {
        private const val NAME = "watchhub.db"
        const val VERSION = 2
    }
}
