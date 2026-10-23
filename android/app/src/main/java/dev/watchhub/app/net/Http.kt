package dev.watchhub.app.net

/**
 * 极简 HTTP，只用 JDK 自带 API（无 OkHttp 依赖）。
 *
 * 这个 App 的网络请求形态只有「GET/POST 一个 URL，读完响应」。
 * 支持：自定义 method/headers/body、gzip、重定向、超时、响应大小上限。
 */
object Http {

    const val DEFAULT_TIMEOUT_MS = 20_000
    const val DEFAULT_MAX_BYTES = 4 * 1024 * 1024

    // 用浏览器形态的 UA：不少站点（如量子位）会对非浏览器 UA 直接 403。
    // 末尾保留 WatchHub 标识，方便源站识别。
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; WatchHub/2.4) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    data class Response(val code: Int, val body: ByteArray, val contentType: String?) {
        fun text(): String = String(body, Charsets.UTF_8)
    }

    class HttpException(message: String) : Exception(message)

    fun execute(
        method: String = "GET",
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        maxBytes: Int = DEFAULT_MAX_BYTES,
    ): Response {
        require(url.startsWith("http://") || url.startsWith("https://")) { "非法 URL: $url" }
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = method.uppercase()
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty(
                "Accept",
                "application/rss+xml, application/atom+xml, application/json, text/html;q=0.8, */*;q=0.5"
            )
            setRequestProperty("Accept-Encoding", "gzip")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Length", body.size.toString())
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.use { readLimited(it, maxBytes) } ?: ByteArray(0)
            val encoding = conn.contentEncoding?.lowercase().orEmpty()
            val bytes = if (encoding.contains("gzip") && raw.isNotEmpty()) {
                java.util.zip.GZIPInputStream(raw.inputStream()).use { readLimited(it, maxBytes) }
            } else raw
            if (code !in 200..299) {
                val snippet = String(bytes, Charsets.UTF_8).take(200)
                throw HttpException("HTTP $code $snippet")
            }
            if (bytes.isEmpty()) throw HttpException("响应为空")
            return Response(code, bytes, conn.contentType)
        } catch (e: HttpException) {
            throw e
        } catch (e: Exception) {
            throw HttpException("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    fun getText(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = DEFAULT_TIMEOUT_MS): String =
        execute("GET", url, headers, timeoutMs = timeoutMs).text()

    private fun readLimited(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(minOf(8192, maxBytes))
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw HttpException("响应超过 ${maxBytes / 1024}KB 上限")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
