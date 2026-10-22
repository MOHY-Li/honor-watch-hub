package dev.watchhub.app.delivery

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import dev.watchhub.app.core.Clock
import dev.watchhub.app.core.LogBus
import dev.watchhub.app.data.SettingsStore
import dev.watchhub.app.data.TaskStore
import dev.watchhub.app.core.model.TaskStatus

/**
 * 日历兜底通道。
 *
 * 为什么需要它：通知镜像依赖 ROM 白名单和用户手动勾选，是整条链路最脆的一环。
 * 荣耀手表会原生同步手机日历提醒，所以把高优先级任务写进日历 = 第二条不押注镜像的通路。
 *
 * 只写系统默认日历里的事件（不建独立账户），任务完成/跳过时删掉对应事件。
 */
class CalendarSync(
    private val ctx: Context,
    private val tasks: TaskStore,
    private val settings: SettingsStore,
    private val clock: Clock,
) {

    fun sync(): Int {
        if (!settings.calendarEnabled) return 0
        if (!hasPermission()) return 0

        var changed = 0
        val calendarId = defaultCalendarId() ?: run {
            LogBus.post("日历同步跳过：找不到可写日历")
            return 0
        }

        // 1. 已完成/跳过的任务：删掉对应日历事件
        tasks.tasksWithCalendarEvent().forEach { task ->
            if (task.status != TaskStatus.OPEN) {
                runCatching { deleteEvent(task.calendarEventId) }
                tasks.setCalendarEvent(task.id, 0L)
                changed++
            }
        }

        // 2. 未写日历的 OPEN 任务：补写（只写未来 30 天内，避免一次写几百条）
        val now = clock.now()
        val horizon = now + 30L * 24 * 3600 * 1000
        tasks.openForCalendar().forEach { task ->
            if (task.dueAt < now - 5 * 60_000L || task.dueAt > horizon) return@forEach
            val eventId = insertEvent(calendarId, task.title, task.body, task.id, task.dueAt) ?: return@forEach
            tasks.setCalendarEvent(task.id, eventId)
            changed++
        }
        if (changed > 0) LogBus.post("日历同步：更新 $changed 条")
        return changed
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    private fun defaultCalendarId(): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.VISIBLE)
        ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.VISIBLE}=1",
            null,
            "${CalendarContract.Calendars.IS_PRIMARY} DESC",
        )?.use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return null
    }

    private fun insertEvent(calendarId: Long, title: String, body: String, taskId: Long, dueAt: Long): Long? {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, "WatchHub 任务 #$taskId" + if (body.isBlank()) "" else "\n$body")
            put(CalendarContract.Events.DTSTART, dueAt)
            put(CalendarContract.Events.DTEND, dueAt + 5 * 60_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, java.time.ZoneId.systemDefault().id)
            put(CalendarContract.Events.HAS_ALARM, 1)
        }
        val uri = runCatching { ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) }
            .getOrNull() ?: return null
        val eventId = ContentUris.parseId(uri)
        runCatching {
            ctx.contentResolver.insert(
                CalendarContract.Reminders.CONTENT_URI,
                ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.MINUTES, 0)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                },
            )
        }
        return eventId
    }

    private fun deleteEvent(eventId: Long) {
        if (eventId <= 0) return
        ctx.contentResolver.delete(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            null,
            null,
        )
    }
}
