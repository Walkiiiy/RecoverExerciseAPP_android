package com.walkiiiy.recover.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.walkiiiy.recover.databinding.FragmentHistoryBinding
import java.io.File

class HistoryFragment : Fragment(), HistoryAdapter.Callback {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private val adapter by lazy { HistoryAdapter(this) }
    private val viewModel: HistoryViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.historyRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.historyRecyclerView.adapter = adapter
        observeHistory()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onWatchVideoRequested(item: PracticeHistoryItem) {
        val file = File(item.videoPath)
        if (!file.exists()) {
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            val authority = "${requireContext().packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(requireContext(), authority, file)
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, item.exerciseTitle))
    }

    private fun observeHistory() {
        viewModel.historyItems.observe(viewLifecycleOwner) { items ->
            adapter.submitList(items)
            binding.emptyStateLayout.isVisible = items.isEmpty()
            binding.historyRecyclerView.isVisible = items.isNotEmpty()
        }
    }

    companion object {
        fun newInstance(): HistoryFragment = HistoryFragment()
    }
}
