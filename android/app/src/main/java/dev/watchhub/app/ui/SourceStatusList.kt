package dev.watchhub.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import dev.watchhub.app.R
import dev.watchhub.app.health.HealthLevel

/**
 * 数据源状态行的渲染（设置页「中枢」组用；2.6.0 前在首页）。
 * 纯视图拼装，无状态——数据组装在 HubUi.home()。
 */
object SourceStatusList {

    fun render(container: LinearLayout, states: List<HubUi.SourceStatusRow>) {
        val ctx: Context = container.context
        container.removeAllViews()

        if (states.isEmpty()) {
            container.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.home_sources_empty)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(0, dp(ctx, 8), 0, 0)
            })
            return
        }

        states.forEach { row ->
            val color = levelColor(ctx, row.level)
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(ctx, 5), 0, dp(ctx, 5))
            }
            line.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 8), dp(ctx, 8)).apply { marginEnd = dp(ctx, 10) }
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_dot)
                backgroundTintList = ColorStateList.valueOf(color)
            })
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(TextView(ctx).apply {
                text = row.name
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            texts.addView(TextView(ctx).apply {
                text = row.statusText
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                setTextColor(color)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            line.addView(texts)
            line.addView(TextView(ctx).apply {
                text = row.kind.uppercase(java.util.Locale.ROOT)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall)
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(dp(ctx, 8), 0, 0, 0)
            })
            container.addView(line)
        }
    }

    /** 状态色：INFO 用主题灰，其余用健康色。 */
    private fun levelColor(ctx: Context, level: HealthLevel): Int = when (level) {
        HealthLevel.OK -> ctx.getColor(R.color.health_ok)
        HealthLevel.WARN -> ctx.getColor(R.color.health_warn)
        HealthLevel.FAIL -> ctx.getColor(R.color.health_fail)
        HealthLevel.INFO -> MaterialColors.getColor(
            TextView(ctx), com.google.android.material.R.attr.colorOnSurfaceVariant,
        )
    }

    private fun dp(ctx: Context, value: Int): Int = (value * ctx.resources.displayMetrics.density).toInt()
}
