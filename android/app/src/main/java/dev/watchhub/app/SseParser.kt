package dev.watchhub.app

/**
 * SSE 行协议解析器（纯 Kotlin，无 Android 依赖 → 可单测）。
 *
 * EventStream 格式：以空行分隔的事件块，块内是 `field: value` 行。
 *  - `:` 开头是注释（心跳），忽略
 *  - `data:` 多行会拼接成以 \n 连接的字符串
 *  - `id:` 会写回 [lastEventId]，用于断线续传
 */
class SseParser {

    var lastEventId: Long = 0L
        private set

    private var event: String? = null
    private var id: Long = 0L
    private val data = StringBuilder()

    /** 喂一行（不含换行符）。若该行结束了一个事件，返回 (event, id, data)，否则 null。 */
    fun feed(line: String): SseEvent? {
        when {
            line.isEmpty() -> {
                val e = if (event != null || data.isNotEmpty()) {
                    SseEvent(event ?: "message", id, data.toString())
                } else null
                if (e != null && e.id > 0) lastEventId = e.id
                event = null
                id = 0L
                data.setLength(0)
                return e
            }
            line.startsWith(":") -> return null
            line.startsWith("event:") -> event = line.substring(6).trim()
            line.startsWith("id:") -> id = line.substring(3).trim().toLongOrNull() ?: 0L
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.substring(5).trimStart())
            }
            // retry:/未知字段：忽略
        }
        return null
    }

    /** 一次性喂多行，方便测试。 */
    fun feedAll(text: String): List<SseEvent> {
        val out = mutableListOf<SseEvent>()
        for (line in text.split("\n", "\r\n")) {
            val e = feed(line)
            if (e != null) out.add(e)
        }
        return out
    }
}

data class SseEvent(val event: String, val id: Long, val data: String)
