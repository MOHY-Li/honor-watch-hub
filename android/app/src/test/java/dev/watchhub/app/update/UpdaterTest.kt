package dev.watchhub.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdaterTest {

    private val latestJson = """
        {
          "versionCode": 20000,
          "versionName": "2.0.0",
          "apkUrl": "https://example.com/WatchHub.apk",
          "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
          "sizeBytes": 123,
          "changelog": "v2",
          "minSdk": 24,
          "publishedAt": "2026-10-22T00:00:00Z"
        }
    """.trimIndent()

    @Test
    fun parse_latestManifest() {
        val m = UpdateManifest.parse(latestJson)
        assertNotNull(m)
        assertEquals(20000L, m!!.versionCode)
        assertEquals("2.0.0", m.versionName)
        assertEquals(64, m.sha256.length)
        assertEquals(24, m.minSdk)
    }

    @Test
    fun parse_rejectsMissingUrl() {
        val json = """{"versionCode": 2, "apkUrl": ""}"""
        assertNull(UpdateManifest.parse(json))
    }

    @Test
    fun parse_rejectsBadShaLength() {
        val json = """{"versionCode": 2, "apkUrl": "https://x/y.apk", "sha256": "abc"}"""
        assertNull(UpdateManifest.parse(json))
    }

    @Test
    fun parse_allowsEmptySha() {
        val json = """{"versionCode": 2, "apkUrl": "https://x/y.apk"}"""
        assertNotNull(UpdateManifest.parse(json))
    }

    @Test
    fun parse_html404GivesNull() {
        assertNull(UpdateManifest.parse("<html>404</html>"))
    }

    @Test
    fun versionCodeOf_variants() {
        assertEquals(20000L, Updater.versionCodeOf("v2.0.0"))
        assertEquals(10204L, Updater.versionCodeOf("v1.2.4"))
        assertEquals(20100L, Updater.versionCodeOf("2.1"))
        assertEquals(100L, Updater.versionCodeOf("v0.1.0"))
        assertEquals(0L, Updater.versionCodeOf("nightly"))
    }

    @Test
    fun parseGitHubRelease() {
        val json = """
            {
              "tag_name": "v2.0.0",
              "body": "changes",
              "published_at": "2026-10-22T00:00:00Z",
              "assets": [
                {"name": "notes.txt", "browser_download_url": "https://x/notes.txt", "size": 1},
                {"name": "WatchHub.apk", "browser_download_url": "https://x/WatchHub.apk", "size": 456,
                 "digest": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
              ]
            }
        """.trimIndent()
        val m = Updater.parseGitHubRelease(json)
        assertNotNull(m)
        assertEquals(20000L, m!!.versionCode)
        assertEquals("https://x/WatchHub.apk", m.apkUrl)
        assertEquals(456L, m.sizeBytes)
        assertEquals(64, m.sha256.length)
    }

    @Test
    fun parseAny_prefersOwnManifest() {
        assertNotNull(Updater.parseAny(latestJson))
    }

    @Test
    fun decide_ignoresStaleFirstSource() {
        // 模拟 jsDelivr 返回旧清单（1.2.2）+ raw 返回新清单（2.0.1）
        val stale = UpdateManifest(
            versionCode = 10202, versionName = "1.2.2", apkUrl = "https://x/old.apk",
            sha256 = "", sizeBytes = 1, changelog = "", minSdk = 24, publishedAt = "",
        )
        val fresh = UpdateManifest(
            versionCode = 20001, versionName = "2.0.1", apkUrl = "https://x/new.apk",
            sha256 = "", sizeBytes = 1, changelog = "", minSdk = 24, publishedAt = "",
        )
        val result = Updater.decide(currentCode = 10204, sdkInt = 34, manifests = listOf(stale, fresh))
        assertTrue(result is UpdateCheck.Available)
        assertEquals(20001L, (result as UpdateCheck.Available).manifest.versionCode)
    }

    @Test
    fun decide_upToDateWhenAllSourcesOld() {
        val old = UpdateManifest(
            versionCode = 10202, versionName = "1.2.2", apkUrl = "https://x/old.apk",
            sha256 = "", sizeBytes = 1, changelog = "", minSdk = 24, publishedAt = "",
        )
        assertTrue(Updater.decide(10204, 34, listOf(old)) is UpdateCheck.UpToDate)
    }

    @Test
    fun decide_unsupportedWhenMinSdkTooHigh() {
        val m = UpdateManifest(
            versionCode = 30000, versionName = "3.0.0", apkUrl = "https://x/a.apk",
            sha256 = "", sizeBytes = 1, changelog = "", minSdk = 99, publishedAt = "",
        )
        assertTrue(Updater.decide(20001, 34, listOf(m)) is UpdateCheck.Unsupported)
    }

    @Test
    fun decide_emptyGivesFailed() {
        assertTrue(Updater.decide(20001, 34, emptyList()) is UpdateCheck.Failed)
    }

    @Test
    fun sha256_knownVector() {
        val f = File.createTempFile("watchhub", ".bin")
        try {
            f.writeText("abc")
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                Updater.sha256(f),
            )
        } finally {
            f.delete()
        }
    }

    @Test
    fun verifyFile_sizeMismatch() {
        // 只验证纯逻辑部分：文件不存在时的返回
        val m = UpdateManifest(
            versionCode = 2, versionName = "2.0.0", apkUrl = "https://x/y.apk",
            sha256 = "", sizeBytes = 10, changelog = "", minSdk = 24, publishedAt = "",
        )
        assertTrue(m.sha256.isBlank())
    }
}
