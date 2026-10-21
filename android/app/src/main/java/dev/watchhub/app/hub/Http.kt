package dev.watchhub.app.hub

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * 极简 HTTP，只用 JDK 自带 API（无 OkHttp 依赖）。
 *
 * 为什么不用 OkHttp：这个 App 的所有网络请求加起来只有
 * 「GET 一个 URL，读全部字节」这一种形态，引入 1MB 的库不值得。
 */
object Http {

    private const val UA = "WatchHub/2.0 (Android; self-hosted)"
    private const val TIMEOUT = 20_000

    fun getBytes(url: String, extraHeaders: Map<String, String> = emptyMap()): ByteArray {
        require(url.startsWith("http://") || url.startsWith("https://")) { "非法 URL: $url" }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/json, text/html;q=0.8, */*;q=0.5")
            setRequestProperty("Accept-Encoding", "gzip")
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.let { String(it.readBytes(), Charsets.UTF_8).take(200) }.orEmpty()
                throw IllegalStateException("HTTP $code $err")
            }
            val raw = conn.inputStream.use { it.readBytes() }
            val encoding = conn.contentEncoding?.lowercase().orEmpty()
            val bytes = if (encoding.contains("gzip")) {
                GZIPInputStream(raw.inputStream()).use { it.readBytes() }
            } else raw
            if (bytes.isEmpty()) throw IllegalStateException("响应为空")
            return bytes
        } finally {
            conn.disconnect()
        }
    }

    fun getString(url: String, extraHeaders: Map<String, String> = emptyMap()): String =
        String(getBytes(url, extraHeaders), Charsets.UTF_8)
}
