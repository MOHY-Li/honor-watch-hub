package dev.watchhub.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {

    @Test
    fun `hello event is parsed`() {
        val p = SseParser()
        val ev = p.feedAll(
            """
            retry: 3000
            event: hello
            data: {"server":"1.0.0","cursor":7}

            """.trimIndent() + "\n"
        )
        assertEquals(1, ev.size)
        assertEquals("hello", ev[0].event)
        assertTrue(ev[0].data.contains("\"cursor\":7"))
    }

    @Test
    fun `heartbeat comment is ignored`() {
        val p = SseParser()
        val ev = p.feedAll(": ping\n\n")
        assertTrue(ev.isEmpty())
    }

    @Test
    fun `notification carries id for resume`() {
        val p = SseParser()
        val ev = p.feedAll(
            "id: 128\nevent: notification\ndata: {\"id\":128,\"title\":\"番剧更新\"}\n\n"
        )
        assertEquals(1, ev.size)
        assertEquals(128L, ev[0].id)
        assertEquals("notification", ev[0].event)
        // 关键：id 要能被续传逻辑读到
        assertEquals(128L, p.lastEventId)
    }

    @Test
    fun `multi line data is joined with newline`() {
        val p = SseParser()
        val ev = p.feedAll("event: x\ndata: a\ndata: b\n\n")
        assertEquals("a\nb", ev[0].data)
    }

    @Test
    fun `two events in one chunk`() {
        val p = SseParser()
        val ev = p.feedAll("id: 1\nevent: n\ndata: one\n\nid: 2\nevent: n\ndata: two\n\n")
        assertEquals(2, ev.size)
        assertEquals("one", ev[0].data)
        assertEquals("two", ev[1].data)
        assertEquals(2L, p.lastEventId)
    }

    @Test
    fun `crlf line endings`() {
        val p = SseParser()
        val ev = p.feedAll("event: a\r\ndata: v\r\n\r\n")
        assertEquals(1, ev.size)
        assertEquals("v", ev[0].data)
    }

    @Test
    fun `blank line with no event yields nothing`() {
        val p = SseParser()
        assertNull(p.feed(""))
        assertNull(p.feed(": ping"))
        assertNull(p.feed("retry: 5000"))
    }
}
