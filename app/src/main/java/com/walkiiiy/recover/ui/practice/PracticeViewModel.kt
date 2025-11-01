package com.walkiiiy.recover.ui.practice

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.walkiiiy.recover.data.db.PracticeSessionEntity
import com.walkiiiy.recover.data.repository.PracticeSessionRepository
import com.walkiiiy.recover.data.scoring.PracticeScorer
import java.io.File

class PracticeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PracticeSessionRepository.getInstance(application)
    private val scorer = PracticeScorer(application)

    private val _uiState = MutableLiveData(PracticeUiState())
    val uiState: LiveData<PracticeUiState> = _uiState

    fun setRecording(isRecording: Boolean) {
        val current = _uiState.value ?: PracticeUiState()
        _uiState.postValue(current.copy(isRecording = isRecording, message = null))
    }

    fun evaluateRecording(meta: PracticeExerciseMeta, recordedVideoPath: String) {
        val current = _uiState.value ?: PracticeUiState()
        _uiState.postValue(current.copy(isRecording = false, isScoring = true, message = null))

        Thread {
            try {
                val recordedUri = Uri.fromFile(File(recordedVideoPath))
                val referenceUri = PracticeScorer.rawToUri(getApplication(), meta.demoVideoRes)
                val score = scorer.score(recordedUri, referenceUri)
                val session = PracticeSessionEntity(
                    exerciseId = meta.id,
                    exerciseTitle = meta.title,
                    exerciseDescription = meta.description,
                    score = score,
                    recordedVideoPath = recordedVideoPath,
                    createdAtMillis = System.currentTimeMillis()
                )
                repository.insertSession(session) {
                    _uiState.postValue(
                        PracticeUiState(
                            isRecording = false,
                            isScoring = false,
                            score = score,
                            message = null
                        )
                    )
                }
            } catch (error: Exception) {
                _uiState.postValue(
                    PracticeUiState(
                        isRecording = false,
                        isScoring = false,
                        score = null,
                        message = error.localizedMessage ?: "评估失败"
                    )
                )
            }
        }.start()
    }

    override fun onCleared() {
        super.onCleared()
        scorer.close()
    }
}

data class PracticeExerciseMeta(
    val id: String,
    val title: String,
    val description: String,
    val demoVideoRes: Int,
    val repetitionCount: Int,
)

data class PracticeUiState(
    val isRecording: Boolean = false,
    val isScoring: Boolean = false,
    val score: Double? = null,
    val message: String? = null,
)
