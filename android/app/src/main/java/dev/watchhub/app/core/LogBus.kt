package dev.watchhub.app.core

/**
 * 进程内日志环形缓冲。UI 的「日志」页读它，不需要落盘。
 *
 * 为什么不用 Timber/文件日志：这个 App 的诊断信息只需要最近几百条，
 * 落盘反而要处理清理和权限，收益为零。
 */
object LogBus {

    private const val CAP = 400
    private val lines = ArrayDeque<String>(CAP)
    private val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")

    @Synchronized
    fun post(msg: String) {
        val ts = java.time.Instant.ofEpochMilli(System.currentTimeMillis())
            .atZone(java.time.ZoneId.systemDefault())
        val line = "${ts.format(fmt)} $msg"
        lines.addLast(line)
        while (lines.size > CAP) lines.removeFirst()
    }

    @Synchronized
    fun tail(n: Int = 200): List<String> {
        val k = n.coerceIn(1, CAP)
        return if (lines.size <= k) lines.toList() else lines.toList().takeLast(k)
    }

    @Synchronized
    fun clear() = lines.clear()
}
