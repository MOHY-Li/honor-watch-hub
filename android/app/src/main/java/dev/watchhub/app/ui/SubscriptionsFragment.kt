package dev.watchhub.app.ui

import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dev.watchhub.app.R
import dev.watchhub.app.databinding.FragmentSubscriptionsBinding
import dev.watchhub.app.ui.adapters.ArticleAdapter
import dev.watchhub.app.ui.adapters.SubscriptionAdapter

/**
 * RSS 订阅：添加订阅、暂停/取消、按源筛选、只看未读、阅读文章。
 *
 * 网络操作（addRssSubscription）全部在后台线程，结果回主线程；
 * Fragment 销毁后不再碰 View。
 */
class SubscriptionsFragment : Fragment() {

    private var _binding: FragmentSubscriptionsBinding? = null
    private val binding get() = _binding!!
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var articleAdapter: ArticleAdapter
    private lateinit var subscriptionAdapter: SubscriptionAdapter
    private var subscriptions: List<HubUi.SubscriptionRow> = emptyList()
    private var selectedSource: String? = null
    private var rebuildingChips = false
    private var adding = false
    private val ctx get() = requireContext().applicationContext

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSubscriptionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        articleAdapter = ArticleAdapter { row -> openArticle(row) }
        subscriptionAdapter = SubscriptionAdapter(
            onToggle = { row, enabled ->
                runCatching { HubUi.setSourceEnabled(ctx, row.name, enabled) }
                    .onFailure {
                        toast(getString(R.string.subs_operation_failed, it.message ?: getString(R.string.subs_unknown_error)))
                    }
                refreshAll()
            },
            onDelete = { row -> confirmUnsubscribe(row) },
        )

        binding.subsList.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        binding.subsList.adapter = subscriptionAdapter
        binding.articleList.layoutManager = LinearLayoutManager(requireContext())
        binding.articleList.adapter = articleAdapter

        binding.btnAdd.setOnClickListener { showAddDialog() }
        binding.btnMarkAll.setOnClickListener { markAllRead() }
        binding.btnRefresh.setOnClickListener { refreshSubscriptions() }
        binding.swUnread.setOnCheckedChangeListener { _, _ -> refreshArticles() }
        binding.chipSources.isSelectionRequired = true
        binding.chipSources.setOnCheckedStateChangeListener { group, checkedIds ->
            if (rebuildingChips) return@setOnCheckedStateChangeListener
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val chip = group.findViewById<Chip>(id) ?: return@setOnCheckedStateChangeListener
            selectedSource = chip.tag as? String
            refreshArticles()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refreshAll()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        binding.subsList.adapter = null
        binding.articleList.adapter = null
        _binding = null
    }

    // ---------------- 刷新 ----------------

    private fun refreshAll() {
        val b = _binding ?: return
        val rows = runCatching { HubUi.subscriptions(ctx) }.getOrElse { error ->
            toast(getString(R.string.subs_load_failed, error.message ?: getString(R.string.subs_unknown_error)))
            emptyList()
        }
        if (selectedSource != null && rows.none { it.name == selectedSource }) selectedSource = null
        subscriptions = rows
        subscriptionAdapter.submitList(rows)
        b.subsList.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        b.subsEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        rebuildChips(rows)
        refreshArticles()
        updateBadge()
    }

    private fun refreshArticles() {
        val b = _binding ?: return
        val rows = runCatching { HubUi.articles(ctx, selectedSource, b.swUnread.isChecked) }
            .getOrElse {
                toast(getString(R.string.subs_load_failed, it.message ?: getString(R.string.subs_unknown_error)))
                emptyList()
            }
        articleAdapter.submitList(rows)
        b.articleEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE

        val unread = if (selectedSource == null) {
            runCatching { HubUi.unreadArticleCount(ctx) }.getOrDefault(0)
        } else {
            subscriptions.firstOrNull { it.name == selectedSource }?.unread ?: 0
        }
        b.unreadCount.text = getString(R.string.subs_unread, unread)
        b.unreadCount.visibility = if (unread > 0) View.VISIBLE else View.GONE
    }

