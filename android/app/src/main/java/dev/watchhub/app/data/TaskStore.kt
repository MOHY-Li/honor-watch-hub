package dev.watchhub.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import dev.watchhub.app.core.model.Priority
import dev.watchhub.app.core.model.Reminder
import dev.watchhub.app.core.model.Task
import dev.watchhub.app.core.model.TaskStatus

/**
 * 一次具体到期的任务。materialize 是幂等的（UNIQUE(reminder_id, due_at) + CONFLICT_IGNORE），
 * 所以 reconcile 可以随便多跑。
 */
class TaskStore(private val db: Db) {

    /** 为一条重复提醒生成未来 [horizonMs] 内的任务。返回新插入的条数。 */
    fun materialize(reminder: Reminder, now: Long, horizonMs: Long = DEFAULT_HORIZON_MS): Int {
        if (reminder.id == 0L) return 0
        val occurrences = reminder.recurrence.occurrencesBetween(now - 120_000L, now + horizonMs)
        if (occurrences.isEmpty()) return 0
        val sqlDb = db.writableDatabase
        var inserted = 0
        sqlDb.beginTransaction()
        try {
            occurrences.forEach { due ->
                val id = sqlDb.insertWithOnConflict(
                    "tasks", null,
                    ContentValues().apply {
                        put("reminder_id", reminder.id)
                        put("title", reminder.title)
                        put("body", reminder.body)
                        put("priority", reminder.priority.name)
                        put("due_at", due)
                        put("status", TaskStatus.OPEN.name)
                        put("created_at", now)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
                if (id != -1L) inserted++
            }
            sqlDb.setTransactionSuccessful()
        } finally {
            sqlDb.endTransaction()
        }
        return inserted
    }

    fun byId(id: Long): Task? = query("SELECT * FROM tasks WHERE id=?", arrayOf(id.toString())).firstOrNull()

    fun open(): List<Task> = query("SELECT * FROM tasks WHERE status='OPEN' ORDER BY due_at")

    /** 已到期但还没生成提醒的任务。是否真的该推由 TaskRules.plan 决定。 */
    fun alertedCandidates(now: Long): List<Task> = query(
        "SELECT * FROM tasks WHERE status='OPEN' AND alerted_at=0 " +
            "AND COALESCE(NULLIF(snoozed_until,0), due_at) <= ? ORDER BY due_at",
        arrayOf(now.toString()),
    )

    fun upcoming(limit: Int = 10): List<Task> {
        val sql = "SELECT * FROM tasks WHERE status='OPEN' " +
            "ORDER BY COALESCE(NULLIF(snoozed_until,0), due_at) LIMIT ?"
        return query(sql, arrayOf(limit.toString()))
    }

    fun recent(limit: Int = 100): List<Task> =
        query("SELECT * FROM tasks ORDER BY due_at DESC LIMIT ?", arrayOf(limit.toString()))

    fun markAlerted(id: Long, at: Long) {
        db.writableDatabase.execSQL("UPDATE tasks SET alerted_at=? WHERE id=?", arrayOf(at, id))
    }

    fun markMissed(id: Long) {
        db.writableDatabase.execSQL("UPDATE tasks SET status='MISSED' WHERE id=?", arrayOf(id))
    }

    fun complete(id: Long, at: Long) {
        db.writableDatabase.execSQL(
            "UPDATE tasks SET status='DONE', done_at=? WHERE id=?",
            arrayOf(at, id),
        )
    }

    fun skip(id: Long, at: Long) {
        db.writableDatabase.execSQL(
            "UPDATE tasks SET status='SKIPPED', done_at=? WHERE id=?",
            arrayOf(at, id),
        )
    }

    /** 稍后再说：清掉已提醒标记，并把实际到期时间推到 [until]。 */
    fun snooze(id: Long, until: Long) {
        db.writableDatabase.execSQL(
            "UPDATE tasks SET status='OPEN', snoozed_until=?, alerted_at=0 WHERE id=?",
            arrayOf(until, id),
        )
    }

    fun deleteForReminder(reminderId: Long) {
        db.writableDatabase.delete("tasks", "reminder_id=?", arrayOf(reminderId.toString()))
    }

    fun statsBetween(startMs: Long, endMs: Long): TaskStats {
        var open = 0
        var done = 0
        var total = 0
        db.readableDatabase.rawQuery(
            "SELECT status, COUNT(*) FROM tasks WHERE due_at >= ? AND due_at < ? GROUP BY status",
            arrayOf(startMs.toString(), endMs.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val n = c.getInt(1)
                total += n
                when (c.getString(0)) {
                    TaskStatus.OPEN.name -> open += n
                    TaskStatus.DONE.name -> done += n
                }
            }
        }
        return TaskStats(open = open, done = done, total = total)
    }

    /** 需要写日历的任务：OPEN 且所属 reminder 打开了日历同步。 */
    fun openForCalendar(): List<Task> = query(
        "SELECT t.* FROM tasks t JOIN reminders r ON r.id = t.reminder_id " +
            "WHERE t.status='OPEN' AND r.calendar=1 AND t.calendar_event_id=0 ORDER BY t.due_at",
    )

    fun tasksWithCalendarEvent(): List<Task> = query(
        "SELECT * FROM tasks WHERE calendar_event_id <> 0",
    )

    fun setCalendarEvent(id: Long, eventId: Long) {
        db.writableDatabase.execSQL(
            "UPDATE tasks SET calendar_event_id=? WHERE id=?",
            arrayOf(eventId, id),
        )
    }

    /** 已完成/已跳过的老任务只留 [retainDays] 天，MISSED 同理。 */
    fun pruneObsolete(now: Long, retainDays: Int = 30) {
        db.writableDatabase.execSQL(
            "DELETE FROM tasks WHERE status IN ('DONE','SKIPPED','MISSED') AND due_at < ?",
            arrayOf(now - retainDays * 24L * 3600 * 1000),
        )
    }

    private fun query(sql: String, args: Array<String>? = null): List<Task> {
        val out = ArrayList<Task>()
        db.readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) out.add(map(c))
        }
        return out
    }

    private fun map(c: android.database.Cursor) = Task(
        reminderId = c.getLong(c.getColumnIndexOrThrow("reminder_id")),
        title = c.getString(c.getColumnIndexOrThrow("title")),
        dueAt = c.getLong(c.getColumnIndexOrThrow("due_at")),
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        body = c.getString(c.getColumnIndexOrThrow("body")),
        priority = Priority.parse(c.getString(c.getColumnIndexOrThrow("priority"))),
        status = runCatching { TaskStatus.valueOf(c.getString(c.getColumnIndexOrThrow("status"))) }
            .getOrDefault(TaskStatus.OPEN),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        alertedAt = c.getLong(c.getColumnIndexOrThrow("alerted_at")),
        snoozedUntil = c.getLong(c.getColumnIndexOrThrow("snoozed_until")),
        doneAt = c.getLong(c.getColumnIndexOrThrow("done_at")),
        calendarEventId = c.getLong(c.getColumnIndexOrThrow("calendar_event_id")),
    )

    companion object {
        const val DEFAULT_HORIZON_MS = 7L * 24 * 3600 * 1000
    }
}
