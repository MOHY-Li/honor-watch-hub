package dev.watchhub.app.source

import dev.watchhub.app.net.Http
import org.json.JSONArray
import org.json.JSONObject

/**
 * DeepSeek 聊天补全客户端（OpenAI 兼容协议）。
 *
 * 放在 source/ 是因为只有 source 层允许碰 net；引擎层通过它做网络调用。
 * 协议按 DeepSeek 官方文档实现，纯函数（[buildRequest]/[parseContent]/[friendlyError]）
 * 有单测；真实联调见 SPEC §12 人工清单。
 */
object DeepSeekClient {

    const val BASE_URL = "https://api.deepseek.com"
    const val MODEL = "deepseek-chat"

    class ApiException(message: String) : Exception(message)

    /**
     * 一次非流式对话。阻塞网络（可能要等模型生成，默认 120s），必须在后台线程调用。
     * 返回 assistant 消息内容；协议错误/HTTP 错误抛 [ApiException]。
     */
    fun chat(apiKey: String, system: String, user: String, timeoutMs: Int = 120_000): String {
        require(apiKey.isNotBlank()) { "API Key 为空" }
        val body = buildRequest(MODEL, system, user).toByteArray(Charsets.UTF_8)
        val text = try {
            Http.execute(
                "POST",
                "$BASE_URL/chat/completions",
                mapOf(
                    "Authorization" to "Bearer $apiKey",
                    "Content-Type" to "application/json",
                ),
                body,
                timeoutMs = timeoutMs,
            ).text()
        } catch (e: Http.HttpException) {
            throw ApiException(friendlyError(e.message))
        }
        return parseContent(text) ?: throw ApiException("响应格式不对：${text.take(120)}")
    }

    fun buildRequest(model: String, system: String, user: String): String =
        JSONObject().apply {
            put("model", model)
            put("stream", false)
            put("temperature", 1.0)
            put("max_tokens", 1024)
            put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
            )
        }.toString()

    /** 取 choices[0].message.content；结构不对或内容为空返回 null，绝不抛异常。 */
    fun parseContent(json: String): String? {
        return try {
            val o = JSONObject(json)
            val choices = o.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val content = choices.getJSONObject(0).optJSONObject("message")?.optString("content")
            content?.trim()?.ifEmpty { null }
        } catch (_: Throwable) {
            null
        }
    }

    /** 把 HTTP/网络错误翻成用户能看懂的中文。 */
    fun friendlyError(raw: String?): String = when {
        raw == null -> "未知错误"
        raw.contains("HTTP 401") -> "API Key 无效（401）"
        raw.contains("HTTP 402") -> "DeepSeek 账户余额不足（402）"
        raw.contains("HTTP 429") -> "请求太频繁，稍后再试（429）"
        else -> raw.take(120)
    }
}
