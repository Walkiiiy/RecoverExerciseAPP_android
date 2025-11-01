package com.walkiiiy.recover.ui.practice

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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

        setupToolbar()
        setupButtons()
        setupObservers()
        setupDemoVideo()
        startCamera()
    }

    override fun onDestroy() {
        activeRecording?.stop()
        super.onDestroy()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.practiceToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.practiceToolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
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
        }
    }

    private fun setupDemoVideo() {
        val meta = exerciseMeta ?: return
        val videoUri = Uri.parse("android.resource://$packageName/${meta.demoVideoRes}")
        binding.demoVideoView.setVideoURI(videoUri)
        binding.demoVideoView.setOnPreparedListener { mediaPlayer ->
            mediaPlayer.isLooping = false
        }
        binding.demoVideoView.setOnCompletionListener {
            if (viewModel.uiState.value?.isRecording == true) {
                stopRecording()
            }
        }
    }

    private fun startPracticeInternal() {
        val meta = exerciseMeta ?: return
        binding.demoVideoView.start()
        startRecording(meta)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
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

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    videoCapture
                )
            } catch (exc: Exception) {
                Toast.makeText(this, "无法启动相机", Toast.LENGTH_SHORT).show()
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
            when (event) {
                is VideoRecordEvent.Start -> {
                    viewModel.setRecording(true)
                    binding.stopPracticeButton.isEnabled = true
                }

                is VideoRecordEvent.Finalize -> {
                    viewModel.setRecording(false)
                    binding.stopPracticeButton.isEnabled = false
                    activeRecording = null
                    if (!event.hasError()) {
                        currentVideoFile?.let { file ->
                            viewModel.evaluateRecording(meta, file.absolutePath)
                        }
                    } else {
                        currentVideoFile?.delete()
                        Toast.makeText(this, "录制失败，请重试", Toast.LENGTH_SHORT).show()
                    }
                    currentVideoFile = null
                }
            }
        }
    }

    private fun stopRecording() {
        binding.demoVideoView.pause()
        binding.demoVideoView.seekTo(0)
        activeRecording?.stop()
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
