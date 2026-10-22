package dev.watchhub.app.ui.adapters

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import dev.watchhub.app.databinding.ItemArticleBinding
import dev.watchhub.app.ui.HubUi

/** 订阅文章列表。未读加粗并显示小圆点。 */
class ArticleAdapter(
    private val onClick: (HubUi.ArticleRow) -> Unit,
) : ListAdapter<HubUi.ArticleRow, ArticleAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemArticleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val binding: ItemArticleBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: HubUi.ArticleRow) {
            val ctx = binding.root.context
            binding.title.text = row.title
            binding.title.setTypeface(null, if (row.unread) Typeface.BOLD else Typeface.NORMAL)

            binding.dot.visibility = if (row.unread) View.VISIBLE else View.INVISIBLE
            binding.dot.backgroundTintList = android.content.res.ColorStateList.valueOf(
                MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary),
            )

            val meta = listOf(row.source, row.timeText).filter { it.isNotBlank() }.joinToString(" · ")
            binding.meta.text = meta
            binding.summary.text = row.summary
            binding.summary.visibility = if (row.summary.isBlank()) View.GONE else View.VISIBLE

            binding.root.setOnClickListener { onClick(row) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<HubUi.ArticleRow>() {
        override fun areItemsTheSame(oldItem: HubUi.ArticleRow, newItem: HubUi.ArticleRow): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: HubUi.ArticleRow, newItem: HubUi.ArticleRow): Boolean =
            oldItem == newItem
    }
}
