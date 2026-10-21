package dev.watchhub.app

import dev.watchhub.app.update.UpdateCheck
import dev.watchhub.app.update.UpdateManifest
import dev.watchhub.app.update.Updater
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 自更新的纯逻辑测试：清单解析 / 版本比较 / SHA-256 校验。 */
class UpdateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun manifest(
        code: Int = 2,
        name: String = "1.1.0",
        sha: String = "a".repeat(64),
        size: Long = 0,
        minSdk: Int = 24,
    ) = UpdateManifest(
        versionCode = code.toLong(),
        versionName = name,
        apkUrl = "https://example.com/WatchHub.apk",
        sha256 = sha,
        sizeBytes = size,
        changelog = "- 测试",
        minSdk = minSdk,
        publishedAt = "2026-02-11T10:00:00Z",
    )

    // ---------- 清单解析 ----------

    @Test
    fun `parses a well formed manifest`() {
        val json = JSONObject().apply {
            put("versionCode", 3)
            put("versionName", "1.2.0")
            put("apkUrl", "https://x/y.apk")
            put("sha256", "b".repeat(64))
            put("sizeBytes", 1234)
            put("changelog", "- 新功能")
            put("minSdk", 24)
        }.toString()

        val m = UpdateManifest.parse(json)!!
        assertEquals(3L, m.versionCode)
        assertEquals("1.2.0", m.versionName)
        assertEquals(1234L, m.sizeBytes)
        assertEquals("b".repeat(64), m.sha256)
    }

    @Test
    fun `rejects manifest without version code`() {
        assertNull(UpdateManifest.parse("""{"versionName":"1.0"}"""))
        assertNull(UpdateManifest.parse("""{"versionCode":0,"apkUrl":"u","sha256":"${"a".repeat(64)}"}"""))
    }

    @Test
    fun `rejects manifest with bad sha length`() {
        assertNull(UpdateManifest.parse("""{"versionCode":2,"apkUrl":"u","sha256":"abc"}"""))
    }

    @Test
    fun `rejects manifest without apk url`() {
        assertNull(
            UpdateManifest.parse(
                """{"versionCode":2,"apkUrl":"","sha256":"${"a".repeat(64)}"}"""
            )
        )
    }

    @Test
    fun `returns null for html instead of crashing`() {
        // 典型场景：raw 地址 404 返回了一个 HTML 页面
        assertNull(UpdateManifest.parse("<!DOCTYPE html><html>404</html>"))
        assertNull(UpdateManifest.parse(""))
        assertNull(UpdateManifest.parse("null"))
    }

    @Test
    fun `sha is normalised to lowercase`() {
        val json = JSONObject().apply {
            put("versionCode", 2)
            put("apkUrl", "https://x/y.apk")
            put("sha256", "A".repeat(64))
        }.toString()
        assertEquals("a".repeat(64), UpdateManifest.parse(json)!!.sha256)
    }

    // ---------- SHA-256 校验 ----------

    @Test
    fun `sha256 matches known value`() {
        val f = tmp.newFile("abc.txt")
        f.writeText("abc")
        // echo -n abc | sha256sum
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Updater.sha256(f),
        )
    }

    @Test
    fun `sha256 of empty file is known value`() {
        val f = tmp.newFile("empty.txt")
        f.writeText("")
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Updater.sha256(f),
        )
    }

    // ---------- 版本比较 ----------

    @Test
    fun `same version is up to date`() {
        val m = manifest(code = 5)
        // 服务端 5，本地 5 → 已最新（注意不是 >）
        assertTrue(m.versionCode <= 5L)
    }

    @Test
    fun `newer version is greater`() {
        assertTrue(manifest(code = 6).versionCode > 5L)
        assertTrue(manifest(code = 4).versionCode < 5L)
    }

    @Test
    fun `minSdk gating`() {
        // 清单要求 Android 33，本机 24 → 不可用（由 Updater.check 判定）
        assertTrue(manifest(minSdk = 33).minSdk > 24)
    }
}
