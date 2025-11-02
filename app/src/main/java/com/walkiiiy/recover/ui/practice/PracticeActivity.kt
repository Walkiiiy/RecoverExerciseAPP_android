package com.walkiiiy.recover.ui.practice

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.walkiiiy.recover.R
import com.walkiiiy.recover.databinding.ActivityPracticeBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PracticeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPracticeBinding
    private val viewModel: PracticeViewModel by viewModels()

    private var exerciseMeta: PracticeExerciseMeta? = null

    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var currentVideoFile: File? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private val cameraExecutor by lazy {
        ContextCompat.getMainExecutor(this)
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.entries.all { it.value }
            if (granted) {
                startPracticeInternal()
            } else {
                Toast.makeText(this, R.string.request_permissions, Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPracticeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Use texture-based preview so it stays within its constrained bounds.
        binding.cameraPreview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE)

        exerciseMeta = extractExerciseMeta()
        if (exerciseMeta == null) {
            Toast.makeText(this, "缺少练习信息", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupBackPressedHandler()
        setupToolbar()
        setupButtons()
        setupObservers()
        setupDemoVideo()
        startCamera()
    }
    
    private fun setupBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPress()
            }
        })
    }
    
    private fun handleBackPress() {
        // 检查 Activity 状态
        if (isFinishing || isDestroyed) return
        
        val state = viewModel.uiState.value
        
        try {
            when {
                state?.isRecording == true -> {
                    // 正在录制，提示用户
                    AlertDialog.Builder(this@PracticeActivity)
                        .setTitle("正在录制")
                        .setMessage("当前正在录制视频，确定要停止并退出吗？")
                        .setPositiveButton("停止并退出") { _, _ ->
                            stopRecordingAndExit()
                        }
                        .setNegativeButton("继续录制", null)
                        .show()
                }
                state?.isScoring == true -> {
                    // 正在评分，提示用户
                    AlertDialog.Builder(this@PracticeActivity)
                        .setTitle("正在评估")
                        .setMessage("正在评估您的动作质量，请稍候...")
                        .setPositiveButton("后台继续") { _, _ ->
                            // 允许返回，评分在后台继续
                            finish()
                        }
                        .setNegativeButton("等待完成", null)
                        .show()
                }
                else -> {
                    // 没有任务进行，直接返回
                    finish()
                }
            }
        } catch (e: Exception) {
            // 如果对话框失败，直接finish
            finish()
        }
    }
    
    private fun stopRecordingAndExit() {
        try {
            activeRecording?.stop()
            activeRecording = null
        } catch (e: Exception) {
            // 忽略异常
        }
        
        // 短暂延迟，等待录制完全停止
        if (::binding.isInitialized && !isFinishing && !isDestroyed) {
            binding.root.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    finish()
                }
            }, 200)
        } else {
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        // 暂停演示视频，使用安全检查
        try {
            if (::binding.isInitialized && binding.demoVideoView.isPlaying) {
                binding.demoVideoView.pause()
            }
        } catch (e: Exception) {
            // 忽略异常
        }
    }

    override fun onStop() {
        super.onStop()
        // 只在Activity finishing时停止录制
        if (isFinishing) {
            try {
                activeRecording?.stop()
            } catch (e: Exception) {
                // 忽略异常
            }
        }
    }

    override fun onDestroy() {
        // 停止所有异步操作和资源
        try {
            // 1. 停止录制
            activeRecording?.stop()
            activeRecording = null
            
            // 2. 解绑相机（必须在主线程）
            cameraProvider?.unbindAll()
            cameraProvider = null
            videoCapture = null
            
            // 3. 释放 VideoView - 使用 suspend 和 release
            if (::binding.isInitialized) {
                try {
                    binding.demoVideoView.suspend()
                } catch (e: Exception) {
                    // 忽略
                }
            }
        } catch (e: Exception) {
            // 确保即使出错也继续执行
        } finally {
            super.onDestroy()
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.practiceToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.practiceToolbar.setNavigationOnClickListener { 
            handleBackPress()
        }
        binding.practiceToolbar.title = exerciseMeta?.title ?: getString(R.string.start_practice)
        binding.exerciseDescriptionText.text = exerciseMeta?.description.orEmpty()
    }

    private fun setupButtons() {
        binding.startPracticeButton.setOnClickListener {
            if (viewModel.uiState.value?.isScoring == true) return@setOnClickListener
            if (hasRequiredPermissions()) {
                startPracticeInternal()
            } else {
                permissionLauncher.launch(REQUIRED_PERMISSIONS)
            }
        }
        binding.stopPracticeButton.setOnClickListener {
            stopRecording()
        }
    }

    private fun setupObservers() {
        viewModel.uiState.observe(this) { state ->
            // 检查 Activity 状态，防止在销毁后更新UI
            if (isFinishing || isDestroyed || !::binding.isInitialized) {
                return@observe
            }
            
            try {
                binding.stopPracticeButton.isEnabled = state.isRecording
                binding.startPracticeButton.isEnabled = !state.isRecording && !state.isScoring
                binding.statusText.isVisible = state.isRecording || state.isScoring || state.message != null
                binding.statusText.text = when {
                    state.isRecording -> getString(R.string.recording_in_progress)
                    state.isScoring -> getString(R.string.recording_completed)
                    state.message != null -> state.message
                    else -> ""
                }
                binding.scoreResultText.visibility = if (state.score != null) View.VISIBLE else View.GONE
                binding.scoreResultText.text = state.score?.let { getString(R.string.score_label, it) } ?: ""
                
                // 状态重置时，重置对话框标志
                if (state.score == null && !state.isScoring && !state.isRecording) {
                    scoreDialogShown = false
                }
                
                // 评分完成后显示提示
                if (state.score != null && !state.isScoring && !state.isRecording) {
                    showScoreCompletedDialog(state.score)
                }
            } catch (e: Exception) {
                // 忽略UI更新异常
            }
        }
    }
    
    private var scoreDialogShown = false
    
    private fun showScoreCompletedDialog(score: Double) {
        // 避免重复显示对话框
        if (scoreDialogShown) return
        
        // 检查 Activity 状态
        if (isFinishing || isDestroyed) return
        
        scoreDialogShown = true
        
        val scoreEmoji = when {
            score >= 90 -> "🎉"
            score >= 80 -> "👍"
            score >= 70 -> "💪"
            score >= 60 -> "😊"
            else -> "💪"
        }
        
        val message = when {
            score >= 90 -> "太棒了！您的动作非常标准！"
            score >= 80 -> "很好！继续保持！"
            score >= 70 -> "不错！还可以更好！"
            score >= 60 -> "继续努力，加油！"
            else -> "多加练习，您会越来越好！"
        }
        
        try {
            AlertDialog.Builder(this)
                .setTitle("$scoreEmoji 训练完成")
                .setMessage("您的得分：${String.format("%.1f", score)}\n$message")
                .setPositiveButton("再次训练") { _, _ ->
                    scoreDialogShown = false
                    // 重置UI状态，准备下次训练
                    viewModel.resetScore()
                }
                .setNegativeButton("返回") { _, _ ->
                    finish()
                }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            // 如果对话框显示失败，直接finish
            finish()
        }
    }

    private fun setupDemoVideo() {
        if (!::binding.isInitialized) return
        
        val meta = exerciseMeta ?: return
        try {
            val videoUri = Uri.parse("android.resource://$packageName/${meta.demoVideoRes}")
            binding.demoVideoView.setVideoURI(videoUri)
            binding.demoVideoView.setOnPreparedListener { mediaPlayer ->
                mediaPlayer.isLooping = false
            }
            binding.demoVideoView.setOnCompletionListener {
                if (!isFinishing && !isDestroyed && viewModel.uiState.value?.isRecording == true) {
                    stopRecording()
                }
            }
        } catch (e: Exception) {
            // 忽略视频设置异常
        }
    }

    private fun startPracticeInternal() {
        if (isFinishing || isDestroyed || !::binding.isInitialized) return
        
        val meta = exerciseMeta ?: return
        try {
            binding.demoVideoView.start()
            startRecording(meta)
        } catch (e: Exception) {
            // 忽略启动异常
            Toast.makeText(this, "启动失败，请重试", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            // 检查 Activity 是否还存活
            if (isFinishing || isDestroyed || !::binding.isInitialized) {
                return@addListener
            }
            
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                
                val preview = Preview.Builder()
                    .build()
                    .also { it.setSurfaceProvider(binding.cameraPreview.surfaceProvider) }

                val qualitySelector = QualitySelector.fromOrderedList(
                    listOf(Quality.FHD, Quality.HD, Quality.SD)
                )

                val recorder = Recorder.Builder()
                    .setQualitySelector(qualitySelector)
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    videoCapture
                )
            } catch (exc: Exception) {
                if (!isFinishing && !isDestroyed) {
                    try {
                        Toast.makeText(this, "无法启动相机", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        // 忽略Toast异常
                    }
                }
            }
        }, cameraExecutor)
    }

    private fun startRecording(meta: PracticeExerciseMeta) {
        val videoCapture = videoCapture ?: return
        val recordingsDir = File(
            getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "practice_sessions"
        ).apply { if (!exists()) mkdirs() }

        val fileName = "session_${meta.id}_" + SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.getDefault()
        ).format(Date()) + ".mp4"

        val outputFile = File(recordingsDir, fileName)
        currentVideoFile = outputFile

        val outputOptions = FileOutputOptions.Builder(outputFile).build()

        val pending = videoCapture.output.prepareRecording(this, outputOptions).apply {
            if (hasAudioPermission()) {
                withAudioEnabled()
            }
        }

        activeRecording = pending.start(cameraExecutor) { event ->
            // 检查 Activity 是否还存活，避免在销毁后操作 UI
            if (isFinishing || isDestroyed) {
                return@start
            }
            
            try {
                when (event) {
                    is VideoRecordEvent.Start -> {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed && ::binding.isInitialized) {
                                try {
                                    viewModel.setRecording(true)
                                    binding.stopPracticeButton.isEnabled = true
                                } catch (e: Exception) {
                                    // 忽略UI更新异常
                                }
                            }
                        }
                    }

                    is VideoRecordEvent.Finalize -> {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed && ::binding.isInitialized) {
                                try {
                                    viewModel.setRecording(false)
                                    binding.stopPracticeButton.isEnabled = false
                                } catch (e: Exception) {
                                    // 忽略UI更新异常
                                }
                            }
                        }
                        activeRecording = null
                        
                        if (!event.hasError()) {
                            currentVideoFile?.let { file ->
                                // 只在Activity存活时才评估录制
                                if (!isFinishing && !isDestroyed) {
                                    viewModel.evaluateRecording(meta, file.absolutePath)
                                }
                            }
                        } else {
                            currentVideoFile?.delete()
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed) {
                                    try {
                                        Toast.makeText(this, "录制失败，请重试", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        // 忽略Toast异常
                                    }
                                }
                            }
                        }
                        currentVideoFile = null
                    }
                }
            } catch (e: Exception) {
                // 捕获所有异常，防止崩溃
                activeRecording = null
                currentVideoFile?.delete()
                currentVideoFile = null
            }
        }
    }

    private fun stopRecording() {
        try {
            if (::binding.isInitialized) {
                binding.demoVideoView.pause()
                binding.demoVideoView.seekTo(0)
            }
            activeRecording?.stop()
        } catch (e: Exception) {
            // 忽略异常
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(
                this,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun extractExerciseMeta(): PracticeExerciseMeta? {
        val id = intent.getStringExtra(EXTRA_EXERCISE_ID) ?: return null
        val title = intent.getStringExtra(EXTRA_EXERCISE_TITLE) ?: return null
        val description = intent.getStringExtra(EXTRA_EXERCISE_DESCRIPTION) ?: ""
        val demoVideoName = intent.getStringExtra(EXTRA_VIDEO_RES_NAME) ?: return null
        val demoVideo = resources.getIdentifier(demoVideoName, "raw", packageName)
        if (demoVideo == 0) return null
        val repetitionCount = intent.getIntExtra(EXTRA_REPETITION_COUNT, 0)
        return PracticeExerciseMeta(id, title, description, demoVideo, repetitionCount)
    }

    companion object {
        const val EXTRA_EXERCISE_ID = "extra_exercise_id"
        const val EXTRA_EXERCISE_TITLE = "extra_exercise_title"
        const val EXTRA_EXERCISE_DESCRIPTION = "extra_exercise_description"
        const val EXTRA_VIDEO_RES_NAME = "extra_video_res_name"
        const val EXTRA_REPETITION_COUNT = "extra_repetition_count"

        private val REQUIRED_PERMISSIONS = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}
