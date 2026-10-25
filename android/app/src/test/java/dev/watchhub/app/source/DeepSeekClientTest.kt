package dev.watchhub.app.source

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 协议期望值来自 DeepSeek 官方文档（OpenAI 兼容 chat/completions）。 */
class DeepSeekClientTest {

    @Test
    fun parseContentTakesFirstChoice() {
        val json = """
            {"choices":[{"message":{"role":"assistant","content":" 报告内容 "}}],
             "usage":{"total_tokens":10}}
        """.trimIndent()
        assertEquals("报告内容", DeepSeekClient.parseContent(json))
    }

    @Test
    fun parseContentRejectsBadPayloads() {
        assertNull(DeepSeekClient.parseContent("""{"error":{"message":"x"}}"""))
        assertNull(DeepSeekClient.parseContent("""{"choices":[]}"""))
        assertNull(DeepSeekClient.parseContent("""{"choices":[{"message":{"content":"   "}}]}"""))
        assertNull(DeepSeekClient.parseContent("not json"))
    }

    @Test
    fun buildRequestShape() {
        val json = DeepSeekClient.buildRequest("deepseek-chat", "系统提示", "用户数据")
        val o = JSONObject(json)
        assertEquals("deepseek-chat", o.getString("model"))
        assertEquals(false, o.getBoolean("stream"))
        val messages = o.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("系统提示", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("用户数据", messages.getJSONObject(1).getString("content"))
    }

    @Test
    fun friendlyErrorMapsCommonCodes() {
        assertEquals("API Key 无效（401）", DeepSeekClient.friendlyError("HttpException: HTTP 401 Unauthorized"))
        assertEquals("DeepSeek 账户余额不足（402）", DeepSeekClient.friendlyError("HTTP 402 Payment Required"))
        assertEquals("请求太频繁，稍后再试（429）", DeepSeekClient.friendlyError("HTTP 429 Too Many Requests"))
        assertTrue(DeepSeekClient.friendlyError("ConnectTimeout: timeout").contains("ConnectTimeout"))
    }
}
