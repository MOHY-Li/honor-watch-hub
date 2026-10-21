package dev.watchhub.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议一致性测试：解析「真实 Python 服务端录下来的 SSE 字节流」。
 *
 * fixture 由 `python server/tests/record_sse_fixture.py` 生成。
 * 如果哪天服务端改了事件格式而客户端没跟上，这个测试会立刻失败。
 */
class SseProtocolFixtureTest {

    private val capture: String by lazy {
        javaClass.classLoader!!.getResourceAsStream("sse_capture.txt")!!
            .bufferedReader(Charsets.UTF_8).readText()
    }

    @Test
    fun `capture file is present and non trivial`() {
        assertTrue(capture.length > 100)
        assertTrue(capture.contains("event: hello"))
        assertTrue(capture.contains("event: notification"))
    }

    @Test
    fun `parser understands real server stream`() {
        val parser = SseParser()
        val events = parser.feedAll(capture)
        assertEquals("hello", events.first().event)

        val notifications = events.filter { it.event == "notification" }
        assertTrue("至少 2 条通知", notifications.size >= 2)
        assertTrue("id 递增", notifications.last().id > notifications.first().id)
        assertEquals("lastEventId 应等于最后一条 id", notifications.last().id, parser.lastEventId)

        // 用 App 的真实模型解析
        val notifs = notifications.map { Notif.fromJson(JSONObject(it.data)) }
        assertTrue(notifs.all { it.id > 0 && it.title.isNotBlank() })
        assertEquals("连接时补发", notifs.first { it.title == "历史通知" }.body)
        assertTrue("high 优先级被识别", notifs.any { it.isHigh && it.tags.contains("anime") })
    }

    @Test
    fun `notification title fits watch constraints`() {
        val parser = SseParser()
        val notifs = parser.feedAll(capture)
            .filter { it.event == "notification" }
            .map { Notif.fromJson(JSONObject(it.data)) }
        // 手表镜像对长度敏感：服务端契约要求 <= 30
        assertTrue(notifs.all { it.title.length <= 30 })
    }
}

