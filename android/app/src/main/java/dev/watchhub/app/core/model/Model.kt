package dev.watchhub.app.core.model

/** 优先级。HIGH 用通知渠道的高重要性 + ALARM 类别，其余用默认类别。 */
enum class Priority {
    LOW,
    NORMAL,
    HIGH;

    companion object {
        fun parse(v: String?): Priority = when (v?.trim()?.lowercase()) {
            "high", "important", "重要", "高" -> HIGH
            "low", "低" -> LOW
            else -> NORMAL
        }
    }

    val wire: String get() = name.lowercase()
}

enum class AlertKind { FEED, REMINDER, SYSTEM }

enum class DeliveryState { PENDING, DELIVERED, FAILED }

/** 采集器产出的一条候选事件。没有「完成」语义，只是外部世界发生了一件事。 */
data class FeedItem(
    val source: String,
    val key: String,
    val title: String,
    val body: String = "",
    val detail: String = "",
    val url: String = "",
    val priority: Priority = Priority.NORMAL,
    val tags: List<String> = emptyList(),
)

/**
 * 一次「要送到手表的东西」。
 *
 * 它同时是 outbox 行：`state == PENDING` 表示还没成功发通知，进程重启后要补发。
 * `key` 唯一，保证同一件事不会重复落库。
 */
data class Alert(
    val title: String,
    val id: Long = 0,
    val key: String = "",
    val kind: AlertKind = AlertKind.FEED,
    val source: String = "",
    val body: String = "",
    val detail: String = "",
    val url: String = "",
    val priority: Priority = Priority.NORMAL,
    val tags: List<String> = emptyList(),
    val at: Long = 0,
    val state: DeliveryState = DeliveryState.PENDING,
    val attempts: Int = 0,
    val deliveredAt: Long = 0,
    val lastError: String? = null,
    val taskId: Long? = null,
)

enum class TaskStatus { OPEN, DONE, SKIPPED, MISSED }

/**
 * 一条具体到期的任务。
 *
 * - `alertedAt == 0` 表示还没生成过提醒（每次 snooze 会清零，重新计时）。
 * - `snoozedUntil > 0` 时，实际到期时间取它而不是 `dueAt`。
 */
data class Task(
    val reminderId: Long,
    val title: String,
    val dueAt: Long,
    val id: Long = 0,
    val body: String = "",
    val priority: Priority = Priority.NORMAL,
    val status: TaskStatus = TaskStatus.OPEN,
    val createdAt: Long = 0,
    val alertedAt: Long = 0,
    val snoozedUntil: Long = 0,
    val doneAt: Long = 0,
    val calendarEventId: Long = 0,
) {
    val effectiveDueAt: Long get() = if (snoozedUntil > 0) snoozedUntil else dueAt
}

/** 重复规则：每天哪些时间点 + 一周里哪几天。`days` 为空表示每天。 */
data class Reminder(
    val name: String,
    val title: String,
    val id: Long = 0,
    val body: String = "",
    val priority: Priority = Priority.NORMAL,
    val recurrence: Recurrence,
    val enabled: Boolean = true,
    val calendarSync: Boolean = false,
)
