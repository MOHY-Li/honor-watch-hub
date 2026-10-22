package dev.watchhub.app.core

import dev.watchhub.app.core.model.Task

/**
 * 任务到期规则。纯函数，是「错过补偿」的唯一实现，单测覆盖。
 *
 * 规则（AGENTS.md 第 3 条第 6 点）：
 * 同一条重复提醒只补发**最近一次**过期未提醒的实例，更早的标 MISSED，不推。
 * 这样手机离线三天后不会一次炸出十几条喝水提醒。
 */
object TaskRules {

    /** 迟到判定阈值：超过 60 秒才算「迟到」，避免闹钟早几百毫秒触发时误标。 */
    const val LATE_THRESHOLD_MS = 60_000L

    data class Plan(val deliver: List<Task>, val missed: List<Task>)

    /**
     * [open] 传入所有 OPEN 状态的任务；返回本轮应该生成提醒的（每条提醒至多一条）和应该标记错过的。
     */
    fun plan(open: List<Task>, now: Long): Plan {
        val eligible = open.filter {
            it.status == dev.watchhub.app.core.model.TaskStatus.OPEN &&
                it.alertedAt == 0L &&
                it.effectiveDueAt <= now
        }
        val deliver = ArrayList<Task>()
        val missed = ArrayList<Task>()
        eligible.groupBy { it.reminderId }.forEach { (_, list) ->
            val sorted = list.sortedBy { it.effectiveDueAt }
            if (sorted.size > 1) missed.addAll(sorted.dropLast(1))
            deliver.add(sorted.last())
        }
        return Plan(deliver.sortedBy { it.effectiveDueAt }, missed)
    }

    fun isLate(task: Task, now: Long): Boolean = now - task.effectiveDueAt > LATE_THRESHOLD_MS

    /**
     * 迟到提醒的正文：必须写明原定时间，否则用户不知道这是补的还是刚到的。
     *
     * zone 显式注入：这个函数只做展示格式化，但不能依赖运行机器的默认时区，
     * 否则在 CI（UTC）和手机上（Asia/Shanghai）会得到不同文案，测试也不可重复。
     */
    fun lateBody(task: Task, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        val at = java.time.Instant.ofEpochMilli(task.effectiveDueAt).atZone(zone)
        val original = java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(at)
        val prefix = "迟到 · 原定 $original"
        return if (task.body.isBlank()) prefix else "$prefix · ${task.body}"
    }
}
