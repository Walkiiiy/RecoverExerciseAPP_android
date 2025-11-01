package com.walkiiiy.recover.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import com.walkiiiy.recover.R
import com.walkiiiy.recover.data.repository.PracticeSessionRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PracticeSessionRepository.getInstance(application)
    private val timeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    val historyItems: LiveData<List<PracticeHistoryItem>> =
        repository.observeSessions().map { sessions ->
            sessions.map { entity ->
                val timestampText = timeFormatter.format(Date(entity.createdAtMillis))
                val scoreText = application.getString(R.string.score_label, entity.score)
                PracticeHistoryItem(
                    id = entity.id,
                    exerciseTitle = entity.exerciseTitle,
                    timestampText = timestampText,
                    scoreText = scoreText,
                    videoPath = entity.recordedVideoPath
                )
            }
        }
}
