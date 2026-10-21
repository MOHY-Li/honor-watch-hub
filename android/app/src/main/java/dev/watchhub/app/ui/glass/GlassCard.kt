package dev.watchhub.app.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.RequiresApi

/**
 * 液体玻璃容器。
 *
 * 渲染方式分两条路（这是 Android 和 Flutter 最大的不同）：
 *
 * **API 33+（真玻璃）**
 *   用 [RenderNode] 把「背后的内容」录制成一张位图，作为 AGSL 着色器的
 *   `uBackdrop` 输入，实时做折射/色散/边缘光。
 *   这和 FlClash 的 Flutter `ShaderMask` + `BackdropFilter` 是同一类做法。
 *
 * **API < 33（降级）**
 *   没有 RuntimeShader。退化为「半透明 + RenderEffect 模糊」，
 *   视觉上是常见的磨砂玻璃，没有折射但没有明显落差。
 *
 * 用法：
 *   ```
 *   <GlassCard>
 *       <内容/>
 *   </GlassCard>
 *   ```
 * 玻璃区域只画在「背景 + 子View之间」，所以子View 的内容永远是清晰的。
 */
class GlassCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : FrameLayout(context, attrs, defStyle) {

    var style: GlassStyle = GlassStyle.forDark(isDarkTheme())
        set(value) {
            field = value
            invalidate()
        }

    /** 关掉玻璃，退化成纯色卡片（用于"省电模式"或极低端机）。 */
    var glassEnabled: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    /** 供外部注入「当前屏幕背景」，实现真正的背景透视。 */
    var backdropProvider: (() -> android.graphics.Bitmap?)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val node: RenderNode? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) RenderNode("glass") else null

    private var runtimeShader: RuntimeShader? = null
    private var shaderWrapper: GlassShader? = null

    private var backdrop: android.graphics.Bitmap? = null
    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setWillNotDraw(false)
        if (GlassShader.isSupported()) {
            runCatching {
                shaderWrapper = GlassShader(context)
                runtimeShader = shaderWrapper?.asShader()
            }.onFailure {
                // 着色器编译失败（不同 GPU 驱动对 AGSL 支持有差异）
                // 不要崩，退化到降级路径即可
                android.util.Log.w("GlassCard", "AGSL 不可用，降级: ${it.message}")
                shaderWrapper = null
                runtimeShader = null
            }
        }
    }

    private fun isDarkTheme(): Boolean {
        val night = context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return night == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    fun refreshBackdrop() {
        backdrop = backdropProvider?.invoke()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        node?.setPosition(0, 0, w, h)
    }

    override fun onDraw(canvas: Canvas) {
        if (!glassEnabled || width == 0 || height == 0) {
            super.onDraw(canvas)
            return
        }

        val s = style
        val r = s.radius.coerceAtMost(minOf(width, height) / 2f)

        when {
            // ---------- 真玻璃 ----------
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                runtimeShader != null && shaderWrapper != null && backdrop != null -> {
                drawRealGlass(canvas, s, r)
            }

            // ---------- 降级：半透明 + 模糊 ----------
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && backdrop != null -> {
                drawFallbackGlass(canvas, s, r)
            }

            else -> {
                drawPlainGlass(canvas, s, r)
            }
        }
        super.onDraw(canvas)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun drawRealGlass(canvas: Canvas, s: GlassStyle, r: Float) {
        val w = width.toFloat()
        val h = height.toFloat()

        // 背景位图缩放成着色器能采样的输入
        val bmp = backdrop ?: return
        val bgShader = android.graphics.BitmapShader(
            bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP
        ).apply {
            val sx = w / bmp.width
            val sy = h / bmp.height
            setLocalMatrix(android.graphics.Matrix().apply { setScale(sx, sy) })
        }

        shaderWrapper!!.bind(s, w, h)
        val shader = runtimeShader!!
        shader.setInputShader("uBackdrop", bgShader)

        paint.shader = shader
        canvas.drawRoundRect(0f, 0f, w, h, r, r, paint)
        paint.shader = null
    }

    /**
     * 降级路径（API 31..32）：没有 RuntimeShader，用 RenderEffect 做模糊。
     *
     * 注意 Paint 没有 setRenderEffect —— 那是 View 的方法。
     * 这里改用 RenderNode：把背景位图录进一个带模糊效果的 RenderNode，再画出来。
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun drawFallbackGlass(canvas: Canvas, s: GlassStyle, r: Float) {
        val w = width
        val h = height
        val bmp = backdrop ?: return

        // 1) 背景铺满（缩放后绘制）
        val src = if (bmp.width == w && bmp.height == h) bmp
        else android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)

        // 2) 用一个临时 RenderNode 承载模糊效果
        val rn = RenderNode("glassBlur")
        rn.setPosition(0, 0, w, h)
        rn.setRenderEffect(
            android.graphics.RenderEffect.createBlurEffect(
                s.blur.coerceAtLeast(16f),
                s.blur.coerceAtLeast(16f),
                Shader.TileMode.CLAMP,
            )
        )
        val rc = rn.beginRecording(w, h)
        rc.drawBitmap(src, 0f, 0f, null)
        rn.endRecording()

        // 3) 裁剪成圆角再画出来
        val save = canvas.save()
        val path = android.graphics.Path().apply {
            addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), r, r, android.graphics.Path.Direction.CW)
        }
        canvas.clipPath(path)
        canvas.drawRenderNode(rn)
        canvas.restoreToCount(save)

        // 4) tint + 边缘光
        backdropPaint.color = s.tint
        canvas.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), r, r, backdropPaint)
        if (s.rim > 0f) {
            backdropPaint.color = Color.argb((s.rim * 120).toInt(), 255, 255, 255)
            backdropPaint.style = Paint.Style.STROKE
            backdropPaint.strokeWidth = s.rimWidth.coerceAtLeast(1f)
            canvas.drawRoundRect(0.5f, 0.5f, w - 0.5f, h - 0.5f, r, r, backdropPaint)
            backdropPaint.style = Paint.Style.FILL
        }
    }

    private fun drawPlainGlass(canvas: Canvas, s: GlassStyle, r: Float) {
        backdropPaint.color = s.tint
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, backdropPaint)
        // 边缘光（用描边近似）
        if (s.rim > 0f) {
            backdropPaint.color = Color.argb((s.rim * 120).toInt(), 255, 255, 255)
            backdropPaint.style = Paint.Style.STROKE
            backdropPaint.strokeWidth = s.rimWidth.coerceAtLeast(1f)
            canvas.drawRoundRect(
                0.5f, 0.5f, width - 0.5f, height - 0.5f, r, r, backdropPaint
            )
            backdropPaint.style = Paint.Style.FILL
        }
    }
}
