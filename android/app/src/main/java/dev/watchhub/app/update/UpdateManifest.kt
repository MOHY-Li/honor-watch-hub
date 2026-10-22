package dev.watchhub.app.update

import org.json.JSONObject

/**
 * 更新清单。由 CI 在每次发布时生成，托管在仓库里（raw 链接）。
 *
 * 格式：
 * {
 *   "versionCode": 20000,
 *   "versionName": "2.0.0",
 *   "apkUrl": "https://github.com/OWNER/REPO/releases/download/v2.0.0/WatchHub.apk",
 *   "sha256": "<64 位十六进制>",
 *   "sizeBytes": 5900000,
 *   "changelog": "• 新增 xxx",
 *   "minSdk": 24,
 *   "publishedAt": "2026-02-11T10:00:00Z"
 * }
 */
data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val changelog: String,
    val minSdk: Int,
    val publishedAt: String,
) {
    companion object {
        /** 解析失败返回 null（网络给了 404 页面 / HTML 时不至于崩）。 */
        fun parse(json: String): UpdateManifest? = try {
            val o = JSONObject(json)
            val code = o.optLong("versionCode", -1)
            val url = o.optString("apkUrl").trim()
            val sha = o.optString("sha256").trim().lowercase()
            // sha 允许为空（GitHub API 线路可能拿不到 digest），但非空时必须是 64 位十六进制
            if (code <= 0 || url.isBlank() || (sha.isNotEmpty() && sha.length != 64)) null
            else UpdateManifest(
                versionCode = code,
                versionName = o.optString("versionName", "?"),
                apkUrl = url,
                sha256 = sha,
                sizeBytes = o.optLong("sizeBytes", 0),
                changelog = o.optString("changelog", ""),
                minSdk = o.optInt("minSdk", 24),
                publishedAt = o.optString("publishedAt", ""),
            )
        } catch (_: Throwable) {
            null
        }
    }
}

/** 一次「有没有新版本」的判断结果。 */
sealed class UpdateCheck {
    data class UpToDate(val current: Long) : UpdateCheck()
    data class Available(val manifest: UpdateManifest) : UpdateCheck()
    data class Unsupported(val manifest: UpdateManifest) : UpdateCheck()
    data class Failed(val reason: String) : UpdateCheck()
}
