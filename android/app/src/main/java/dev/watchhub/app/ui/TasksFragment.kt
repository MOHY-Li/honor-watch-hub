package dev.watchhub.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import dev.watchhub.app.R
import dev.watchhub.app.databinding.FragmentTasksBinding
import dev.watchhub.app.ui.adapters.TaskAdapter

/** 任务页：按已过期/今天/之后/最近完成分组。提醒规则管理在独立二级页（ReminderListActivity）。 */
class TasksFragment : Fragment() {

    private var _binding: FragmentTasksBinding? = null
    private val binding get() = _binding!!
    private lateinit var taskAdapter: TaskAdapter
    private val ctx get() = requireContext().applicationContext

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTasksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        taskAdapter = TaskAdapter(
            onDone = { row -> runAndRefresh { HubUi.completeTask(ctx, row.id) } },
            onSkip = { row -> runAndRefresh { HubUi.skipTask(ctx, row.id) } },
            onSnooze = { row -> runAndRefresh { HubUi.snoozeTask(ctx, row.id, 10) } },
        )
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = taskAdapter
        binding.btnNew.setOnClickListener {
            startActivity(Intent(requireContext(), TaskEditActivity::class.java))
        }
        binding.btnManage.setOnClickListener {
            startActivity(Intent(requireContext(), ReminderListActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.list.adapter = null
        _binding = null
    }

    private fun refresh() {
        if (_binding == null) return
        val sections = runCatching { HubUi.taskSections(ctx) }.getOrElse { emptyList() }
        val tasks = ArrayList<TaskAdapter.Row>()
        sections.forEach { section ->
            tasks.add(TaskAdapter.Row.Section(section.title))
            section.rows.forEach { tasks.add(TaskAdapter.Row.Task(it)) }
        }
        binding.empty.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun runAndRefresh(block: () -> Unit) {
        runCatching(block).onFailure {
            toast("操作失败：" + (it.message ?: it.javaClass.simpleName))
        }
        refresh()
    }

    private fun toast(text: String) {
        android.widget.Toast.makeText(requireContext(), text, android.widget.Toast.LENGTH_SHORT).show()
    }
}
