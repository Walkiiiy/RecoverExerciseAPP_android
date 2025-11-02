package com.walkiiiy.recover.ui.exercise

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.walkiiiy.recover.R
import com.walkiiiy.recover.data.model.Exercise
import com.walkiiiy.recover.databinding.ItemExerciseBinding

class ExerciseAdapter(
    private val callback: Callback
) : ListAdapter<Exercise, ExerciseAdapter.ExerciseViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExerciseViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemExerciseBinding.inflate(inflater, parent, false)
        return ExerciseViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ExerciseViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ExerciseViewHolder(
        private val binding: ItemExerciseBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(exercise: Exercise) {
            binding.titleText.text = exercise.title
            binding.descriptionText.text = exercise.description
            binding.repetitionBadge.text =
                binding.root.context.getString(R.string.exercise_count, exercise.repetitionCount)
            
            // 设置缩略图
            val thumbnailResId = binding.root.context.resources.getIdentifier(
                exercise.thumbnailDrawable,
                "drawable",
                binding.root.context.packageName
            )
            if (thumbnailResId != 0) {
                binding.videoThumbnail.setImageResource(thumbnailResId)
            }
            
            binding.startButton.setOnClickListener {
                callback.onExerciseSelected(exercise)
            }
        }
    }

    interface Callback {
        fun onExerciseSelected(exercise: Exercise)
    }

    private object DiffCallback : DiffUtil.ItemCallback<Exercise>() {
        override fun areItemsTheSame(oldItem: Exercise, newItem: Exercise): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Exercise, newItem: Exercise): Boolean =
            oldItem == newItem
    }
}
