package dev.watchhub.app.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.watchhub.app.R
import dev.watchhub.app.core.model.Priority
import dev.watchhub.app.core.model.Recurrence
import dev.watchhub.app.core.model.Reminder
import dev.watchhub.app.databinding.ActivityTaskEditBinding
import java.time.DayOfWeek

/** 新建/编辑重复提醒。保存前做基础校验，重名由 HubUi 返回失败。 */
class TaskEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "reminder_id"
    }

    private lateinit var binding: ActivityTaskEditBinding
    private var reminderId: Long = 0
    private val dayChips = LinkedHashMap<DayOfWeek, Chip>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTaskEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        reminderId = intent.getLongExtra(EXTRA_ID, 0L)
        binding.toolbar.setNavigationOnClickListener { finish() }
        setupDays()

        if (reminderId > 0) {
            val reminder = runCatching { HubUi.reminderById(this, reminderId) }.getOrNull()
            if (reminder == null) {
                Toast.makeText(this, "提示不存在或已删除", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            bind(reminder)
        } else {
            binding.toolbar.title = getString(R.string.task_edit_new)
            binding.rbNormal.isChecked = true
            binding.switchEnabled.isChecked = true
            binding.btnDelete.visibility = android.view.View.GONE
        }

        binding.btnSave.setOnClickListener { save() }
        binding.btnDelete.setOnClickListener { confirmDelete() }
    }

    private fun setupDays() {
        val labels = listOf(
            R.string.day_mon to DayOfWeek.MONDAY,
            R.string.day_tue to DayOfWeek.TUESDAY,
            R.string.day_wed to DayOfWeek.WEDNESDAY,
            R.string.day_thu to DayOfWeek.THURSDAY,
            R.string.day_fri to DayOfWeek.FRIDAY,
            R.string.day_sat to DayOfWeek.SATURDAY,
            R.string.day_sun to DayOfWeek.SUNDAY,
        )
        labels.forEach { (labelRes, day) ->
            val chip = Chip(this).apply {
                text = getString(labelRes)
                isCheckable = true
                isCheckedIconVisible = true
                isClickable = true
                chipMinHeight = resources.displayMetrics.density * 32
            }
            binding.chipDays.addView(chip)
            dayChips[day] = chip
        }
    }

    private fun bind(reminder: Reminder) {
        binding.toolbar.title = getString(R.string.task_edit_edit)
        binding.etName.setText(reminder.name)
        binding.etName.isEnabled = true
        binding.etTitle.setText(reminder.title)
        binding.etBody.setText(reminder.body)
        binding.etTimes.setText(
            reminder.recurrence.times.sorted().joinToString(", ") { "%02d:%02d".format(it.hour, it.minute) }
        )
        reminder.recurrence.days.forEach { day -> dayChips[day]?.isChecked = true }
        when (reminder.priority) {
            Priority.HIGH -> binding.rbHigh.isChecked = true
            Priority.NORMAL -> binding.rbNormal.isChecked = true
            Priority.LOW -> binding.rbLow.isChecked = true
        }
        binding.switchEnabled.isChecked = reminder.enabled
        binding.switchCalendar.isChecked = reminder.calendarSync
        binding.btnDelete.visibility = android.view.View.VISIBLE
    }

    private fun save() {
        val name = binding.etName.text?.toString()?.trim().orEmpty()
        val title = binding.etTitle.text?.toString()?.trim().orEmpty()
        val body = binding.etBody.text?.toString()?.trim().orEmpty()
        val timesRaw = binding.etTimes.text?.toString().orEmpty()

        if (name.isBlank()) {
            binding.layoutName.error = getString(R.string.task_edit_name_required)
            return
        }
        binding.layoutName.error = null
        if (title.isBlank()) {
            binding.layoutTitle.error = getString(R.string.task_edit_title_required)
            return
        }
        binding.layoutTitle.error = null

        val times = timesRaw.split(',', '，', ' ', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val recurrence = Recurrence.parse(times, emptyList())
        if (recurrence.times.isEmpty()) {
            binding.layoutTimes.error = getString(R.string.task_edit_time_required)
            return
        }
        binding.layoutTimes.error = null

        val days = dayChips.filterValues { it.isChecked }.keys
        val fullRecurrence = Recurrence(
            times = recurrence.times.sorted(),
            days = days.toSet(),
        )

        val priority = when {
            binding.rbHigh.isChecked -> Priority.HIGH
            binding.rbLow.isChecked -> Priority.LOW
            else -> Priority.NORMAL
        }

        val reminder = Reminder(
            name = name,
            title = title,
            id = reminderId,
            body = body,
            priority = priority,
            recurrence = fullRecurrence,
            enabled = binding.switchEnabled.isChecked,
            calendarSync = binding.switchCalendar.isChecked,
        )

        HubUi.saveReminder(this, reminder)
            .onSuccess {
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                finish()
            }
            .onFailure { error ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("保存失败")
                    .setMessage(error.message ?: "未知错误")
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
    }

    private fun confirmDelete() {
        if (reminderId <= 0) return
        MaterialAlertDialogBuilder(this)
            .setTitle("删除这条提醒？")
            .setMessage("删除后不再生成新任务，历史任务保留。")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                runCatching { HubUi.deleteReminder(this, reminderId) }
                    .onSuccess { finish() }
                    .onFailure {
                        Toast.makeText(this, "删除失败：${it.message}", Toast.LENGTH_SHORT).show()
                    }
            }
            .show()
    }
}
