package dev.watchhub.app.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.color.MaterialColors
import dev.watchhub.app.R
import dev.watchhub.app.databinding.FragmentHomeBinding
import dev.watchhub.app.health.HealthLevel

/** 首页：中枢开关、今日进度、下一次提醒、体检摘要、数据源状态列表。 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val handler = Handler(Looper.getMainLooper())
    private var updating = false
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.hubSwitch.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            val ctx = requireContext().applicationContext
            runCatching {
                if (checked) HubUi.start(ctx) else HubUi.stop(ctx)
            }.onFailure {
                toast("操作失败：" + (it.message ?: it.javaClass.simpleName))
            }
            refresh()
        }
        binding.healthCard.setOnClickListener {
            startActivity(Intent(requireContext(), HealthActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        restartTicker()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            handler.removeCallbacks(ticker)
        } else {
            refresh()
            restartTicker()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(ticker)
        _binding = null
    }

    private fun restartTicker() {
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, REFRESH_MS)
    }

    private fun refresh() {
        val ctx = requireContext().applicationContext
        val home = runCatching { HubUi.home(ctx) }.getOrNull() ?: return
        val b = _binding ?: return
        updating = true

        b.hubSwitch.isChecked = home.wantRun
        b.hubDesc.text = if (home.wantRun && home.running) {
            getString(R.string.home_hub_running)
        } else if (home.wantRun) {
            "中枢已开启，但服务心跳未更新（可能被系统杀了），点开关重启"
        } else {
            getString(R.string.home_hub_stopped)
        }

        val total = home.todayOpen + home.todayDone
        b.todayProgress.progress = if (total > 0) home.todayDone * 100 / total else 0
        b.todayText.text = if (total > 0) {
            "已完成 ${home.todayDone} / ${total}，还剩 ${home.todayOpen} 条待办"
        } else {
            getString(R.string.home_progress_none)
        }

        b.nextTitle.text = home.nextTitle ?: getString(R.string.home_next_none)
        b.nextTime.text = home.nextTimeText.orEmpty()

        val worst = home.healthWorst
        val fails = home.health.count { it.level == HealthLevel.FAIL }
        val warns = home.health.count { it.level == HealthLevel.WARN }
        b.healthSummary.text = when (worst) {
            HealthLevel.FAIL -> "异常：$fails 项需要处理"
            HealthLevel.WARN -> "需注意：$warns 项建议检查"
            HealthLevel.OK -> "链路正常"
            HealthLevel.INFO -> "暂无可判断项"
        }
        b.healthSummary.setTextColor(colorForLevel(worst))

        renderSourceStates(home.sourceStates)

        updating = false
    }

    /** 数据源状态列表（后端已按异常置顶排序，这里直接渲染）。 */
    private fun renderSourceStates(states: List<HubUi.SourceStatusRow>) {
        val b = _binding ?: return
        val ctx = requireContext()
        b.sourcesContainer.removeAllViews()

        if (states.isEmpty()) {
            b.sourcesContainer.addView(TextView(ctx).apply {
                text = getString(R.string.home_sources_empty)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(
                    MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant),
                )
                setPadding(0, dp(8), 0, 0)
            })
            return
        }

        states.forEach { row ->
            val color = sourceLevelColor(row.level, b.sourcesContainer)
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(5))
            }
            line.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(10) }
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
                setTextColor(
                    MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant),
                )
                setPadding(dp(8), 0, 0, 0)
            })
            b.sourcesContainer.addView(line)
        }
    }

    private fun colorForLevel(level: HealthLevel): Int = when (level) {
        HealthLevel.OK -> requireContext().getColor(R.color.health_ok)
        HealthLevel.WARN -> requireContext().getColor(R.color.health_warn)
        HealthLevel.FAIL -> requireContext().getColor(R.color.health_fail)
        HealthLevel.INFO -> requireContext().getColor(R.color.health_info)
    }

    /** 数据源状态色：INFO 用灰色（主题 onSurfaceVariant），其余用健康色。 */
    private fun sourceLevelColor(level: HealthLevel, anchor: View): Int = when (level) {
        HealthLevel.OK -> requireContext().getColor(R.color.health_ok)
        HealthLevel.WARN -> requireContext().getColor(R.color.health_warn)
        HealthLevel.FAIL -> requireContext().getColor(R.color.health_fail)
        HealthLevel.INFO -> MaterialColors.getColor(
            anchor, com.google.android.material.R.attr.colorOnSurfaceVariant,
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(text: String) {
        android.widget.Toast.makeText(requireContext(), text, android.widget.Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REFRESH_MS = 15_000L
    }
}
