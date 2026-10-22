package dev.watchhub.app.engine

import dev.watchhub.app.core.model.Priority
import dev.watchhub.app.core.model.Recurrence
import dev.watchhub.app.core.model.Reminder
import dev.watchhub.app.source.SourceDef
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1 配置（SharedPreferences 里的 JSON 数组）→ v2 模型。
 *
 * 抽成纯函数是为了能单测：迁移错一个字段，用户手机上的源/提醒就丢了，
 * 这是整个重写里最不能出错的一段。
 */
object V1Config {

    data class Parsed(val sources: List<SourceDef>, val reminders: List<Reminder>)

    fun parse(json: String): Parsed {
        val arr = try {
            JSONArray(json)
        } catch (_: Exception) {
            return Parsed(emptyList(), emptyList())
        }
        val sources = ArrayList<SourceDef>()
        val reminders = ArrayList<Reminder>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            when (val kind = o.optString("kind", "rss")) {
                "cron" -> parseCron(o, i)?.let { reminders.add(it) }
                else -> {
                    // v1 的 kind 字段直接复用；SourceDef.parse 会校验 url/kind
                    val copy = JSONObject(o.toString()).apply { put("kind", kind) }
                    SourceDef.parse(copy)?.let { sources.add(it) }
                }
            }
        }
        return Parsed(sources, reminders)
    }

    private fun parseCron(o: JSONObject, index: Int): Reminder? {
        val name = o.optString("name").trim().ifBlank { "reminder_$index" }
        val times = when (val t = o.opt("times")) {
            is JSONArray -> (0 until t.length()).map { t.optString(it) }
            is String -> listOf(t)
            else -> emptyList()
        }
        val dayArray = o.optJSONArray("days")
        val days = if (dayArray == null) emptyList() else
            (0 until dayArray.length()).mapNotNull { dayArray.optInt(it, -1).takeIf { d -> d in 0..6 } }
        if (times.isEmpty()) return null
        return Reminder(
            name = name,
            title = o.optString("title", name),
            body = o.optString("body", ""),
            priority = Priority.parse(o.optString("priority", "high")),
            recurrence = Recurrence.parse(times, days),
            enabled = o.optBoolean("enabled", true),
            calendarSync = false,
        )
    }
}
