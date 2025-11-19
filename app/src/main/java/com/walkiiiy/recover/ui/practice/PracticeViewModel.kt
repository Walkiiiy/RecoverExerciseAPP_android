package com.walkiiiy.recover.ui.practice

import android.app.Application
import android.net.Uri
import android.util.Log
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
    // 1.定义类属性
    private val repository = PracticeSessionRepository.getInstance(application)
    private val scorer = PracticeScorer(application)

    // _uiState, uiState用于控制ui
    private val _uiState = MutableLiveData(PracticeUiState())
    val uiState: LiveData<PracticeUiState> = _uiState

    private val _isDemoVideoCompleted = MutableLiveData<Boolean>()
    val isDemoVideoCompleted: LiveData<Boolean> get() = _isDemoVideoCompleted

    // 1.2 设置视频播放结束
    fun onDemoVideoCompleted() {
        _isDemoVideoCompleted.value = true
    }

    // 1.3 重置视频播放状态
    fun resetDemoVideoStatus() {
        _isDemoVideoCompleted.value = false
    }

    // 2.UI控制方法：方法用于更新 UI 状态中的 isRecording 字段，当用户开始或停止录制时调用
    fun setRecording(isRecording: Boolean) {
        val current = _uiState.value ?: PracticeUiState()
        _uiState.postValue(current.copy(isRecording = isRecording, message = null))
    }

    // 3.UI控制方法：方法将 UI 状态重置为初始状态，通常用于清除之前的评分结果或恢复初始状态
    fun resetScore() {
        _uiState.postValue(PracticeUiState())
    }

    /*
    验证视频文件：确保视频文件存在且非空。

    创建 URI：为录制的视频和参考视频创建 Uri 对象。

    获取参考视频：从应用资源中加载参考视频，以便与录制的视频进行比较。

    评分算法：通过 PracticeScorer 使用录制的视频和参考视频进行姿态分析和评分。

    保存评估结果：将评分结果保存到数据库中。
     */
    // 4. 评分相关的模块 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~


    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    // 将评分设置成一个可输入参数 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    fun evaluateRecording(
        meta: PracticeExerciseMeta,
        recordedVideoPath: String,
        score: Double? = null // 新增的参数，默认为 null
    ) {
        val current = _uiState.value ?: PracticeUiState()
        _uiState.postValue(current.copy(isRecording = false, isScoring = true, message = "正在准备评估..."))

        // 使用 viewModelScope 确保在 ViewModel 销毁时自动取消
        viewModelScope.launch(Dispatchers.IO) {
            var currentStep = "初始化"
            try {
                Log.d(TAG, "开始评估录制视频: $recordedVideoPath")

                // 如果score不为null，直接跳过视频验证和评分算法步骤
                // val finalScore = score ?: run {
                if (score == null) {
                    // （1）验证录制的视频文件
                    currentStep = "验证视频文件"
                    updateMessage("正在验证视频文件...")
                    val recordedFile = File(recordedVideoPath)
                    if (!recordedFile.exists()) {
                        throw IllegalArgumentException("录制的视频文件不存在")
                    }
                    if (recordedFile.length() == 0L) {
                        throw IllegalArgumentException("录制的视频文件为空")
                    }
                    Log.d(TAG, "视频文件验证成功，大小: ${recordedFile.length()} 字节")

                    // （2）创建视频URI
                    currentStep = "创建视频URI"
                    val recordedUri = Uri.fromFile(recordedFile)
                    Log.d(TAG, "录制视频URI: $recordedUri")

                    // （3）获取参考视频URI
                    currentStep = "获取参考视频"
                    updateMessage("正在加载参考视频...")
                    val referenceUri = try {
                        PracticeScorer.rawToUri(getApplication(), meta.demoVideoRes)
                    } catch (e: Exception) {
                        Log.e(TAG, "获取参考视频失败", e)
                        throw IllegalArgumentException("无法加载参考视频，请检查资源文件")
                    }
                    Log.d(TAG, "参考视频URI: $referenceUri")

                    // （4）运行评分算法
                    currentStep = "分析动作姿态"
                    updateMessage("正在分析您的动作姿态...\n这可能需要一些时间")
                    try {
                        scorer.score(recordedUri, referenceUri) // 一个是正确动作参考视频，一个是安卓小绿人
                    } catch (e: Exception) {
                        Log.e(TAG, "评分失败", e)
                        // 提供更详细的错误信息
                        val errorMsg = when {
                            e.message?.contains("libmediapipe") == true ->
                                "姿态识别库加载失败\n错误: ${e.message?.substringAfter(":")?.trim()}"
                            e.message?.contains("initialize") == true || e.message?.contains("初始化") == true ->
                                "姿态识别模型初始化失败\n错误: ${e.message}"
                            e.message?.contains("video") == true || e.message?.contains("视频") == true ->
                                "视频处理失败\n错误: ${e.message}"
                            e.message?.contains("pose") == true || e.message?.contains("姿态") == true ->
                                "姿态检测失败\n错误: ${e.message}"
                            else ->
                                "评分过程出错\n错误: ${e.message ?: "未知错误"}"
                        }
                        throw RuntimeException(errorMsg, e)
                    }
                }

                Log.d(TAG, "评分完成: $score")

                // （5）保存到数据库
                currentStep = "保存评估结果"
                updateMessage("正在保存评估结果...")
                val session = PracticeSessionEntity(
                    exerciseId = meta.id,
                    exerciseTitle = meta.title,
                    exerciseDescription = meta.description,
//                    score = score,
                    score = score ?: 0.0,
                    recordedVideoPath = recordedVideoPath,
                    createdAtMillis = System.currentTimeMillis()
                )

                // 插入数据库，回调会在主线程执行
                repository.insertSession(session) {
                    // 这个回调现在保证在主线程执行
                    Log.d(TAG, "评估结果已保存到数据库")
                    _uiState.value = PracticeUiState(
                        isRecording = false,
                        isScoring = false,
//                        score = score,
                        score = score ?: 0.0,
                        message = null
                    )
                }
            } catch (error: Exception) {
                Log.e(TAG, "评估失败于步骤: $currentStep", error)
                // 切换到主线程更新UI
                withContext(Dispatchers.Main) {
                    val detailedMessage = buildErrorMessage(currentStep, error)
                    Log.e(TAG, "显示错误信息: $detailedMessage")
                    _uiState.value = PracticeUiState(
                        isRecording = false,
                        isScoring = false,
                        score = null,
                        message = detailedMessage
                    )
                }
            }
        }
    }




    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    /**
     * 更新评估过程中的提示消息
     */
    // 5.updateMessage 用于在评估过程中更新 UI 层的消息，显示当前的操作步骤或进度提示
    private suspend fun updateMessage(message: String) {
        withContext(Dispatchers.Main) {
            val current = _uiState.value ?: PracticeUiState()
            _uiState.value = current.copy(message = message)
        }
    }
    
    /**
     * 构建详细的错误消息
     */
    // 6.如果出错通过这里显示错误信息
    private fun buildErrorMessage(step: String, error: Exception): String {
        val errorDetail = error.message ?: "未知错误"
        val stepMessage = when (step) {
            "验证视频文件" -> "📹 视频文件验证失败"
            "创建视频URI" -> "🔗 视频路径处理失败"
            "获取参考视频" -> "📺 参考视频加载失败"
            "分析动作姿态" -> "🤸 动作分析失败"
            "保存评估结果" -> "💾 结果保存失败"
            else -> "❌ 评估失败"
        }
        
        return """
            $stepMessage
            
            步骤: $step
            错误: $errorDetail
            
            请尝试：
            1. 重新录制视频
            2. 检查网络和存储权限
            3. 重启应用后重试
        """.trimIndent()
    }

    override fun onCleared() {
        super.onCleared()
        scorer.close()
    }
    
    companion object {
        private const val TAG = "PracticeViewModel"
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
