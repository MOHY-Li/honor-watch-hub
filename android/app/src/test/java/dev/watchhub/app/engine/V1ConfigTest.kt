package dev.watchhub.app.engine

import dev.watchhub.app.core.model.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class V1ConfigTest {

    private val v1Json = """
        [
          {
            "name": "bangumi",
            "kind": "json",
            "url": "https://api.bgm.tv/v0/episodes?subject_id=1",
            "list_path": "data",
            "interval": 900,
            "priority": "high",
            "tags": ["anime"],
            "fields": {"title": "name_cn", "key": "id"}
          },
          {
            "name": "drink_water",
            "kind": "cron",
            "times": ["09:30", "11:30"],
            "days": [0, 1, 2, 3, 4],
            "title": "喝水",
            "body": "起来走两步",
            "priority": "high"
          },
          {
            "name": "page",
            "kind": "hash",
            "url": "https://example.com/x",
            "interval": 60,
            "enabled": false
          },
          {
            "name": "bad",
            "kind": "unknown"
          }
        ]
    """.trimIndent()

    @Test
    fun parse_splitsSourcesAndReminders() {
        val parsed = V1Config.parse(v1Json)
        assertEquals(listOf("bangumi", "page"), parsed.sources.map { it.name })
        assertEquals(listOf("drink_water"), parsed.reminders.map { it.name })
    }

    @Test
    fun parse_cronBecomesReminder() {
        val r = V1Config.parse(v1Json).reminders.first()
        assertEquals("喝水", r.title)
        assertEquals(Priority.HIGH, r.priority)
        assertEquals(2, r.recurrence.times.size)
        assertEquals(
            setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            r.recurrence.days,
        )
        assertTrue(r.enabled)
    }

    @Test
    fun parse_preservesSourceEnabled() {
        val page = V1Config.parse(v1Json).sources.first { it.name == "page" }
        assertFalse(page.enabled)
        assertEquals(60, page.intervalSec)
    }

    @Test
    fun parse_invalidKindSkipped() {
        assertTrue(V1Config.parse(v1Json).sources.none { it.name == "bad" })
    }

    @Test
    fun parse_brokenJsonReturnsEmpty() {
        val parsed = V1Config.parse("not json")
        assertTrue(parsed.sources.isEmpty())
        assertTrue(parsed.reminders.isEmpty())
    }

    @Test
    fun parse_cronWithoutTimesSkipped() {
        val json = """[{"name":"x","kind":"cron","title":"无时间","times":[]}]"""
        assertTrue(V1Config.parse(json).reminders.isEmpty())
    }

    @Test
    fun parse_cronSingleTimeString() {
        val json = """[{"name":"x","kind":"cron","times":"08:00","title":"早"}]"""
        val r = V1Config.parse(json).reminders.first()
        assertEquals(1, r.recurrence.times.size)
        assertTrue(r.recurrence.daily)
    }
}
