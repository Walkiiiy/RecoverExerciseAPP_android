package com.walkiiiy.recover.ui.exercise

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.walkiiiy.recover.data.ExerciseCatalog
import com.walkiiiy.recover.data.model.Exercise
import com.walkiiiy.recover.databinding.FragmentExerciseListBinding
import com.walkiiiy.recover.ui.practice.PracticeActivity

class ExerciseListFragment : Fragment(), ExerciseAdapter.Callback {

    private var _binding: FragmentExerciseListBinding? = null
    private val binding get() = _binding!!

    private val adapter: ExerciseAdapter by lazy {
        ExerciseAdapter(this)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExerciseListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.exerciseRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.exerciseRecyclerView.adapter = adapter
        adapter.submitList(ExerciseCatalog.exercises)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onExerciseSelected(exercise: Exercise) {
        val intent = Intent(requireContext(), PracticeActivity::class.java).apply {
            putExtra(PracticeActivity.EXTRA_EXERCISE_ID, exercise.id)
            putExtra(PracticeActivity.EXTRA_EXERCISE_TITLE, exercise.title)
            putExtra(PracticeActivity.EXTRA_EXERCISE_DESCRIPTION, exercise.description)
            putExtra(PracticeActivity.EXTRA_VIDEO_RES_NAME, exercise.demoVideoAssetName)
            putExtra(PracticeActivity.EXTRA_REPETITION_COUNT, exercise.repetitionCount)
        }
        startActivity(intent)
    }

    companion object {
        fun newInstance(): ExerciseListFragment = ExerciseListFragment()
    }
}
