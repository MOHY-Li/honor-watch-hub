package dev.watchhub.app.engine

import dev.watchhub.app.core.model.HealthRule
import dev.watchhub.app.core.model.MetricType
import dev.watchhub.app.core.model.Priority
import dev.watchhub.app.core.model.Recurrence
import dev.watchhub.app.core.model.Reminder
import dev.watchhub.app.core.model.RuleOp
import dev.watchhub.app.core.model.ScheduleMode
import dev.watchhub.app.source.SourceDef
import org.json.JSONArray
import org.json.JSONObject

/**
 * 配置导出/导入的纯函数部分：源 / 提醒 / 健康规则 / 非敏感设置 ⇄ JSON。
 *
 * 安全约定：**绝不导出 API Key 等凭据**（设置走白名单键）。
 * 导入为合并语义（同名 upsert，不删未提及项）；解析是全量的——
 * 任何一条非法整体拒绝，绝不应用一半。放 engine 是因为要同时看见
 * SourceDef（source 层）和各模型（core 层）。
 */
object ConfigExchange {

    const val FORMAT = 1

    /** 导出的设置白名单。新增可导设置时在这里登记；凭据类键永远不进这里。 */
    val EXPORTABLE_SETTINGS = listOf("start_on_boot", "calendar_enabled", "ai_auto_weekly")

    data class Parsed(
        val sources: List<SourceDef>,
        val reminders: List<Reminder>,
        val rules: List<HealthRule>,
        val settings: Map<String, Boolean>,
    )

    fun export(
        sources: List<SourceDef>,
        reminders: List<Reminder>,
        rules: List<HealthRule>,
        settings: Map<String, Boolean>,
    ): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("app", "watchhub")
        val srcArr = JSONArray()
        sources.forEach { srcArr.put(it.raw) }
        root.put("sources", srcArr)
        val remArr = JSONArray()
        reminders.forEach { remArr.put(reminderToJson(it)) }
        root.put("reminders", remArr)
        val ruleArr = JSONArray()
        rules.forEach { ruleArr.put(ruleToJson(it)) }
        root.put("health_rules", ruleArr)
        root.put("settings", JSONObject().apply { EXPORTABLE_SETTINGS.forEach { k -> put(k, settings[k] ?: false) } })
        return root.toString(2)
    }

    fun parse(json: String): Parsed {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("不是合法的 JSON 文件: ${e.message}")
        }
        if (root.optInt("format", -1) != FORMAT) {
            throw IllegalArgumentException("不认识的配置版本（format=${root.optInt("format", -1)}），需要 $FORMAT")
        }
        val sources = section(root, "sources") { i, o ->
            SourceDef.parse(o) ?: throw IllegalArgumentException("sources 第 ${i + 1} 条缺少 name 或 kind 不合法")
        }
        val reminders = section(root, "reminders") { i, o -> reminderFromJson(o, i) }
        val rules = section(root, "health_rules") { i, o -> ruleFromJson(o, i) }
        val settingsObj = root.optJSONObject("settings") ?: JSONObject()
        val settings = EXPORTABLE_SETTINGS.filter { settingsObj.has(it) }.associateWith { settingsObj.getBoolean(it) }
        return Parsed(sources, reminders, rules, settings)
    }

    private fun <T> section(root: JSONObject, key: String, map: (Int, JSONObject) -> T): List<T> {
        val arr = root.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<T>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: throw IllegalArgumentException("$key 第 ${i + 1} 条不是 JSON 对象")
            out.add(map(i, o))
        }
        return out
    }

    // ---------------- Reminder ⇄ JSON ----------------

    private fun reminderToJson(r: Reminder): JSONObject = JSONObject().apply {
        put("name", r.name)
        put("title", r.title)
        put("body", r.body)
        put("priority", r.priority.wire)
        put("times", JSONArray(r.recurrence.times.sorted().map { "%02d:%02d".format(it.hour, it.minute) }))
        // days 序列化为 0=周一 的整数（与 ReminderStore 存储口径一致），枚举名回来解析不了
        put("days", JSONArray(r.recurrence.days.sortedBy { it.value }.map { it.value - 1 }))
        put("enabled", r.enabled)
        put("calendar", r.calendarSync)
        put("mode", r.mode.name)
        put("interval_min", r.intervalMin)
        put("cron", r.cron)
    }

    private fun reminderFromJson(o: JSONObject, index: Int): Reminder {
        val name = o.optString("name").trim()
        if (name.isEmpty()) throw IllegalArgumentException("reminders 第 ${index + 1} 条缺少 name")
        val times = o.optJSONArray("times")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        val days = o.optJSONArray("days")?.let { a -> (0 until a.length()).mapNotNull { a.optInt(it).takeIf { d -> d in 0..6 } } }
            ?: emptyList()
        return Reminder(
            name = name,
            title = o.optString("title").ifBlank { name },
            body = o.optString("body"),
            priority = Priority.parse(o.optString("priority")),
            recurrence = Recurrence.parse(times, days),
            enabled = o.optBoolean("enabled", true),
            calendarSync = o.optBoolean("calendar", false),
            mode = ScheduleMode.parse(o.optString("mode")),
            intervalMin = o.optInt("interval_min", 25),
            cron = o.optString("cron"),
        )
    }

    // ---------------- HealthRule ⇄ JSON ----------------

    private fun ruleToJson(r: HealthRule): JSONObject = JSONObject().apply {
        put("name", r.name)
        put("metric", r.metric.name)
        put("op", r.op.name)
        put("threshold", r.threshold)
        put("sustained_min", r.sustainedMin)
        put("cooldown_min", r.cooldownMin)
        put("priority", r.priority.name)
        put("enabled", r.enabled)
    }

    private fun ruleFromJson(o: JSONObject, index: Int): HealthRule {
        val name = o.optString("name").trim()
        if (name.isEmpty()) throw IllegalArgumentException("health_rules 第 ${index + 1} 条缺少 name")
        val metric = MetricType.parse(o.optString("metric"))
            ?: throw IllegalArgumentException("health_rules 第 ${index + 1} 条指标类型不认识: ${o.optString("metric")}")
        return HealthRule(
            name = name,
            metric = metric,
            op = RuleOp.parse(o.optString("op")),
            threshold = o.optDouble("threshold", 0.0),
            sustainedMin = o.optInt("sustained_min", 15),
            cooldownMin = o.optInt("cooldown_min", 60),
            priority = Priority.parse(o.optString("priority")),
            enabled = o.optBoolean("enabled", true),
        )
    }
}
