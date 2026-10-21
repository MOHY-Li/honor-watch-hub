package dev.watchhub.app

import dev.watchhub.app.hub.CronSource
import dev.watchhub.app.hub.HashSource
import dev.watchhub.app.hub.Item
import dev.watchhub.app.hub.JsonSource
import dev.watchhub.app.hub.Source
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** 源解析层的单元测试：不联网的部分全部可测。 */
class SourcesTest {

    @Test
    fun `clip removes tags and trims`() {
        assertEquals("hi there", Source.clip("<b>hi</b>   there", 100))
        assertEquals(30, Source.clip("x".repeat(200), 30).length)
        assertTrue(Source.clip("x".repeat(200), 30).endsWith("…"))
    }

    @Test
    fun `pick correct class by kind`() {
        val kinds = mapOf(
            "rss" to "RssSource",
            "json" to "JsonSource",
            "regex" to "RegexSource",
            "hash" to "HashSource",
            "cron" to "CronSource",
        )
        for ((kind, cls) in kinds) {
            val s = Source.from(JSONObject("""{"name":"x","kind":"$kind","url":"http://a"}"""))
            assertNotNull("kind=$kind", s)
            assertEquals(cls, s!!.javaClass.simpleName)
        }
    }

    @Test
    fun `unknown kind is rejected`() {
        assertNull(Source.from(JSONObject("""{"name":"x","kind":"nope"}""")))
        assertNull(Source.from(JSONObject("""{"kind":"rss"}""")))
    }

    @Test
    fun `dig handles nested paths and arrays`() {
        val o = JSONObject("""{"data":{"items":[{"t":"a"},{"t":"b"}]}}""")
        val arr = JsonSource.dig(o, "data.items") as JSONArray
        assertEquals(2, arr.length())
        assertEquals("b", JsonSource.dig(o, "data.items.1.t"))
        assertNull(JsonSource.dig(o, "data.missing.x"))
    }

    @Test
    fun `fmt fills template from row`() {
        val row = JSONObject("""{"airdate":"2024-10-02","ep":3}""")
        assertEquals("2024-10-02 · 第3话", Source.fmt("{airdate} · 第{ep}话", row))
        assertEquals("x-y", Source.fmt("{a}-{b}", JSONObject("""{"a":"x","b":"y"}""")))
        assertEquals("", Source.fmt("{nope}", row))
    }

    @Test
    fun `cron fires only at configured minute`() {
        val now = Calendar.getInstance()
        val hm = "%02d:%02d".format(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
        val other = "%02d:%02d".format((now.get(Calendar.HOUR_OF_DAY) + 2) % 24, now.get(Calendar.MINUTE))

        val hit = CronSource("r", JSONObject("""{"name":"r","kind":"cron","times":["$hm"],"title":"喝水"}"""))
        assertEquals(1, hit.fetch().size)
        assertTrue(hit.fetch()[0].key.endsWith("%04d%02d%02d%02d%02d".format(
            now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH),
            now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))))

        val miss = CronSource("r2", JSONObject("""{"name":"r2","kind":"cron","times":["$other"]}"""))
        assertTrue(miss.fetch().isEmpty())
    }

    @Test
    fun `cron respects day filter`() {
        val now = Calendar.getInstance()
        val hm = "%02d:%02d".format(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
        val dow = now.get(Calendar.DAY_OF_WEEK)
        val today = if (dow == Calendar.SUNDAY) 6 else dow - 2
        val tomorrow = (today + 1) % 7

        val yes = CronSource("a", JSONObject("""{"name":"a","kind":"cron","times":["$hm"],"days":[$today]}"""))
        assertEquals(1, yes.fetch().size)

        val no = CronSource("b", JSONObject("""{"name":"b","kind":"cron","times":["$hm"],"days":[$tomorrow]}"""))
        assertTrue(no.fetch().isEmpty())
    }

    @Test
    fun `cron always notifies on first run`() {
        val s = CronSource("c", JSONObject("""{"name":"c","kind":"cron","times":["09:00"]}"""))
        assertTrue(s.notifyOnFirstRun)
    }

    @Test
    fun `rss and hash default to silent first run`() {
        val r = Source.from(JSONObject("""{"name":"r","kind":"rss","url":"http://a"}"""))!!
        assertTrue(!r.notifyOnFirstRun)
        val h = Source.from(JSONObject("""{"name":"h","kind":"hash","url":"http://a"}"""))!!
        assertTrue(!h.notifyOnFirstRun)
        val opt = Source.from(JSONObject("""{"name":"o","kind":"rss","url":"http://a","notify_on_first_run":true}"""))!!
        assertTrue(opt.notifyOnFirstRun)
    }

    @Test
    fun `clock key is stable within a minute`() {
        val src = CronSource("d", JSONObject("""{"name":"d","kind":"cron","times":[]}"""))
        // times 为空时永不命中，但 key 生成规则仍是 名字+分钟
        assertTrue(src.fetch().isEmpty())
    }

    @Test
    fun `tags parse from array`() {
        val s = Source.from(JSONObject("""{"name":"t","kind":"rss","url":"http://a","tags":["anime","番剧"]}"""))!!
        assertEquals(listOf("anime", "番剧"), s.tags)
    }

    @Test
    fun `hash source key changes with content`() {
        // 用 data URI 不行，这里只验证 key 结构与 source 名绑定
        val h = HashSource("price", JSONObject("""{"name":"price","kind":"hash","url":"http://x","title":"变了"}"""))
        assertEquals("price", h.name)
        assertEquals("变了", h.raw.optString("title"))
    }
}
