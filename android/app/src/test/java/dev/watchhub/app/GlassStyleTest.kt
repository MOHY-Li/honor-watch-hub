package dev.watchhub.app

import dev.watchhub.app.ui.glass.GlassStyle
import dev.watchhub.app.ui.glass.alpha
import dev.watchhub.app.ui.glass.withAlpha
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 玻璃样式的纯逻辑测试。
 *
 * 折射/色散这些要真机才看得出效果，但「参数怎么算」是纯函数，可以测。
 * 尤其是 scale()：它是玻璃浮现动画的核心，算错了会看到奇怪的闪烁。
 */
class GlassStyleTest {

    // ---------- alpha 工具 ----------

    @Test
    fun `alpha extracts top byte`() {
        assertEquals(0xFF, 0xFF123456.toInt().alpha())
        assertEquals(0x00, 0x00123456.alpha())
        assertEquals(0x8A, 0x8AFDFDFD.toInt().alpha())
    }

    @Test
    fun `withAlpha replaces only alpha`() {
        val c = 0x8AFDFDFD.toInt()
        val r = c.withAlpha(0xFF)
        assertEquals(0xFF, r.alpha())
        // RGB 必须原样保留
        assertEquals(0xFDFDFD, r and 0x00FFFFFF)
    }

    @Test
    fun `withAlpha clamps out of range`() {
        assertEquals(0xFF, 0x00123456.withAlpha(999).alpha())
        assertEquals(0x00, 0x00123456.withAlpha(-5).alpha())
    }

    // ---------- scale（浮现动画） ----------

    @Test
    fun `scale zero collapses glass to nothing but keeps geometry`() {
        val s = GlassStyle.Light.scale(0f)
        assertEquals(0f, s.refraction, 0.001f)
        assertEquals(0f, s.rim, 0.001f)
        assertEquals(0f, s.dispersion, 0.001f)
        assertEquals(0, s.tint.alpha())
        // 几何量不缩放：玻璃浮现时厚度变化，但形状不该跳
        assertEquals(GlassStyle.Light.bevel, s.bevel, 0.001f)
        assertEquals(GlassStyle.Light.rimWidth, s.rimWidth, 0.001f)
        assertEquals(GlassStyle.Light.radius, s.radius, 0.001f)
    }

    @Test
    fun `scale one equals original`() {
        val s = GlassStyle.Light
        val full = s.scale(1f)
        assertEquals(s.refraction, full.refraction, 0.001f)
        assertEquals(s.rim, full.rim, 0.001f)
        assertEquals(s.saturation, full.saturation, 0.001f)
        assertEquals(s.tint.alpha(), full.tint.alpha())
    }

    @Test
    fun `scale clamps input`() {
        val below = GlassStyle.Light.scale(-3f)
        assertEquals(0f, below.refraction, 0.001f)
        val above = GlassStyle.Light.scale(9f)
        assertEquals(GlassStyle.Light.refraction, above.refraction, 0.001f)
    }

    @Test
    fun `scale is monotonic in saturation`() {
        // 动画中间值必须落在两端之间，否则会看到跳变
        val a = GlassStyle.Light.scale(0.25f).saturation
        val b = GlassStyle.Light.scale(0.75f).saturation
        assertTrue("saturation 应随 t 单调增: $a -> $b", a < b)
    }

    @Test
    fun `scale keeps brightness converging to original`() {
        // brightness 以 1 为基准，t 从 0 到 1 应从 1 收敛回原值
        val s = GlassStyle.Dark
        assertEquals(1f, s.scale(0f).brightness, 0.001f)
        assertEquals(s.brightness, s.scale(1f).brightness, 0.001f)
    }

    // ---------- 预设合理性 ----------

    @Test
    fun `light and dark presets differ in saturation`() {
        assertTrue(GlassStyle.Light.saturation != GlassStyle.Dark.saturation)
        assertTrue(GlassStyle.forDark(false) == GlassStyle.Light)
        assertTrue(GlassStyle.forDark(true) == GlassStyle.Dark)
    }

    @Test
    fun `pill presets have full radius`() {
        assertTrue("胶囊必须整圆角", GlassStyle.PillLight.radius >= 999f)
        assertTrue(GlassStyle.pillForDark(true) == GlassStyle.PillDark)
    }

    @Test
    fun `fallback preset disables expensive effects`() {
        val f = GlassStyle.Fallback
        assertEquals(0f, f.refraction, 0.001f)
        assertEquals(0f, f.bevel, 0.001f)
        assertEquals(0f, f.dispersion, 0.001f)
        assertTrue("降级仍需可读的背景", f.tint.alpha() > 200)
    }

    @Test
    fun `defaults are sane`() {
        val s = GlassStyle()
        assertTrue(s.radius > 0f)
        assertTrue(s.saturation > 0f)
        assertTrue(s.brightness > 0f)
        assertTrue("rim 应在 0..1", s.rim in 0f..1f)
        assertTrue("hairline 应在 0..1", s.hairline in 0f..1f)
    }
}
