package dev.watchhub.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import dev.watchhub.app.core.model.Priority
import dev.watchhub.app.core.model.Recurrence
import dev.watchhub.app.core.model.Reminder

/** 重复提醒的定义。一次具体到期是 tasks 表里的 Task。 */
class ReminderStore(private val db: Db) {

    fun all(): List<Reminder> = query("SELECT * FROM reminders ORDER BY id")

    fun enabled(): List<Reminder> = query("SELECT * FROM reminders WHERE enabled=1 ORDER BY id")

    fun byId(id: Long): Reminder? = query("SELECT * FROM reminders WHERE id=?", arrayOf(id.toString())).firstOrNull()

    fun byName(name: String): Reminder? =
        query("SELECT * FROM reminders WHERE name=?", arrayOf(name)).firstOrNull()

    fun isEmpty(): Boolean =
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM reminders", null)
            .use { it.moveToFirst() && it.getInt(0) == 0 }

    /** 新建或更新。名字唯一，重名会抛 SQLiteConstraintException，UI 负责提示。 */
    fun upsert(r: Reminder): Long {
        val cv = ContentValues().apply {
            put("name", r.name.trim())
            put("title", r.title.trim())
            put("body", r.body)
            put("priority", r.priority.name)
            put("times", r.recurrence.times.sorted().joinToString(",") { "%02d:%02d".format(it.hour, it.minute) })
            put("days", r.recurrence.days.sorted().joinToString(",") { (it.value - 1).toString() })
            put("enabled", if (r.enabled) 1 else 0)
            put("calendar", if (r.calendarSync) 1 else 0)
        }
        return if (r.id == 0L) {
            db.writableDatabase.insertWithOnConflict("reminders", null, cv, SQLiteDatabase.CONFLICT_ABORT)
        } else {
            db.writableDatabase.updateWithOnConflict(
                "reminders", cv, "id=?", arrayOf(r.id.toString()), SQLiteDatabase.CONFLICT_ABORT,
            )
            r.id
        }
    }

    fun setEnabled(id: Long, enabled: Boolean) {
        db.writableDatabase.execSQL(
            "UPDATE reminders SET enabled=? WHERE id=?",
            arrayOf(if (enabled) 1 else 0, id),
        )
    }

    fun delete(id: Long) {
        db.writableDatabase.delete("reminders", "id=?", arrayOf(id.toString()))
    }

    private fun query(sql: String, args: Array<String>? = null): List<Reminder> {
        val out = ArrayList<Reminder>()
        db.readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                val times = c.getString(c.getColumnIndexOrThrow("times"))
                    .split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val days = c.getString(c.getColumnIndexOrThrow("days"))
                    .split(",").mapNotNull { it.trim().toIntOrNull() }
                    .filter { it in 0..6 }
                out.add(
                    Reminder(
                        name = c.getString(c.getColumnIndexOrThrow("name")),
                        title = c.getString(c.getColumnIndexOrThrow("title")),
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        body = c.getString(c.getColumnIndexOrThrow("body")),
                        priority = Priority.parse(c.getString(c.getColumnIndexOrThrow("priority"))),
                        recurrence = Recurrence.parse(times, days),
                        enabled = c.getInt(c.getColumnIndexOrThrow("enabled")) == 1,
                        calendarSync = c.getInt(c.getColumnIndexOrThrow("calendar")) == 1,
                    )
                )
            }
        }
        return out
    }
}
