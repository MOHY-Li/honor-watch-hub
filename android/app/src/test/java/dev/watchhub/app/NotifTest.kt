package dev.watchhub.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifTest {

    @Test
    fun `parses full notification`() {
        val n = Notif.fromJson(
            JSONObject(
                """
                {"id":128,"ts":"2026-02-11T09:30:00+08:00","title":"进击的巨人 第12话",
                 "body":"已更新 · bangumi","detail":"长正文","priority":"high",
                 "tags":["anime","番剧"],"url":"https://x/y","source":"bangumi","key":"k"}
                """.trimIndent()
            )
        )
        assertEquals(128L, n.id)
        assertEquals("进击的巨人 第12话", n.title)
        assertEquals(listOf("anime", "番剧"), n.tags)
        assertEquals("bangumi", n.source)
        assertTrue(n.isHigh)
    }

    @Test
    fun `missing fields default safely`() {
        val n = Notif.fromJson(JSONObject("""{"id":1}"""))
        assertEquals(1L, n.id)
        assertEquals("（无标题）", n.title)
        assertEquals("default", n.priority)
        assertTrue(n.tags.isEmpty())
        assertFalse(n.isHigh)
    }

    @Test
    fun `fingerprint distinguishes same id different body`() {
        val a = Notif(1, "", "t", "a", "", "default", emptyList(), "", "")
        val b = Notif(1, "", "t", "b", "", "default", emptyList(), "", "")
        assertTrue(a.fingerprint() != b.fingerprint())
    }
}
