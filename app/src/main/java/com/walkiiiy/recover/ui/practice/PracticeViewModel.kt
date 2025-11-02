package com.walkiiiy.recover.ui.practice

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.walkiiiy.recover.data.db.PracticeSessionEntity
import com.walkiiiy.recover.data.repository.PracticeSessionRepository
import com.walkiiiy.recover.data.scoring.PracticeScorer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    
    fun resetScore() {
        _uiState.postValue(PracticeUiState())
    }

    fun evaluateRecording(meta: PracticeExerciseMeta, recordedVideoPath: String) {
        val current = _uiState.value ?: PracticeUiState()
        _uiState.postValue(current.copy(isRecording = false, isScoring = true, message = null))

        // 使用 viewModelScope 确保在 ViewModel 销毁时自动取消
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val recordedUri = Uri.fromFile(File(recordedVideoPath))
                
                val referenceUri = PracticeScorer.rawToUri(getApplication(), meta.demoVideoRes)
                val score = scorer.score(recordedUri, referenceUri)
                //val score = 100.00
                
                val session = PracticeSessionEntity(
                    exerciseId = meta.id,
                    exerciseTitle = meta.title,
                    exerciseDescription = meta.description,
                    score = score,
                    recordedVideoPath = recordedVideoPath,
                    createdAtMillis = System.currentTimeMillis()
                )
                
                // 插入数据库，回调会在主线程执行
                repository.insertSession(session) {
                    // 这个回调现在保证在主线程执行
                    _uiState.value = PracticeUiState(
                        isRecording = false,
                        isScoring = false,
                        score = score,
                        message = null
                    )
                }
            } catch (error: Exception) {
                // 切换到主线程更新UI
                withContext(Dispatchers.Main) {
                    _uiState.value = PracticeUiState(
                        isRecording = false,
                        isScoring = false,
                        score = null,
                        message = error.localizedMessage ?: "评估失败"
                    )
                }
            }
        }
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
