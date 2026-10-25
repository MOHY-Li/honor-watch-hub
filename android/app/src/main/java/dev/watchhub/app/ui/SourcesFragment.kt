package dev.watchhub.app.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.watchhub.app.R
import dev.watchhub.app.databinding.FragmentSourcesBinding
import dev.watchhub.app.source.SourceKind
import dev.watchhub.app.ui.adapters.SourceAdapter

/** 源管理页：只管高级监控源（JSON/正则/指纹）的列表、启停、立即检查、编辑、新增。RSS 在订阅页管理。 */
class SourcesFragment : Fragment() {

    private var _binding: FragmentSourcesBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: SourceAdapter
    private val handler = Handler(Looper.getMainLooper())
    private val ctx get() = requireContext().applicationContext

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSourcesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = SourceAdapter(
            onEnabled = { row, enabled ->
                runCatching { HubUi.setSourceEnabled(ctx, row.name, enabled) }
                    .onFailure { toast("操作失败：" + it.message) }
                refresh()
            },
            onCheck = { row -> checkNow(row.name) },
            onEdit = { row ->
                startActivity(
                    Intent(requireContext(), SourceEditActivity::class.java)
                        .putExtra(SourceEditActivity.EXTRA_NAME, row.name),
                )
            },
            onDelete = { row -> confirmDelete(row.name) },
        )
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        binding.btnAdd.setOnClickListener { chooseKind() }
        binding.btnAllJson.setOnClickListener {
            startActivity(
                Intent(requireContext(), SourceEditActivity::class.java)
                    .putExtra(SourceEditActivity.EXTRA_EDIT_ALL, true),
            )
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
        val rows = runCatching { HubUi.advancedSourceRows(ctx) }.getOrElse { emptyList() }
        adapter.submitList(rows)
        binding.empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    /** RSS 不在这里新增（订阅页「添加订阅」才是 RSS 入口），只提供三种高级源。 */
    private fun chooseKind() {
        val kinds = listOf(SourceKind.JSON, SourceKind.REGEX, SourceKind.HASH)
        val labels = kinds.map { kindLabel(it) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.source_edit_new)
            .setItems(labels) { _, which ->
                startActivity(
                    Intent(requireContext(), SourceEditActivity::class.java)
                        .putExtra(SourceEditActivity.EXTRA_KIND, kinds[which].wire),
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun kindLabel(kind: SourceKind): String = when (kind) {
        SourceKind.RSS -> getString(R.string.source_kind_rss)
        SourceKind.JSON -> getString(R.string.source_kind_json)
        SourceKind.REGEX -> getString(R.string.source_kind_regex)
        SourceKind.HASH -> getString(R.string.source_kind_hash)
    }

    private fun checkNow(name: String) {
        toast(getString(R.string.source_checking, name))
        val appCtx = requireContext().applicationContext
        Thread({
            val result = runCatching { HubUi.checkSourceNow(appCtx, name) }
            handler.post {
                if (!isAdded) return@post
                result.onSuccess { count ->
                    toast(getString(R.string.source_check_result, name, count))
                }.onFailure {
                    toast("检查失败：" + (it.message ?: it.javaClass.simpleName))
                }
                refresh()
            }
        }, "watchhub-source-check").start()
    }

    private fun confirmDelete(name: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.source_delete_confirm, name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                runCatching { HubUi.deleteSource(ctx, name) }
                    .onFailure { toast("删除失败：" + it.message) }
                refresh()
            }
            .show()
    }

    private fun toast(text: String) {
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    }
}
