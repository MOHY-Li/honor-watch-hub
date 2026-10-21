package dev.watchhub.app

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 进程内日志总线：Service 写，Activity 观察。只为让调试期能看到发生什么。 */
object LogBus {
    private const val MAX = 200
    private val lines = ArrayDeque<String>()
    private val listeners = mutableSetOf<(List<String>) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun post(msg: String) {
        synchronized(lines) {
            lines.addLast("${fmt.format(Date())}  $msg")
            while (lines.size > MAX) lines.removeFirst()
            val snapshot = lines.toList()
            main.post { listeners.toList().forEach { it(snapshot) } }
        }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun addListener(l: (List<String>) -> Unit) {
        synchronized(lines) { listeners.add(l) }
        l(snapshot())
    }

    fun removeListener(l: (List<String>) -> Unit) {
        synchronized(lines) { listeners.remove(l) }
    }
}
