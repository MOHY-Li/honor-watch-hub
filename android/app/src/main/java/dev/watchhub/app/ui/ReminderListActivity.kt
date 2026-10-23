package dev.watchhub.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.watchhub.app.R
import dev.watchhub.app.databinding.ActivityReminderListBinding
import dev.watchhub.app.ui.adapters.ReminderAdapter

/**
 * 提醒规则管理（独立二级页）。
 *
 * 从任务页拆出来的原因见 docs/specs/2026-10-23-watchhub-2.2.0.md：
 * 任务和规则混在一屏时，完成/跳过与启停/删除挨在一起，容易误触，且管理入口要滑到底。
 */
class ReminderListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReminderListBinding
    private lateinit var adapter: ReminderAdapter
    private val ctx get() = applicationContext

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReminderListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = ReminderAdapter(
            onEnabled = { row, enabled ->
                runCatching { HubUi.setReminderEnabled(ctx, row.id, enabled) }
                refresh()
            },
            onEdit = { row ->
                startActivity(
                    Intent(this, TaskEditActivity::class.java)
                        .putExtra(TaskEditActivity.EXTRA_ID, row.id),
                )
            },
            onDelete = { row -> confirmDelete(row) },
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.btnNew.setOnClickListener {
            startActivity(Intent(this, TaskEditActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        binding.list.adapter = null
        super.onDestroy()
    }

    private fun refresh() {
        val rows = ArrayList<ReminderAdapter.Row>()
        rows.add(ReminderAdapter.Row.Header)
        val list = runCatching { HubUi.reminderRows(ctx) }.getOrDefault(emptyList())
        if (list.isEmpty()) {
            rows.add(ReminderAdapter.Row.Empty)
        } else {
            list.forEach { rows.add(ReminderAdapter.Row.Item(it)) }
        }
        adapter.submitList(rows)
        binding.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun confirmDelete(row: HubUi.ReminderRow) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.task_delete_confirm, row.name))
            .setMessage(R.string.task_delete_confirm_desc)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                runCatching { HubUi.deleteReminder(ctx, row.id) }
                refresh()
            }
            .show()
    }
}