    private fun rebuildChips(rows: List<HubUi.SubscriptionRow>) {
        val b = _binding ?: return
        rebuildingChips = true
        b.chipSources.removeAllViews()
        val chips = ArrayList<Pair<Chip, String?>>()

        val all = Chip(requireContext()).apply {
            text = getString(R.string.subs_filter_all)
            isCheckable = true
            id = View.generateViewId()
            tag = null
        }
        b.chipSources.addView(all)
        chips.add(all to null)

        rows.forEach { row ->
            val chip = Chip(requireContext()).apply {
                text = row.name
                isCheckable = true
                id = View.generateViewId()
                tag = row.name
            }
            b.chipSources.addView(chip)
            chips.add(chip to row.name)
        }

        val target = chips.firstOrNull { it.second == selectedSource } ?: chips.first()
        target.first.isChecked = true
        rebuildingChips = false
    }

    private fun updateBadge() {
        (activity as? MainActivity)?.updateSubscriptionsBadge()
    }

    // ---------------- 交互 ----------------

    private fun showAddDialog() {
        val ui = requireContext()
        val input = TextInputEditText(ui).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 1
        }
        val layout = TextInputLayout(ui).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = getString(R.string.subs_add_hint)
            addView(input)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(ui).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(layout)
        }
        val dialog = MaterialAlertDialogBuilder(ui)
            .setTitle(R.string.subs_add)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.add, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val raw = input.text?.toString()?.trim().orEmpty()
                if (raw.isBlank()) {
                    layout.error = getString(R.string.subs_url_required)
                    return@setOnClickListener
                }
                layout.error = null
                dialog.dismiss()
                addSubscription(raw)
            }
        }
        dialog.show()
    }

    private fun addSubscription(raw: String) {
        if (adding) return
        val b = _binding ?: return
        adding = true
        b.btnAdd.isEnabled = false
        b.progress.visibility = View.VISIBLE
        val appCtx = ctx
        Thread({
            val result = HubUi.addRssSubscription(appCtx, raw)
            handler.post {
                adding = false
                val vb = _binding ?: return@post
                vb.btnAdd.isEnabled = true
                vb.progress.visibility = View.GONE
                if (!isAdded) return@post
                result.onSuccess { name ->
                    toast(getString(R.string.subs_added, name))
                    refreshAll()
                }.onFailure { error ->
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.subs_add_failed_title)
                        .setMessage(
                            error.message ?: getString(R.string.subs_add_failed, getString(R.string.subs_unknown_error)),
                        )
                        .setPositiveButton(R.string.ok, null)
                        .show()
                }
            }
        }, "watchhub-subscribe").start()
    }

    private fun confirmUnsubscribe(row: HubUi.SubscriptionRow) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.subs_delete_confirm, row.name))
            .setMessage(R.string.subs_delete_confirm_desc)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                runCatching { HubUi.deleteSource(ctx, row.name) }
                    .onFailure {
                        toast(getString(R.string.subs_delete_failed, it.message ?: getString(R.string.subs_unknown_error)))
                    }
                refreshAll()
            }
            .show()
    }

    /** 手动触发一轮抓取（后台线程），完成后刷新列表。 */
    private fun refreshSubscriptions() {
        val b = _binding ?: return
        b.btnRefresh.isEnabled = false
        b.progress.visibility = View.VISIBLE
        val appCtx = ctx
        Thread({
            val added = runCatching { HubUi.refreshSubscriptions(appCtx) }.getOrDefault(-1)
            handler.post {
                val vb = _binding ?: return@post
                vb.btnRefresh.isEnabled = true
                vb.progress.visibility = View.GONE
                if (!isAdded) return@post
                if (added >= 0) toast(getString(R.string.subs_refreshed, added))
                else toast(getString(R.string.subs_load_failed, getString(R.string.subs_unknown_error)))
                refreshAll()
            }
        }, "watchhub-sub-refresh").start()
    }

    private fun markAllRead() {
        val count = runCatching { HubUi.markAllArticlesRead(ctx, selectedSource) }.getOrDefault(0)
        toast(getString(R.string.subs_mark_all_done, count))
        refreshAll()
    }

    private fun openArticle(row: HubUi.ArticleRow) {
        runCatching { HubUi.markArticleRead(ctx, row.id) }
        val article = runCatching { HubUi.articleById(ctx, row.id) }.getOrNull()
        refreshArticles()
        updateBadge()

        val title = article?.title?.ifBlank { row.title } ?: row.title
        val summary = (article?.summary ?: row.summary).ifBlank { getString(R.string.subs_no_summary) }
        val url = article?.url ?: row.url
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(summary)
            .setPositiveButton(R.string.close, null)
        if (url.isNotBlank()) {
            builder.setNeutralButton(R.string.subs_open_original) { _, _ -> openUrl(url) }
        }
        builder.show()
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { toast(getString(R.string.subs_open_link_failed)) }
    }

    private fun toast(text: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    }
}
