package com.walkiiiy.recover.ui.history

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.walkiiiy.recover.databinding.ItemHistoryEntryBinding

class HistoryAdapter(
    private val callback: Callback
) : ListAdapter<PracticeHistoryItem, HistoryAdapter.HistoryViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemHistoryEntryBinding.inflate(inflater, parent, false)
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class HistoryViewHolder(
        private val binding: ItemHistoryEntryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PracticeHistoryItem) {
            binding.titleText.text = item.exerciseTitle
            binding.timeText.text = item.timestampText
            binding.scoreText.text = item.scoreText
            binding.watchButton.setOnClickListener {
                callback.onWatchVideoRequested(item)
            }
        }
    }

    interface Callback {
        fun onWatchVideoRequested(item: PracticeHistoryItem)
    }

    private object DiffCallback : DiffUtil.ItemCallback<PracticeHistoryItem>() {
        override fun areItemsTheSame(
            oldItem: PracticeHistoryItem,
            newItem: PracticeHistoryItem
        ): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(
            oldItem: PracticeHistoryItem,
            newItem: PracticeHistoryItem
        ): Boolean = oldItem == newItem
    }
}
