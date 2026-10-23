package dev.watchhub.app.ui

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import dev.watchhub.app.R
import dev.watchhub.app.databinding.ActivityReminderNudgeBinding

/**
 * 提醒确认窗口（手机端）。
 *
 * 手表镜像只能显示标题和正文，没有回执/按钮；所以「已处理」只能在手机上确认。
 * 由通知的全屏意图拉起；权限不可用时系统会退化成 heads-up，用户仍可从通知进入。
 * 直接返回/划掉不算确认，升级链会继续。
 */
class ReminderNudgeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TASK_ID = "task_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        val binding = ActivityReminderNudgeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        val task = runCatching { HubUi.taskById(this, taskId) }.getOrNull()
        if (task == null) {
            finish()
            return
        }

        binding.title.text = task.title
        binding.body.text = task.body.ifBlank { getString(R.string.nudge_no_body) }

        binding.btnComplete.setOnClickListener {
            HubUi.completeTask(this, taskId)
            finish()
        }
        binding.btnSnooze.setOnClickListener {
            HubUi.snoozeTask(this, taskId, 10)
            finish()
        }
        binding.btnSkip.setOnClickListener {
            HubUi.skipTask(this, taskId)
            finish()
        }
    }
}
