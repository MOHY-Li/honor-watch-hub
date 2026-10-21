package dev.watchhub.app.ui.glass

import androidx.annotation.ColorInt

/**
 * 玻璃样式参数。对标 FlClash 的 `LiquidGlassStyle`。
 *
 * 这里刻意做成纯数据类（不碰 Android API）：
 * 预设和 lerp/scale 这些逻辑可以在 JVM 单测里验证，
 * 只有真正喂给 RuntimeShader 的部分才需要设备。
 */
data class GlassStyle(
    /** 圆角半径 (dp) */
    val radius: Float = 22f,
    /** 折射强度 (dp)：边缘把背景「吸」向中心的距离 */
    val refraction: Float = 60f,
    /** 斜切深度 (dp)：折射影响的边缘带宽度 */
    val bevel: Float = 20f,
    /** 边缘光强度 0..1 */
    val rim: Float = 0.28f,
    /** 边缘光宽度 (dp) */
    val rimWidth: Float = 1.2f,
    /** 饱和度，1 = 原样 */
    val saturation: Float = 2.08f,
    /** 亮度，1 = 原样 */
    val brightness: Float = 1f,
    /** 色散强度：RGB 分离程度 */
    val dispersion: Float = 0f,
    /** 模糊半径 (dp)，0 = 不模糊（靠折射本身产生质感） */
    val blur: Float = 0f,
    /** 薄雾色，alpha = 浓度 */
    @ColorInt val tint: Int = 0x8AFDFDFD.toInt(),
    /** 发丝线强度 0..1 */
    val hairline: Float = 0.25f,
) {

    /**
     * 按比例淡入。
     * FlClash 的 `scale(t)` 用在这里：玻璃「浮现」时是从弯曲开始而不是从透明开始，
     * 所以 refraction/bevel/rim 一起缩放，视觉上才像玻璃变厚。
     */
    fun scale(t: Float): GlassStyle {
        val a = t.coerceIn(0f, 1f)
        return copy(
            tint = tint.withAlpha((tint.alpha() * a).toInt()),
            refraction = refraction * a,
            rim = rim * a,
            rimWidth = rimWidth,
            bevel = bevel,
            saturation = 1f + (saturation - 1f) * a,
            brightness = 1f + (brightness - 1f) * a,
            dispersion = dispersion * a,
            blur = blur * a,
            hairline = hairline * a,
        )
    }

    companion object {
        /** 浅色主题：偏白、高饱和、细边缘光（照搬 FlClash 的 regular 浅色预设） */
        val Light = GlassStyle(
            tint = 0x8AFDFDFD.toInt(),
            saturation = 2.08f,
            rim = 0.28f,
            rimWidth = 1.2f,
            hairline = 0.25f,
        )

        /** 深色主题：偏暗、低对比、更弱的边缘光 */
        val Dark = GlassStyle(
            tint = 0x9E3A3A3A.toInt(),
            saturation = 2.84f,
            rim = 0.28f,
            rimWidth = 1.2f,
            hairline = 0f,
        )

        /** 悬浮胶囊条（导航栏）：不靠模糊，靠折射和薄雾 */
        val PillLight = GlassStyle(
            radius = 999f,
            tint = 0x93F4F4F4.toInt(),
            saturation = 2.1f,
            rim = 0f,
            rimWidth = 0f,
            blur = 0f,
            hairline = 0.35f,
        )

        val PillDark = GlassStyle(
            radius = 999f,
            tint = 0x7A303030.toInt(),
            saturation = 1.925f,
            rim = 0.35f,
            rimWidth = 1f,
        )

        /** 低端设备 / API < 33 的降级样式：只有半透明底，不折射 */
        val Fallback = GlassStyle(
            tint = 0xE6F4F4F4.toInt(),
            refraction = 0f,
            bevel = 0f,
            rim = 0f,
            rimWidth = 0f,
            dispersion = 0f,
            blur = 0f,
            hairline = 0.2f,
        )

        fun forDark(dark: Boolean): GlassStyle = if (dark) Dark else Light
        fun pillForDark(dark: Boolean): GlassStyle = if (dark) PillDark else PillLight
    }
}

/** ARGB int 的 alpha 分量 0..255。 */
internal fun Int.alpha(): Int = (this ushr 24) and 0xFF

/** 替换 ARGB int 的 alpha，其余不变，并夹到 0..255。 */
internal fun Int.withAlpha(newAlpha: Int): Int =
    (this and 0x00FFFFFF) or (newAlpha.coerceIn(0, 255) shl 24)
