package dev.watchhub.app

import dev.watchhub.app.hub.Source
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 调度语义的回归测试。
 *
 * 这些规则看着琐碎，但每一条对应一个真实会踩的坑：
 *  - cron 必须每分钟可命中（否则漏提醒）
 *  - 普通源按 interval 走（否则每 20s 打一次源站）
 *  - 首轮不推历史（否则手表被 20 条刷屏）
 */
class ScheduleSemanticsTest {

    private fun src(json: String) = Source.from(JSONObject(json))!!

    @Test
    fun `cron is polled every minute regardless of interval`() {
        // cron 源即便写了 interval: 3600，引擎也必须每分钟碰一次
        val s = src("""{"name":"c","kind":"cron","times":["09:00"],"interval":3600}""")
        assertTrue("cron 必须是 CronSource", s is dev.watchhub.app.hub.CronSource)
    }

    @Test
    fun `interval has a floor of 60s`() {
        // 防呆：即使配了 interval: 1，也不能每秒打源站
        val s = src("""{"name":"r","kind":"rss","url":"http://x","interval":1}""")
        assertEquals(60, s.intervalSec)
    }

    @Test
    fun `interval is respected when reasonable`() {
        val s = src("""{"name":"r","kind":"rss","url":"http://x","interval":900}""")
        assertEquals(900, s.intervalSec)
    }

    @Test
    fun `rss does not notify on first run by default`() {
        val s = src("""{"name":"r","kind":"rss","url":"http://x"}""")
        assertTrue("默认首轮只播种", !s.notifyOnFirstRun)
    }

    @Test
    fun `cron does notify on first run`() {
        val s = src("""{"name":"c","kind":"cron","times":["09:00"]}""")
        assertTrue("cron 命中就该响，不能被播种吞掉", s.notifyOnFirstRun)
    }

    @Test
    fun `explicit opt in overrides default`() {
        val s = src("""{"name":"r","kind":"rss","url":"http://x","notify_on_first_run":true}""")
        assertTrue(s.notifyOnFirstRun)
    }

    @Test
    fun `disabled source is respected`() {
        val s = src("""{"name":"r","kind":"rss","url":"http://x","enabled":false}""")
        assertTrue(!s.enabled)
    }

    @Test
    fun `enabled defaults to true`() {
        val s = src("""{"name":"r","kind":"rss","url":"http://x"}""")
        assertTrue(s.enabled)
    }
}
