package dev.watchhub.app.engine

import android.content.Context
import dev.watchhub.app.core.Clock
import dev.watchhub.app.core.LogBus
import dev.watchhub.app.data.ReminderStore
import dev.watchhub.app.data.SettingsStore
import dev.watchhub.app.data.SourceStore

/**
 * v1 → v2 迁移的执行者。
 *
 * v1 的源配置在 SharedPreferences（`watchhub_sources/sources_json`），
 * `cron` 类型在 v2 里变成 Reminder，其余变成 SourceDef。
 * 迁移只跑一次（settings.migrated_v1），解析逻辑在 [V1Config]（纯函数、有单测）。
 */
class Migrator(
    private val ctx: Context,
    private val settings: SettingsStore,
    private val sources: SourceStore,
    private val reminders: ReminderStore,
    private val clock: Clock,
) {

    data class Result(val sources: Int, val reminders: Int, val seeded: Boolean, val skipped: Boolean)

    fun migrateIfNeeded(): Result {
        seedAiFeedOnce()
        if (settings.migratedV1) return Result(0, 0, false, skipped = true)

        val oldJson = ctx.getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE)
            .getString("sources_json", null)

        var sourceCount = 0
        var reminderCount = 0
        if (oldJson != null) {
            runCatching { V1Config.parse(oldJson) }
                .onSuccess { parsed ->
                    parsed.sources.forEach { sources.upsert(it) }
                    parsed.reminders.forEach { reminders.upsert(it) }
                    sourceCount = parsed.sources.size
                    reminderCount = parsed.reminders.size
                }
                .onFailure { LogBus.post("v1 配置迁移失败（保留原数据）: ${it.message}") }
        }

        // 没有旧配置（新装）或解析出来是空的：给一份能直接跑的示例
        var seeded = false
        if (sources.isEmpty() && reminders.isEmpty()) {
            seedDefaults()
            seeded = true
        }

        // v1 的 want_run 决定升级后是否自动继续跑
        if (!settings.wantRun) {
            val oldWantRun = ctx.getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE)
                .getBoolean("want_run", false)
            if (oldWantRun) settings.wantRun = true
        }

        settings.migratedV1 = true
        LogBus.post("迁移完成：源 $sourceCount 个、提醒 $reminderCount 条" + if (seeded) "（已写入示例）" else "")
        return Result(sourceCount, reminderCount, seeded, skipped = false)
    }

    /**
     * 2.4.0：用户要求先加一个 AI 订阅。只执行一次，且已有同名/同 URL 源时跳过；
     * 用户之后在「源管理」里删掉也不会被重新加回来。
     */
    private fun seedAiFeedOnce() {
        if (settings.getBool(KEY_SEEDED_AI, false)) return
        settings.putBool(KEY_SEEDED_AI, true)
        val existing = sources.all()
        if (existing.any { it.name == AI_FEED_NAME || it.raw.optString("url") == AI_FEED_URL }) return
        runCatching {
            dev.watchhub.app.source.SourceDef.parse(org.json.JSONObject(AI_FEED_JSON))?.let {
                sources.upsert(it)
                LogBus.post("已添加推荐订阅：${it.name}（可在「源管理」删除）")
            }
        }.onFailure { LogBus.post("添加推荐订阅失败: ${it.message}") }
    }

    private fun seedDefaults() {
        DEFAULT_SOURCES.forEach { json ->
            runCatching {
                dev.watchhub.app.source.SourceDef.parse(org.json.JSONObject(json))
                    ?.let { sources.upsert(it) }
            }
        }
        reminders.upsert(DEFAULT_REMINDER)
        LogBus.post("已写入内置示例：${DEFAULT_SOURCES.size} 个源 + 1 条提醒")
    }

    companion object {
        private const val OLD_PREFS = "watchhub_sources"
        private const val KEY_SEEDED_AI = "seeded_ai_feed_24"
        private const val AI_FEED_NAME = "qbitai"
        private const val AI_FEED_URL = "https://www.qbitai.com/feed"
        private val AI_FEED_JSON = """
            {
              "name": "qbitai",
              "kind": "rss",
              "url": "https://www.qbitai.com/feed",
              "interval": 900,
              "limit": 30,
              "priority": "normal",
              "tags": ["ai", "rss"]
            }
        """.trimIndent()

        val DEFAULT_SOURCES: List<String> = listOf(
            """
            {
              "name": "bangumi",
              "kind": "json",
              "url": "https://api.bgm.tv/v0/episodes?subject_id=425998&type=0&limit=20",
              "list_path": "data",
              "limit": 20,
              "interval": 900,
              "priority": "high",
              "tags": ["anime"],
              "fields": {"title": "name_cn", "key": "id", "body": "airdate", "url": "id"},
              "body_format": "{airdate} · 第{ep}话"
            }
            """.trimIndent(),
            """
            {
              "name": "page_watch",
              "kind": "hash",
              "url": "https://example.com/product/123",
              "interval": 1800,
              "title": "商品页有变化",
              "body": "价格或库存更新了",
              "priority": "high",
              "tags": ["shopping"],
              "enabled": false
            }
            """.trimIndent(),
        )

        val DEFAULT_REMINDER = dev.watchhub.app.core.model.Reminder(
            name = "drink_water",
            title = "喝水",
            body = "起来走两步，喝口水",
            priority = dev.watchhub.app.core.model.Priority.HIGH,
            recurrence = dev.watchhub.app.core.model.Recurrence.parse(
                listOf("09:30", "11:30", "14:30", "16:30"),
                listOf(0, 1, 2, 3, 4),
            ),
        )
    }
}
