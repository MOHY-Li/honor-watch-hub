package dev.watchhub.app.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.watchhub.app.R
import dev.watchhub.app.databinding.ItemSubscriptionBinding
import dev.watchhub.app.ui.HubUi

/** 订阅源横向列表：未读数、状态、暂停/恢复、取消订阅。 */
class SubscriptionAdapter(
    private val onToggle: (HubUi.SubscriptionRow, Boolean) -> Unit,
    private val onDelete: (HubUi.SubscriptionRow) -> Unit,
) : ListAdapter<HubUi.SubscriptionRow, SubscriptionAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSubscriptionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val binding: ItemSubscriptionBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: HubUi.SubscriptionRow) {
            val ctx = binding.root.context
            binding.name.text = row.name
            binding.unread.text = ctx.getString(R.string.subs_unread, row.unread)
            binding.unread.visibility = if (row.unread > 0) View.VISIBLE else View.GONE
            binding.status.text = row.statusText

            binding.btnToggle.text = ctx.getString(
                if (row.enabled) R.string.subs_pause else R.string.subs_resume,
            )
            binding.btnToggle.setOnClickListener { onToggle(row, !row.enabled) }
            binding.btnDelete.setOnClickListener { onDelete(row) }

            binding.name.alpha = if (row.enabled) 1f else 0.5f
            binding.status.alpha = if (row.enabled) 1f else 0.6f
        }
    }

    private object Diff : DiffUtil.ItemCallback<HubUi.SubscriptionRow>() {
        override fun areItemsTheSame(oldItem: HubUi.SubscriptionRow, newItem: HubUi.SubscriptionRow): Boolean =
            oldItem.name == newItem.name

        override fun areContentsTheSame(oldItem: HubUi.SubscriptionRow, newItem: HubUi.SubscriptionRow): Boolean =
            oldItem == newItem
    }
}
