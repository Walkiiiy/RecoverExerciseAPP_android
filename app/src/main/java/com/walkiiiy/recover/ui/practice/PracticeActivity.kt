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
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Observer


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
// cameraX，流数据相关的库
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.ImageAnalysis.Analyzer

// 姿势识别相关的内容
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import android.os.SystemClock
import android.graphics.ImageFormat
import android.graphics.PixelFormat


// json 文件存储
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.walkiiiy.recover.data.scoring.PracticeScorer
import java.io.OutputStreamWriter
import java.io.InputStreamReader
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.pow
import kotlin.math.abs


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
            // 检查 Activity 是否还存活
            if (isFinishing || isDestroyed || !::binding.isInitialized) {
                return@registerForActivityResult
            }
            
            val granted = result.entries.all { it.value }
            if (granted) {
                // 权限授予后，启动图像分析（而不是录制视频）
                // 确保相机已经初始化后再启动
                if (cameraProvider != null) {
                    startImageAnalysis()
                    viewModel.setRecording(true)
                    binding.stopPracticeButton.isEnabled = true
                    setupDemoVideo()
                } else {
                    // 如果相机还没初始化，等待一下再启动
                    binding.root.postDelayed({
                        if (!isFinishing && !isDestroyed && ::binding.isInitialized && cameraProvider != null) {
                            startImageAnalysis()
                            viewModel.setRecording(true)
                            binding.stopPracticeButton.isEnabled = true
                            setupDemoVideo()
                        }
                    }, 300)
                }
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
            // 1. 停止图像分析（如果正在运行）
            try {
                imageAnalysisUseCase?.let { useCase ->
                    cameraProvider?.unbind(useCase)
                }
                imageAnalysisUseCase = null
            } catch (e: Exception) {
                // 忽略异常
            }
            
            // 2. 停止录制（如果正在录制）
            try {
                activeRecording?.stop()
            } catch (e: Exception) {
                // 忽略异常
            }
            activeRecording = null
            
            // 3. 解绑相机（必须在主线程）
            try {
                cameraProvider?.unbindAll()
            } catch (e: Exception) {
                // 忽略异常
            }
            cameraProvider = null
            videoCapture = null
            
            // 4. 释放 VideoView - 使用 suspend 和 release
            if (::binding.isInitialized) {
                try {
                    binding.demoVideoView.suspend()
                } catch (e: Exception) {
                    // 忽略
                }
            }
            
            // 5. 清空缓存
            try {
                postureResultsCache.clear()
            } catch (e: Exception) {
                // 忽略
            }
        } catch (e: Exception) {
            // 确保即使出错也继续执行
            Log.e(TAG, "Error in onDestroy: ${e.message}", e)
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
    // 启动相关的按钮
    binding.startPracticeButton.setOnClickListener {
        if (viewModel.uiState.value?.isScoring == true) return@setOnClickListener

        if (hasRequiredPermissions()) {
            // 启动实时图像分析，不进行录制
            startImageAnalysis()  // 启动图像分析
            viewModel.setRecording(true)  // 更新UI状态，表示正在进行分析
            binding.stopPracticeButton.isEnabled = true  // 启用停止按钮

            // 只有在开始按钮点击后才播放标准动作视频
            setupDemoVideo()  // 启动标准动作视频播放
        } else {
            permissionLauncher.launch(REQUIRED_PERMISSIONS)         // 这里权限还是有问题，第一次没有权限的时候，会执行旧版本 （闪退） ******************
        }
    }

    // 结束相关的按钮
    binding.stopPracticeButton.setOnClickListener {
        stopImageAnalysis()  // 停止图像分析
        viewModel.setRecording(false)  // 更新UI状态，表示结束分析
        binding.stopPracticeButton.isEnabled = false  // 禁用停止按钮

        // 停止标准动作视频
        binding.demoVideoView.stopPlayback()

        // ----------------------------------------------------------------------------------------------
        // 生成 meta 和文件路径
        val meta = exerciseMeta ?: return@setOnClickListener  // 生成 meta

        val recordingsDir = File(
            getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "practice_sessions"
        ).apply {
            if (!exists()) mkdirs()
        }

        // 根据当前时间生成唯一的文件名
        val fileName = "session_${meta.id}_" + SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.getDefault()
        ).format(Date()) + ".mp4"

        // 生成完整的文件路径
        val outputFile = File(recordingsDir, fileName)

        // 进行评分
        if (postureResultsCache.isNotEmpty()) {
            val score = calculateScore(postureResultsCache)
            // 传递分数和生成的文件路径到 ViewModel
            viewModel.evaluateRecording(meta, outputFile.absolutePath, score)
        } else {
            // 如果没有结果，给出默认评分
            viewModel.evaluateRecording(meta, outputFile.absolutePath, 0.0)
        }
        // 清空缓存，便于下一次会话重新开始
        try { postureResultsCache.clear() } catch (_: Exception) {}
    }

    // 观察视频是否播放完成
    viewModel.isDemoVideoCompleted.observe(this, Observer { isCompleted ->
        if (isCompleted) {
            // 视频播放完成，执行评分逻辑
            val meta = exerciseMeta ?: return@Observer
            val recordingsDir = File(
                getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                "practice_sessions"
            ).apply {
                if (!exists()) mkdirs()
            }

            // 根据当前时间生成唯一的文件名
            val fileName = "session_${meta.id}_" + SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.getDefault()
            ).format(Date()) + ".mp4"

            // 生成完整的文件路径
            val outputFile = File(recordingsDir, fileName)

            // 进行评分
            if (postureResultsCache.isNotEmpty()) {
                val score = calculateScore(postureResultsCache)
                // 传递分数和生成的文件路径到 ViewModel
                viewModel.evaluateRecording(meta, outputFile.absolutePath, score)
            } else {
                // 如果没有结果，给出默认评分
                viewModel.evaluateRecording(meta, outputFile.absolutePath, 0.0)
            }
            // 清空缓存，便于下一次会话重新开始
            try { postureResultsCache.clear() } catch (_: Exception) {}
            // 重置视频播放状态
            viewModel.resetDemoVideoStatus()
        }
    })
}


    // ----------------------------------------------------------------------------------------------

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
        // 只在开始按钮点击后加载标准动作视频
        val videoUri = Uri.parse("android.resource://$packageName/${meta.demoVideoRes}")
        binding.demoVideoView.setVideoURI(videoUri)

        binding.demoVideoView.setOnPreparedListener { mediaPlayer ->
            mediaPlayer.isLooping = false // 不循环播放
        }

        binding.demoVideoView.setOnCompletionListener {
            // 标准动作视频播放完毕后，停止实时图像分析
            stopImageAnalysis()  // 停止实时图像分析
            viewModel.setRecording(false)  // 更新UI状态为停止分析
            binding.stopPracticeButton.isEnabled = false  // 禁用停止按钮

            // 通知 ViewModel，视频播放已结束
            viewModel.onDemoVideoCompleted()
        }

        // 启动标准动作视频播放
        binding.demoVideoView.start()

    } catch (e: Exception) {
        // 忽略视频设置异常
    }
}


    // 启动录制视频
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


    // 实时版本的实现 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    // (0) poselandmarker 初始化
    private var poseLandmarker: PoseLandmarker? = null
    private var isInitialized = false
    private var initializationError: Throwable? = null
    private var frameTimestamp = 0L  // 用于实时流的时间戳计数器

    private fun ensureInitialized() {
        if (isInitialized) {
            // 如果之前初始化失败过，抛出异常
            initializationError?.let {
                throw RuntimeException(
                    "❌ MediaPipe姿态识别库不可用\n\n" +
                            "错误原因：${it.message}\n\n" +
                            "可能的解决方案：\n" +
                            "1. MediaPipe库未正确安装\n" +
                            "2. 设备架构不支持(需要ARM64)\n" +
                            "3. 依赖配置有问题\n\n" +
                            "当前使用模拟评分模式进行测试",
                    it
                )
            }
            return
        }

        try {
            Log.d(TAG, "开始初始化MediaPipe PoseLandmarker...")

            val baseOptions = BaseOptions.builder()
                .setDelegate(Delegate.CPU)
                .setModelAssetPath("pose_landmarker_full.task")
                .build()

            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinPoseDetectionConfidence(DEFAULT_POSE_DETECTION_CONFIDENCE)
                .setMinTrackingConfidence(DEFAULT_POSE_TRACKING_CONFIDENCE)
                .setMinPosePresenceConfidence(DEFAULT_POSE_PRESENCE_CONFIDENCE)
                .setRunningMode(RunningMode.VIDEO)  // 使用 VIDEO 模式，时间戳需要单调递增
                .build()

            poseLandmarker = PoseLandmarker.createFromOptions(this, options)
            isInitialized = true
            Log.d(TAG, "PoseLandmarker initialized successfully")
        } catch (e: UnsatisfiedLinkError) {
            // Native库加载失败
            Log.e(TAG, "Native library loading failed", e)
            initializationError = e
            isInitialized = true

            val errorMsg = when {
                e.message?.contains("libmediapipe_tasks_vision_jni.so") == true ->
                    "MediaPipe native库(libmediapipe_tasks_vision_jni.so)未找到"
                else ->
                    "Native库加载失败: ${e.message}"
            }

            throw RuntimeException(
                "❌ MediaPipe库加载失败\n\n" +
                        "错误：$errorMsg\n\n" +
                        "这是依赖配置问题，需要：\n" +
                        "1. 检查build.gradle中的MediaPipe版本\n" +
                        "2. 确保使用正确的依赖版本\n" +
                        "3. 清理并重新构建项目\n\n" +
                        "技术细节：${e.javaClass.simpleName}",
                e
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize PoseLandmarker", e)
            initializationError = e
            isInitialized = true

            throw RuntimeException(
                "❌ 姿态识别模型初始化失败\n\n" +
                        "错误类型：${e.javaClass.simpleName}\n" +
                        "错误信息：${e.message}\n\n" +
                        "请检查：\n" +
                        "1. 模型文件是否存在于assets目录\n" +
                        "2. 应用权限是否充足\n" +
                        "3. 设备内存是否充足",
                e
            )
        }
    }

    private fun resetPoseLandmarker() {
        try {
            poseLandmarker?.close()
            poseLandmarker = null
            isInitialized = false
            initializationError = null
            frameTimestamp = 0L  // 重置时间戳
            Log.d(TAG, "PoseLandmarker已重置")
        } catch (e: Exception) {
            Log.w(TAG, "重置PoseLandmarker时出错: ${e.message}")
        }
    }

    // (1) 提取动作 【动作提取】
    fun processFrameForPostureRecognition(imageProxy: ImageProxy): List<List<Float>> {
        // 不要每次都重置，只在开始/停止时重置
        ensureInitialized()
        
        return try {
            // 获取图像的格式
            val format = imageProxy.format
            Log.d("PostureRecognition", "Image format: $format")

            // 将 ImageProxy 转换为 Bitmap
            val bitmap = when (format) {
                ImageFormat.YUV_420_888 -> {
                    // YUV 格式需要转换
                    yuvToArgb8888(imageProxy)
                }
                PixelFormat.RGBA_8888, ImageFormat.RGB_565 -> {
                    // 直接从 ImageProxy 创建 Bitmap
                    val bitmapBuffer = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
                    imageProxy.use { 
                        bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer)
                    }
                    bitmapBuffer
                }
                else -> {
                    // 其他格式，尝试转换
                    Log.w("PostureRecognition", "Unsupported image format: $format, attempting conversion")
                    val bitmapBuffer = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
                    imageProxy.use { 
                        // 尝试从第一个 plane 读取数据
                        if (imageProxy.planes.isNotEmpty()) {
                            bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer)
                        }
                    }
                    bitmapBuffer
                }
            }

            // 确保 bitmap 不为空且格式正确
            if (bitmap.width <= 0 || bitmap.height <= 0) {
                Log.w("PostureRecognition", "Invalid bitmap dimensions")
                imageProxy.close()
                return emptyList()
            }

            // 转换为 ARGB_8888 格式（如果需要）
            val argbBitmap = if (bitmap.config != Bitmap.Config.ARGB_8888) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bitmap
            }

            // 使用 PoseLandmarker 进行姿势检测
            // 使用递增的时间戳（实时流模式需要连续递增的时间戳）
            val timestamp = frameTimestamp++
            val mpImage = BitmapImageBuilder(argbBitmap).build()
            
            // 实时流模式使用 detectAsync，但我们需要同步结果，所以使用 detectForVideo
            // 注意：LIVE_STREAM 模式下，detectForVideo 仍然可用，但时间戳必须单调递增
            val result = poseLandmarker?.detectForVideo(mpImage, timestamp)

            // 安全地获取检测到的 landmarks     //【未采集点的填补】
            // 修复：检查 landmarks 列表是否为空，避免 IndexOutOfBoundsException
            val landmarksList = result?.landmarks()
            val firstLandmark = landmarksList?.getOrNull(0)  // 使用 getOrNull 避免异常
            
            if (firstLandmark == null || firstLandmark.isEmpty()) {
                Log.d("PostureRecognition", "No pose detected in frame (timestamp: $timestamp)")
                imageProxy.close()
                return emptyList()
            }

            // 检查有效关键点数量
            // 如果有效关键点少于15个，说明只检测到面部等局部特征，将所有坐标设为0
            val validLandmarkCount = firstLandmark.count { landmark ->
                val x = landmark.x()
                val y = landmark.y()
                val z = landmark.z()

                // 判断关键点是否有效：
                // 1. 坐标均为有限值
                // 2. 不全部接近 0（避免未检测到的默认值）
                // 3. 不出现极端异常值（>5 或 <-5），防止噪声
                val isFinite = x.isFinite() && y.isFinite() && z.isFinite()
                val notZero = abs(x) + abs(y) + abs(z) > 0.01f
                val reasonable = abs(x) <= 5f && abs(y) <= 5f && abs(z) <= 5f
                isFinite && notZero && reasonable
            }
            
            // 如果有效关键点少于15个，返回全0的列表（33个关键点，每个都是[0,0,0]）
            if (validLandmarkCount < 15) {
                Log.d("PostureRecognition", "Valid landmarks count ($validLandmarkCount) < 15, returning zeros (timestamp: $timestamp)")
                imageProxy.close()
                // 返回33个全0的关键点
                return List(33) { listOf(0f, 0f, 0f) }
            }

            // 转换为 List<List<Float>>
            val resultList = firstLandmark.map { lm -> 
                listOf(lm.x(), lm.y(), lm.z()) 
            }
            
            imageProxy.close()
            resultList
            
        } catch (e: Exception) {
            // 捕获异常并打印日志
            Log.e("PostureRecognition", "Error processing frame for posture recognition: ${e.message}", e)
            e.printStackTrace()
            // 确保关闭 ImageProxy
            try {
                imageProxy.close()
            } catch (closeEx: Exception) {
                Log.e("PostureRecognition", "Error closing ImageProxy: ${closeEx.message}")
            }
            // 返回空列表
            emptyList()
        }
    }

    // 用于 YUV 格式转换为 ARGB_8888 的方法（需要实现具体转换逻辑）
    fun yuvToArgb8888(imageProxy: ImageProxy): Bitmap {
        // 提取 YUV 数据并转换为 ARGB_8888 格式的图像
        val plane = imageProxy.planes[0]  // 获取 Y 平面（如果是 YUV 420 格式）
        val buffer = plane.buffer
        // 这里需要自定义 YUV 转 RGB 或 ARGB 的转换方法
        // 此处只返回一个假设的 ARGB 图像
        val bitmap = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
        // 实现 YUV 转 ARGB 的过程
        return bitmap
    }

    // （2）评分函数：读取标准姿势、对齐并计算相似度得分（0-100）   // 涉及json保存 *************
    private fun calculateScore(postureResultsCache: List<List<List<Float>>>): Double {
        if (postureResultsCache.isEmpty()) return 0.0

        // 1) 读取与演示视频同名（或约定后缀）的标准姿势 JSON
        val standardSequence = readStandardPoseSequence() ?: return 0.0
        if (standardSequence.isEmpty()) return 0.0

        // 3) 归一化标准姿势序列并保存
        val normalizedStandard = standardSequence.map { pose ->
            normalizeLandmarks(pose)
        }
        saveNormalizedPosesToJsonFile(this, normalizedStandard, "pose-std.json")

        // 2) 归一化检测到的姿势序列并保存
        val normalizedDetected = postureResultsCache.map { pose ->
            normalizeLandmarks(pose)
        }
        saveNormalizedPosesToJsonFile(this, normalizedDetected, "pose-detect.json")



        // 4) 计算相似度（参考 PracticeScorer.calculateSimilarity）
        val similarity = calculateSimilarity(postureResultsCache, standardSequence)
        
        // 5) 转换为 0-100 的分数
        val score = min(similarity*4.5,1.0) * 100.0
        
        Log.d(TAG, "评分完成: similarity=$similarity, score=$score")
        return score.coerceIn(0.0, 100.0)
    }

    /**
     * 计算两组姿态序列的相似度（参考 PracticeScorer.calculateSimilarity）
     * 使用几何平均和惩罚机制，让差异大的动作明显拉低总体评分
     */
    private fun calculateSimilarity(
        recorded: List<List<List<Float>>>,
        reference: List<List<List<Float>>>
    ): Double {
        try {
            // 获取较短和较长的列表
            val minSize = minOf(recorded.size, reference.size)
            val maxSize = maxOf(recorded.size, reference.size)

            if (minSize == 0) return 0.0

            // 计算每一对姿态的相似度，使用较短列表的长度
            val similarities = recorded.take(minSize).zip(reference.take(minSize)).map { (rec, ref) ->
                comparePoses(rec, ref)
            }

            // 策略1: 计算算术平均（基础分数，用于对比）
            val arithmeticMean = similarities.average()
            
            // 策略2: 计算几何平均（对低值更敏感，差异大的帧会显著拉低分数）
            val geometricMean = if (similarities.all { it > 0.0 }) {
                similarities.fold(1.0) { acc, sim -> acc * sim }.pow(1.0 / similarities.size)
            } else {
                0.0  // 如果有0值，几何平均为0
            }
            
            // 策略3: 计算调和平均（对低值最敏感，比几何平均更严格）
            val harmonicMean = if (similarities.all { it > 0.0 }) {
                val sumReciprocal = similarities.sumOf { 1.0 / it }
                similarities.size / sumReciprocal
            } else {
                0.0
            }
            
            // 策略4: 找到最差帧（最小相似度）
            val minSimilarity = similarities.minOrNull() ?: 0.0
            
            // 策略5: 计算加权平均，对低分帧给予更高权重（使用立方惩罚，更激进）
            // 对低分帧给予更高权重，让差异大的帧更大地影响最终分数
            val weightedPairs = similarities.map { sim ->
                val diff = 1.0 - sim
                // 使用立方惩罚，差异越大，权重增长越快
                val weight = 1.0 + diff * diff * diff * 5.0  // 立方惩罚，系数更大
                Pair(sim, weight)
            }
            val totalWeight = weightedPairs.sumOf { it.second }
            val weightedSum = weightedPairs.sumOf { (sim, weight) -> sim * weight }
            val weightedMean = if (totalWeight > 0.0) weightedSum / totalWeight else 0.0
            
            // 策略6: 计算低分帧的惩罚分数（使用平方和立方组合）
            val lowScorePenalty = similarities.map { sim ->
                val diff = 1.0 - sim
                // 对低分使用立方惩罚
                diff * diff * diff
            }.average()
            
            // 组合策略：使用调和平均、几何平均和最差帧的组合，让差异大的动作明显拉低分数
            // 调和平均权重0.4（最敏感），几何平均权重0.3，最差帧权重0.2，加权平均权重0.1
            val combinedScore = harmonicMean * 0.4 + geometricMean * 0.3 + minSimilarity * 0.2 + weightedMean * 0.1
            
            // 应用低分惩罚：如果有低分帧，进一步降低分数
            val penaltyScore = combinedScore * (1.0 - lowScorePenalty * 0.3)
            
            // 如果算术平均和组合分数差异很大，说明有差异很大的帧，进一步惩罚
            // 降低阈值，让更小的差异也能触发惩罚
            val penaltyFactor = when {
                arithmeticMean - penaltyScore > 0.15 -> 0.7  // 差异>0.15，降低30%
                arithmeticMean - penaltyScore > 0.10 -> 0.8  // 差异>0.10，降低20%
                arithmeticMean - penaltyScore > 0.05 -> 0.9  // 差异>0.05，降低10%
                else -> 1.0
            }
            
            return (penaltyScore * penaltyFactor).coerceIn(0.0, 1.0)*minSize/maxSize

        } catch (e: Exception) {
            Log.e(TAG, "计算相似度失败: ${e.message}", e)
            return 0.0
        }
    }

    /**
     * 比较两个姿态的相似度（参考 PracticeScorer.comparePoses）
     */
     // 【未采集点的填补】
    private fun comparePoses(pose1: List<List<Float>>, pose2: List<List<Float>>): Double {
        // 如果任一姿态未检测到关键点，返回0
        if (pose1.isEmpty() || pose2.isEmpty()) {
            return 0.0
        }

        // 确保关键点数量相同
        if (pose1.size != pose2.size) {
            return 0.0
        }

        // 归一化关键点（相对于躯干中心）
        val normalized1 = normalizeLandmarks(pose1)
        val normalized2 = normalizeLandmarks(pose2)

        // 计算每个关键点的相似度
        var totalSimilarity = 0.0
        var totalWeight = 0.0

        for (i in normalized1.indices) {
            val weight = LANDMARK_WEIGHTS.getOrElse(i) { 1.0f }
            val distance = calculateDistance(normalized1[i], normalized2[i])

            // 对距离使用立方惩罚，让大差异的惩罚更严重
            // 相似度 = 1 - distance^3，这样距离越大，相似度下降越快
            val cubedDistance = distance * distance * distance
            // 使用更激进的惩罚：距离越大，相似度下降更快
            val similarity = max(1.0 - cubedDistance * 1.5, 0.0).coerceIn(0.0, 1.0)

            totalSimilarity += similarity * weight
            totalWeight += weight
        }

        return if (totalWeight > 0) totalSimilarity / totalWeight else 0.0
    }

    /**
     * 归一化关键点（相对于躯干中心和尺度）（参考 PracticeScorer.normalizeLandmarks）
     * 输入：List<List<Float>> - 每个关键点是 [x, y, z]
     * 输出：List<Triple<Float, Float, Float>> - 归一化后的关键点（躯干长度为1）
     */
     // 【涉及未检测点的填补】
    private fun normalizeLandmarks(landmarks: List<List<Float>>): List<Triple<Float, Float, Float>> {
        // 计算躯干中心（肩膀和臀部的中点）
        val leftShoulder = landmarks.getOrNull(11)  // 索引11
        val rightShoulder = landmarks.getOrNull(12) // 索引12
        val leftHip = landmarks.getOrNull(23)       // 索引23
        val rightHip = landmarks.getOrNull(24)      // 索引24

        // 无效帧的默认返回值（33 个关键点全部为 0）
        val invalidFrame = List(landmarks.size) { Triple(0f, 0f, 0f) }

        // 判断关键点是否有效（坐标在 0-1 范围、且不在原点附近、包含 z）
        fun isValidCorePoint(point: List<Float>?): Boolean {
            if (point == null || point.size < 3) return false
            val x = point[0]
            val y = point[1]
            val z = point[2]
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return false
            // 如果所有坐标都接近 0，视为无效（通常代表未检测到）
            if (abs(x) < 0.01f && abs(y) < 0.01f && abs(z) < 0.01f) return false
            // 同时避免极端异常值（超过典型归一化范围），防止放大噪声
            if (abs(x) > 5f || abs(y) > 5f || abs(z) > 5f) return false
            return true
        }

        // 如果核心关键点无效，则直接返回全 0，避免缩放异常
        if (!isValidCorePoint(leftShoulder) ||
            !isValidCorePoint(rightShoulder) ||
            !isValidCorePoint(leftHip) ||
            !isValidCorePoint(rightHip)
        ) {
            return invalidFrame
        }

        // 安全获取坐标值
        fun getCoord(point: List<Float>?, index: Int): Float {
            return point?.getOrNull(index) ?: 0f
        }

        fun toTriple(point: List<Float>?): Triple<Float, Float, Float> {
            val x = getCoord(point, 0)
            val y = getCoord(point, 1)
            val z = getCoord(point, 2)
            return Triple(x, y, z)
        }

        // 计算2D欧氏距离（只使用x和y轴，忽略z轴）
        fun euclideanDistance2D(p1: Triple<Float, Float, Float>, p2: Triple<Float, Float, Float>): Float {
            val dx = p1.first - p2.first
            val dy = p1.second - p2.second
            // 忽略z轴：不计算dz
            return sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        }

        // 固定躯干高度值（用于尺度对齐）
        val TARGET_TORSO_HEIGHT = 1.0f

        // 计算中心点和尺度
        val centerX: Float
        val centerY: Float
        val centerZ: Float
        val scale: Float

        // 获取肩膀和髋部的坐标
        val leftShoulderTriple = toTriple(leftShoulder)
        val rightShoulderTriple = toTriple(rightShoulder)
        val leftHipTriple = toTriple(leftHip)
        val rightHipTriple = toTriple(rightHip)

        // 计算肩膀中点和髋部中点
        fun midpoint(a: Triple<Float, Float, Float>, b: Triple<Float, Float, Float>): Triple<Float, Float, Float> {
            return Triple(
                (a.first + b.first) / 2f,
                (a.second + b.second) / 2f,
                (a.third + b.third) / 2f
            )
        }

        val shoulderMid = midpoint(leftShoulderTriple, rightShoulderTriple)
        val hipMid = midpoint(leftHipTriple, rightHipTriple)

        // 计算躯干中心（肩膀和髋部中点的中点）
        centerX = (shoulderMid.first + hipMid.first) / 2f
        centerY = (shoulderMid.second + hipMid.second) / 2f
        centerZ = (shoulderMid.third + hipMid.third) / 2f

        // 计算2D空间中肩到胯的距离（躯干高度，只使用x和y轴，忽略z轴）
        val torsoHeight2D = euclideanDistance2D(shoulderMid, hipMid)

        // 如果躯干高度太小，直接返回无效帧，避免缩放异常
        if (torsoHeight2D < 0.01f) {
            return invalidFrame
        }

        // 将躯干高度缩放到固定值TARGET_TORSO_HEIGHT 【尺度标准化】
        // 归一化后：torsoHeight2D / scale = TARGET_TORSO_HEIGHT
        // 所以：scale = torsoHeight2D / TARGET_TORSO_HEIGHT
        scale = torsoHeight2D / TARGET_TORSO_HEIGHT

        // 归一化所有关键点（保持躯干长度为1）【尺度标准化，只使用x和y轴】
        val normalizedPoints = landmarks.map { lm ->
            val x = getCoord(lm, 0)
            val y = getCoord(lm, 1)
            val z = getCoord(lm, 2)
            // 对x和y进行平移和缩放，z只进行平移（不缩放）
            Triple(
                (x - centerX) / scale,
                (y - centerY) / scale,
                z - centerZ  // z轴不参与缩放，只平移
            )
        }

        // 再次检查躯干高度，确保所有帧的躯干高度严格等于 TARGET_TORSO_HEIGHT（只使用x和y轴）
        val normalizedShoulderMid = midpoint(
            normalizedPoints[11],
            normalizedPoints[12]
        )
        val normalizedHipMid = midpoint(
            normalizedPoints[23],
            normalizedPoints[24]
        )
        val normalizedTorsoHeight = euclideanDistance2D(normalizedShoulderMid, normalizedHipMid)

        if (!normalizedTorsoHeight.isFinite() || normalizedTorsoHeight < 0.0001f) {
            return invalidFrame
        }

        // 如果归一化后的躯干高度不等于1，进行二次校正（只校正x和y轴）
        val correction = TARGET_TORSO_HEIGHT / normalizedTorsoHeight
        return normalizedPoints.map { point ->
            Triple(
                point.first * correction,  // x轴校正
                point.second * correction, // y轴校正
                point.third                // z轴不校正
            )
        }
    }

    /**
     * 计算两个点之间的2D欧氏距离（只使用x和y轴，忽略z轴）
     */
    private fun calculateDistance(p1: Triple<Float, Float, Float>, p2: Triple<Float, Float, Float>): Double {
        val dx = p1.first - p2.first
        val dy = p1.second - p2.second
        // 忽略z轴：不计算dz
        return sqrt((dx * dx + dy * dy).toDouble())
    }

    // 读取标准姿势序列：尝试以演示视频资源名为基名，依次尝试几种命名
    private fun readStandardPoseSequence(): List<List<List<Float>>>? {
        val meta = exerciseMeta ?: return null
        val baseName = try {
            resources.getResourceEntryName(meta.demoVideoRes)
        } catch (e: Exception) {
            null
        } ?: return null

        // 仅按约定：视频原名，JSON 加后缀 _json
        val candidateNames = listOf("${baseName}_json")

        val gson = Gson()
        val type = object : TypeToken<List<List<List<Float>>>>() {}.type

        for (name in candidateNames) {
            val temp = name
            try {
                val resId = resources.getIdentifier(name, "raw", packageName)
                if (resId == 0) continue
                resources.openRawResource(resId).use { input ->
                    InputStreamReader(input).use { reader ->
                        val parsed: List<List<List<Float>>>? = gson.fromJson(reader, type)
                        if (parsed != null && parsed.isNotEmpty()) return parsed
                    }
                }
            } catch (_: Exception) {
                // 尝试下一个候选名
            }
        }

        return null
    }







    // （3）初始化缓存

    private val postureResultsCache = mutableListOf<List<List<Float>>>()
    // （4）实时分析函数

    // **************************************************************************************************
    // 【动作提取】
    private fun createImageAnalyzer(): ImageAnalysis.Analyzer {
        var frameCount = 0  // 用于计数已经处理的帧数

        return object : Analyzer {
            override fun analyze(image: ImageProxy) {
                try {
                    // 每隔6帧执行一次处理
                    if (frameCount % 9 == 0) {
                        // 调用姿势识别函数处理当前帧
                        val result = processFrameForPostureRecognition(image)

                        // 将识别结果添加到缓存
                        postureResultsCache.add(result)
                    }

                    // 增加帧计数
                    frameCount++

                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    image.close()
                }
            }
        }
    }

    // (5) 用于控制图像实时分析  // 这个函数目前应该用不上了
    private var imageAnalysisUseCase: ImageAnalysis? = null

    // (6) 核心提取函数
    private fun startRecording(meta: PracticeExerciseMeta) {
        val videoCapture = videoCapture ?: return
        // (1).指定视频保存的目录
        val recordingsDir = File(
            getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "practice_sessions"
        ).apply { if (!exists()) mkdirs() }
        // (2).根据当前的时间生成唯一的视频文件名
        val fileName = "session_${meta.id}_" + SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.getDefault()
        ).format(Date()) + ".mp4"
        // (3) 使用上述目录和文件名创建File对象，用于存储视频
        val outputFile = File(recordingsDir, fileName)
        currentVideoFile = outputFile

        // (4) 指定视频输出的文件
        val outputOptions = FileOutputOptions.Builder(outputFile).build()

        val pending = videoCapture.output.prepareRecording(this, outputOptions).apply {
            if (hasAudioPermission()) {
                withAudioEnabled()
            }
        }
        // (5) 启动录制
        activeRecording = pending.start(cameraExecutor) { event ->
            // 检查 Activity 是否还存活，避免在销毁后操作 UI
            if (isFinishing || isDestroyed) {
                return@start
            }
            // 控制录制的部分 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
            // 当视频录制开始时，VideoRecordEvent.Start 被触发
            try {
                when (event) {
                    // （5.1）视频录制开始
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

                        // 启动图像分析
                        startImageAnalysis()  // 启动实时姿势分析 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

                    }
                    // （5.2）视频录制结束
                    is VideoRecordEvent.Finalize -> {
                        // 控制 UI
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
                        stopImageAnalysis() // 停止图像分析 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                        activeRecording = null
                        // 检查Activity是否存活，评估录制
                        if (!event.hasError()) {
                            currentVideoFile?.let { file ->
                                // 只在Activity存活时才评估录制
                                if (!isFinishing && !isDestroyed) {
                                    // 调用临时评分函数
                                    val score = calculateScore(postureResultsCache)

                                    // 传递分数到 ViewModel
                                    viewModel.evaluateRecording(meta, file.absolutePath, score)
                                    // 清空缓存，便于下一次会话重新开始
                                    try { postureResultsCache.clear() } catch (_: Exception) {}
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

                        // 录制结束，停止实时姿势分析

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

    // (7) 开始实时姿势提取
    private fun startImageAnalysis() {
        try {
            // 开始新的分析会话时，重置 PoseLandmarker 和时间戳
            resetPoseLandmarker()
            ensureInitialized()
            
            // 配置ImageAnalysis
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // 处理最新的图像
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            // 设置图像分析器
            val imageAnalyzer = createImageAnalyzer()
            imageAnalysis.setAnalyzer(cameraExecutor, imageAnalyzer)

            // 将图像分析器与CameraX绑定
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT) // 使用前置摄像头
                .build()

            // 确保相机提供者存在并且可以绑定
            cameraProvider?.let { provider ->
                provider.bindToLifecycle(this, cameraSelector, imageAnalysis)
                imageAnalysisUseCase = imageAnalysis
                Log.d("ImageAnalysis", "Image analysis started successfully.")
            } ?: run {
                Log.e("ImageAnalysis", "Camera provider is null.")
            }
        } catch (e: Exception) {
            Log.e("ImageAnalysis", "Failed to start image analysis: ${e.message}", e)
        }
    }

    // （8）停止实时姿势分析
    private fun stopImageAnalysis() {
        try {
            imageAnalysisUseCase?.let { useCase ->
                // 如果已经绑定了ImageAnalysis，解除绑定
                cameraProvider?.unbind(useCase)
                imageAnalysisUseCase = null
                savePostureResultsToJsonFile(this, postureResultsCache)
                // // 清空缓存，便于下一次会话重新开始
                // try { postureResultsCache.clear() } catch (_: Exception) {}
                Log.d("ImageAnalysis", "Image analysis stopped successfully.")
            } ?: run {
                Log.w("ImageAnalysis", "No image analysis use case to stop.")
            }
        } catch (e: Exception) {
            Log.e("ImageAnalysis", "Failed to stop image analysis: ${e.message}", e)
            val x=1
        }
    }

    // (9) 存储提取结果
    // 将 MutableList<List<List<Float>>> 存储为 JSON 文件
    fun savePostureResultsToJsonFile(context: Context, postureResultsCache: MutableList<List<List<Float>>>) {
        val gson = Gson()

        // 将 postureResultsCache 转换为 JSON 字符串
        val jsonString = gson.toJson(postureResultsCache)

        try {
            // 判断当前 Android 版本
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10 及以上版本使用 MediaStore 保存文件
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "posture_results.json")  // 文件名
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")  // MIME 类型
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)  // 保存到 Downloads 文件夹
                }

                // 获取 ContentResolver
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)

                uri?.let {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        OutputStreamWriter(outputStream).use { writer ->
                            writer.write(jsonString) // 将 JSON 字符串写入文件
                        }
                    }
                }
            } else {
                // 对于 Android 9 及以下，直接写入文件系统
                val downloadDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "posture_results.json")
                downloadDir.writeText(jsonString)
            }
        } catch (e: Exception) {
            e.printStackTrace() // 捕获并打印异常
        }
    }

    /**
     * 保存归一化后的姿势序列到 JSON 文件
     * @param context Context 上下文
     * @param normalizedPoses List<List<Triple<Float, Float, Float>>> 归一化后的姿势序列
     * @param fileName String 文件名
     */
    private fun saveNormalizedPosesToJsonFile(
        context: Context,
        normalizedPoses: List<List<Triple<Float, Float, Float>>>,
        fileName: String
    ) {
        val gson = Gson()

        // 将 Triple<Float, Float, Float> 转换为 List<Float> 以便序列化
        val serializableData: List<List<List<Float>>> = normalizedPoses.map { pose ->
            pose.map { triple ->
                listOf(triple.first, triple.second, triple.third)
            }
        }

        // 将数据转换为 JSON 字符串
        val jsonString = gson.toJson(serializableData)

        try {
            // 判断当前 Android 版本
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10 及以上版本使用 MediaStore 保存文件
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)  // 文件名
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")  // MIME 类型
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)  // 保存到 Downloads 文件夹
                }

                // 获取 ContentResolver
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)

                uri?.let {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        OutputStreamWriter(outputStream).use { writer ->
                            writer.write(jsonString) // 将 JSON 字符串写入文件
                        }
                    }
                    Log.d(TAG, "已保存归一化姿势序列到: $fileName")
                } ?: run {
                    Log.e(TAG, "无法创建文件: $fileName")
                }
            } else {
                // 对于 Android 9 及以下，直接写入文件系统
                val downloadDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                downloadDir.writeText(jsonString)
                Log.d(TAG, "已保存归一化姿势序列到: ${downloadDir.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存归一化姿势序列失败: ${e.message}", e)
            e.printStackTrace()
        }
    }



    // 根据按钮结束录制
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
        private const val TAG = "PracticeActivity"

        // MediaPipe参数
        private const val DEFAULT_POSE_DETECTION_CONFIDENCE = 0.5f
        private const val DEFAULT_POSE_TRACKING_CONFIDENCE = 0.5f
        private const val DEFAULT_POSE_PRESENCE_CONFIDENCE = 0.5f

        // MediaPipe Pose关键点索引常量
        private const val LEFT_SHOULDER_INDEX = 11
        private const val RIGHT_SHOULDER_INDEX = 12
        private const val EXPECTED_KEYPOINT_COUNT = 33  // MediaPipe Pose标准关键点数量

        // 关键点权重（某些关键点更重要，参考 PracticeScorer）
        private val LANDMARK_WEIGHTS = mapOf(
            // 鼻子
            0 to 2.0f,
            // 眼睛
            1 to 0.1f, 2 to 0.1f, 3 to 0.1f, 4 to 0.1f,
            // 耳朵
            7 to 2f, 8 to 2f,
            // 嘴巴
            9 to 0.1f, 10 to 0.1f,
            // 肩膀（重要）
            11 to 0.1f, 12 to 0.1f,
            // 手肘（重要）
            13 to 3f, 14 to 3f,
            // 手腕（重要）
            15 to 3f, 16 to 3f,
            // 手指
            17 to 1f, 18 to 1f, 19 to 1f, 20 to 1f, 21 to 1f, 22 to 1f,
            // 臀部（重要）
            23 to 0.1f, 24 to 0.1f,
            // 膝盖（重要）
            25 to 3f, 26 to 3f,
            // 脚踝（重要）
            27 to 3f, 28 to 3f,
            // 脚
            29 to 0.1f, 30 to 0.1f, 31 to 0.1f, 32 to 0.1f
        )
    }
}




