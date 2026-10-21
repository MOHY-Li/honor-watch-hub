package dev.watchhub.app.ui.glass

import android.content.Context
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import dev.watchhub.app.R

/**
 * 液体玻璃着色器的封装。
 *
 * 对应 FlClash 的 `lib/ui/liquid_glass.dart` + `shaders/liquid_glass.frag`。
 * AGSL 是 Android 版的 SkSL，与 Flutter 用的 GLSL 片段着色器同源，
 * 所以那套折射/边缘光/色散的思路可以近乎原样移植。
 *
 * 只在 API 33+ 可用（RuntimeShader 从 TIRAMISU 开始）。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class GlassShader(context: Context) {

    private val shader: RuntimeShader = RuntimeShader(
        context.resources.openRawResource(R.raw.liquid_glass)
            .bufferedReader().use { it.readText() }
    )

    /**
     * 把样式写进 uniform。
     * uniform 名字必须和 liquid_glass.agsl 里声明的一一对应——
     * 写错会抛 IllegalArgumentException，所以这里全部显式写死，不做字符串拼接。
     */
    fun bind(style: GlassStyle, width: Float, height: Float) {
        shader.setFloatUniform("uSize", width, height)
        shader.setFloatUniform("uRadius", style.radius)
        shader.setFloatUniform("uRefraction", style.refraction)
        shader.setFloatUniform("uBevel", style.bevel)
        shader.setFloatUniform("uRim", style.rim)
        shader.setFloatUniform("uRimWidth", style.rimWidth)
        shader.setFloatUniform("uSaturation", style.saturation)
        shader.setFloatUniform("uBrightness", style.brightness)
        shader.setFloatUniform("uDispersion", style.dispersion)
        shader.setFloatUniform("uBlur", style.blur)
        shader.setFloatUniform(
            "uTint",
            ((style.tint shr 16) and 0xFF) / 255f,
            ((style.tint shr 8) and 0xFF) / 255f,
            (style.tint and 0xFF) / 255f,
            ((style.tint ushr 24) and 0xFF) / 255f,
        )
        shader.setFloatUniform("uHairline", style.hairline)
    }

    fun asShader(): RuntimeShader = shader

    companion object {
        /** 真玻璃需要 API 33+（RuntimeShader）。 */
        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }
}
