package dev.watchhub.app.ui.glass

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import dev.watchhub.app.R

/**
 * 悬浮胶囊切换栏。对标 FlClash 的 lib/ui/navigation_dock.dart。
 *
 * FlClash 那版有极细的物理动画（弹簧/jelly/magnet/parallax/glow）。
 * 这里抓最核心的三点：
 *  1. 浮起来的胶囊 —— 不贴底，悬浮在内容上方
 *  2. 滑块跟随 —— 选中项后面有块会滑过去的高亮，带弹性过冲
 *  3. 按压缩放 —— 按下时图标放大，松开回弹
 *
 * 用纯 Canvas 画而不是子 View 组：保证每次重绘只有一次硬件加速绘制。
 */
class FloatingDock @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    data class Item(val id: Int, val label: String, val iconRes: Int)

    var items: List<Item> = emptyList()
        set(value) {
            field = value
            if (selectedIndex >= value.size) selectedIndex = 0
            requestLayout()
            invalidate()
        }

    var selectedIndex: Int = 0
        set(value) {
            if (items.isEmpty()) return
            val target = value.coerceIn(0, items.size - 1)
            if (target == field) return
            val from = slidPos
            field = target
            animateSlide(from, target.toFloat())
            invalidate()
        }

    var onSelect: ((index: Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val barHeight = dp(62f)
    private val barPadding = dp(6f)
    private val edgeMargin = dp(14f)
    private val iconSize = dp(22f)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lensPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val barRect = RectF()
    private val lensRect = RectF()
    private val clipPath = Path()

    private var slidPos = 0f
    private var pressScale = 0f
    private var pressedIndex = -1

    private var slideAnim: ValueAnimator? = null
    private var pressAnim: ValueAnimator? = null

    private var dark = false
    private var accentColor = 0xFF3D6BE5.toInt()

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        accentColor = runCatching { ContextCompat.getColor(context, R.color.brand) }
            .getOrDefault(0xFF3D6BE5.toInt())
        updateTheme()
    }

    fun updateTheme() {
        val night = context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        dark = night == android.content.res.Configuration.UI_MODE_NIGHT_YES
        barPaint.color = if (dark) 0xE6262626.toInt() else 0xF2FFFFFF.toInt()
        lensPaint.color = if (dark) 0x33FFFFFF else 0x1F000000
        rimPaint.color = if (dark) 0x1AFFFFFF else 0x14000000
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            (barHeight + edgeMargin * 2).toInt(),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        barRect.set(edgeMargin, edgeMargin, w - edgeMargin, edgeMargin + barHeight)
        slidPos = selectedIndex.toFloat()
        invalidate()
    }

    private fun itemWidth(): Float =
        if (items.isEmpty()) 0f else (barRect.width() - barPadding * 2) / items.size

    private fun lensRectFor(pos: Float): RectF {
        val iw = itemWidth()
        val cx = barRect.left + barPadding + iw * (pos + 0.5f)
        val halfW = iw / 2f * (0.82f + 0.08f * pressScale)
        val halfH = (barRect.height() / 2f - barPadding) * (1f + 0.06f * pressScale)
        val cy = barRect.centerY()
        lensRect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        return lensRect
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.isEmpty()) return

        val radius = barRect.height() / 2f
        canvas.drawRoundRect(barRect, radius, radius, barPaint)

        rimPaint.strokeWidth = dp(1f)
        canvas.drawRoundRect(barRect, radius, radius, rimPaint)

        clipPath.reset()
        clipPath.addRoundRect(barRect, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)
        val lr = lensRectFor(slidPos)
        canvas.drawRoundRect(lr, lr.height() / 2f, lr.height() / 2f, lensPaint)
        canvas.restore()

        val iw = itemWidth()
        for ((i, item) in items.withIndex()) {
            val cx = barRect.left + barPadding + iw * (i + 0.5f)
            val selected = i == selectedIndex
            val scale = if (selected) 1f + 0.12f * pressScale else 1f
            val half = iconSize / 2f * scale
            val cy = barRect.centerY()
            ContextCompat.getDrawable(context, item.iconRes)?.let {
                it.setBounds(
                    (cx - half).toInt(), (cy - half).toInt(),
                    (cx + half).toInt(), (cy + half).toInt(),
                )
                it.setTint(if (selected) accentColor else if (dark) 0xFFE8E8E8.toInt() else 0xFF5A5A5A.toInt())
                it.draw(canvas)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (items.isEmpty()) return false
        val iw = itemWidth()
        val idx = ((event.x - barRect.left - barPadding) / iw).toInt()
            .coerceIn(0, items.size - 1)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedIndex = idx
                animatePress(1f)
                return true
            }
            MotionEvent.ACTION_UP -> {
                animatePress(0f)
                if (pressedIndex == idx) {
                    selectedIndex = idx
                    onSelect?.invoke(idx)
                }
                pressedIndex = -1
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                animatePress(0f)
                pressedIndex = -1
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun animateSlide(from: Float, to: Float) {
        slideAnim?.cancel()
        slideAnim = ValueAnimator.ofFloat(from, to).apply {
            duration = 320
            interpolator = OvershootInterpolator(0.9f)
            addUpdateListener {
                slidPos = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun animatePress(target: Float) {
        pressAnim?.cancel()
        pressAnim = ValueAnimator.ofFloat(pressScale, target).apply {
            duration = 160
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                pressScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
}