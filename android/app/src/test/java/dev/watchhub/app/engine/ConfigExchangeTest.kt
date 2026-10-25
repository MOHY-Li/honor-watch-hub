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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 期望语义来自 SPEC 2026-10-25-watchhub-2.6.0 §3 A6–A8。 */
class ConfigExchangeTest {

    private fun sampleSource(): SourceDef = SourceDef.parse(
        JSONObject(
            """
            {"name":"qbitai","kind":"rss","url":"https://www.qbitai.com/feed",
             "interval":900,"limit":30,"priority":"high","tags":["ai"]}
            """,
        ),
    )!!

    private fun sampleReminder() = Reminder(
        name = "drink_water",
        title = "喝水",
        body = "起来走两步",
        priority = Priority.HIGH,
        recurrence = Recurrence.parse(listOf("09:30", "14:30"), listOf(0, 1, 2, 3, 4)),
        enabled = false,
        calendarSync = true,
        mode = ScheduleMode.INTERVAL,
        intervalMin = 30,
    )

    private fun sampleRule() = HealthRule(
        name = "心率过高",
        metric = MetricType.HEART_RATE,
        op = RuleOp.ABOVE,
        threshold = 100.0,
        sustainedMin = 30,
        cooldownMin = 90,
        priority = Priority.HIGH,
        enabled = false,
    )

    @Test
    fun roundTripKeepsEverything() {
        val json = ConfigExchange.export(
            sources = listOf(sampleSource()),
            reminders = listOf(sampleReminder()),
            rules = listOf(sampleRule()),
            settings = mapOf("start_on_boot" to false, "calendar_enabled" to true, "ai_auto_weekly" to true),
        )
        val parsed = ConfigExchange.parse(json)
        assertEquals(1, parsed.sources.size)
        assertEquals("qbitai", parsed.sources[0].name)
        assertEquals(900, parsed.sources[0].intervalSec)
        assertEquals(Priority.HIGH, parsed.sources[0].priority)

        assertEquals(1, parsed.reminders.size)
        val r = parsed.reminders[0]
        assertEquals("drink_water", r.name)
        assertEquals(ScheduleMode.INTERVAL, r.mode)
        assertEquals(30, r.intervalMin)
        assertEquals(listOf("09:30", "14:30"), r.recurrence.times.map { "%02d:%02d".format(it.hour, it.minute) }.sorted())
        assertEquals(listOf(0, 1, 2, 3, 4), r.recurrence.days.map { it.value - 1 }.sorted())
        assertFalse(r.enabled)
        assertTrue(r.calendarSync)

        assertEquals(1, parsed.rules.size)
        val rule = parsed.rules[0]
        assertEquals(MetricType.HEART_RATE, rule.metric)
        assertEquals(RuleOp.ABOVE, rule.op)
        assertEquals(100.0, rule.threshold, 0.0)
        assertEquals(90, rule.cooldownMin)
        assertFalse(rule.enabled)

        assertEquals(mapOf("start_on_boot" to false, "calendar_enabled" to true, "ai_auto_weekly" to true), parsed.settings)
    }

    @Test
    fun apiKeyNeverExported() {
        val json = ConfigExchange.export(listOf(sampleSource()), emptyList(), emptyList(), emptyMap())
        assertFalse("导出不得包含 api key 字段", json.contains("sk-"))
        assertFalse(JSONObject(json).has("ai_api_key"))
        assertFalse(JSONObject(json).getJSONObject("settings").toString().contains("key"))
    }

    @Test
    fun rejectsBadJson() {
        try {
            ConfigExchange.parse("not json at all")
            fail("坏 JSON 必须抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("JSON"))
        }
    }

    @Test
    fun rejectsUnknownVersion() {
        val o = JSONObject()
        o.put("format", 99)
        try {
            ConfigExchange.parse(o.toString())
            fail("未知版本必须拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("版本"))
        }
    }

    @Test
    fun rejectsInvalidEntriesWithoutPartialApply() {
        val o = JSONObject()
        o.put("format", ConfigExchange.FORMAT)
        val sources = JSONArray()
        sources.put(JSONObject("""{"name":"bad","kind":"rss","url":"https://x.com/feed"}"""))
        sources.put(JSONObject("""{"name":"no-kind","url":"https://x.com/feed"}"""))
        o.put("sources", sources)
        try {
            ConfigExchange.parse(o.toString())
            fail("非法条目必须整体拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("第 2 条"))
        }
    }

    @Test
    fun ignoresUnknownFieldsAndMissingSections() {
        val o = JSONObject()
        o.put("format", ConfigExchange.FORMAT)
        o.put("future_field", "whatever")
        val parsed = ConfigExchange.parse(o.toString())
        assertTrue(parsed.sources.isEmpty())
        assertTrue(parsed.reminders.isEmpty())
        assertTrue(parsed.rules.isEmpty())
        assertTrue(parsed.settings.isEmpty())
    }
}
