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

enum class AlertKind { FEED, REMINDER, SYSTEM, HEALTH }

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
    /** 发布时刻（毫秒）。只有 RSS/Atom 之类能拿到；0 表示未知，用抓取时间兜底。 */
    val publishedAt: Long = 0,
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

/** 提醒的调度方式。TIMES 复用原有「星期 + 时间点」的 Recurrence。 */
enum class ScheduleMode {
    TIMES,
    INTERVAL,
    CRON;

    companion object {
        fun parse(v: String?): ScheduleMode =
            entries.firstOrNull { it.name == v?.trim()?.uppercase() } ?: TIMES
    }
}

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
    /** 最近一次提醒的时间（首次提醒或最近一次 nudge）。 */
    val alertedAt: Long = 0,
    val snoozedUntil: Long = 0,
    val doneAt: Long = 0,
    val calendarEventId: Long = 0,
    /** 升级链已经推进到第几档：0 = 首次提醒后还没 nudge。 */
    val escalationLevel: Int = 0,
    /** 升级链开始的本地时间戳；0 表示链未激活。 */
    val chainStartedAt: Long = 0,
) {
    val effectiveDueAt: Long get() = if (snoozedUntil > 0) snoozedUntil else dueAt
}

/**
 * 信息流里的一篇文章（订阅页用）。
 *
 * 和 [Alert] 的区别：Alert 是「要送到手表的一条通知」（会被裁剪、可能重复送达）；
 * Article 是「留在手机上可回看的原文记录」，带已读状态，不参与通知去重。
 */
data class Article(
    val source: String,
    val key: String,
    val title: String,
    val url: String = "",
    val summary: String = "",
    val publishedAt: Long = 0,
    val fetchedAt: Long = 0,
    val readAt: Long = 0,
    val starred: Boolean = false,
    val id: Long = 0,
)

/** 重复规则：每天哪些时间点 + 一周里哪几天。`days` 为空表示每天。 */
data class Reminder(
    val name: String,
    val title: String,
    val id: Long = 0,
    val body: String = "",
    val priority: Priority = Priority.NORMAL,
    /** mode=TIMES 时生效。 */
    val recurrence: Recurrence = Recurrence(emptyList()),
    val enabled: Boolean = true,
    val calendarSync: Boolean = false,
    val mode: ScheduleMode = ScheduleMode.TIMES,
    /** mode=INTERVAL 时的基础间隔（分钟）。 */
    val intervalMin: Int = 25,
    /** mode=CRON 时的 5 段表达式。 */
    val cron: String = "",
)
