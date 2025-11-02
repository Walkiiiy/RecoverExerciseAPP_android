package com.walkiiiy.recover.data.scoring

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlin.math.sqrt

/**
 * PracticeScorer - 用于评估练习视频与参考视频的相似度
 * 使用MediaPipe Pose Landmarker进行姿态检测和比较
 */
class PracticeScorer(private val context: Context) {

    private var poseLandmarker: PoseLandmarker? = null
    private var isInitialized = false
    private var initializationError: Throwable? = null

    /**
     * 重置PoseLandmarker（用于处理新视频）
     */
    private fun resetPoseLandmarker() {
        try {
            poseLandmarker?.close()
            poseLandmarker = null
            isInitialized = false
            initializationError = null
            Log.d(TAG, "PoseLandmarker已重置")
        } catch (e: Exception) {
            Log.w(TAG, "重置PoseLandmarker时出错: ${e.message}")
        }
    }
    
    /**
     * 延迟初始化PoseLandmarker（在第一次使用时初始化）
     */
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
                .setRunningMode(RunningMode.VIDEO)
                .build()

            poseLandmarker = PoseLandmarker.createFromOptions(context, options)
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

    /**
     * 评分主方法：比较录制视频和参考视频的相似度
     * @param recordedUri 录制的视频URI
     * @param referenceUri 参考视频URI
     * @return 相似度分数 (0-100)
     */
    fun score(recordedUri: Uri, referenceUri: Uri): Double {
        // 延迟初始化：只在第一次调用score时初始化
        ensureInitialized()
        
        try {
            Log.d(TAG, "开始评分: recorded=$recordedUri, reference=$referenceUri")

            // 提取两个视频的姿态关键点
            val recordedPoses = extractPosesFromVideo(recordedUri)
            val referencePoses = extractPosesFromVideo(referenceUri)

            if (recordedPoses.isEmpty()) {
                throw RuntimeException("无法从录制视频中检测到姿态")
            }
            if (referencePoses.isEmpty()) {
                throw RuntimeException("无法从参考视频中检测到姿态")
            }

            Log.d(TAG, "提取的姿态帧数: recorded=${recordedPoses.size}, reference=${referencePoses.size}")

            // 计算相似度
            val similarity = calculateSimilarity(recordedPoses, referencePoses)
            val score = similarity * 100.0

            Log.d(TAG, "评分完成: $score")
            return score.coerceIn(0.0, 100.0)

        } catch (e: Exception) {
            Log.e(TAG, "评分失败: ${e.message}", e)
            throw RuntimeException("评分过程出错: ${e.message}", e)
        }
    }

    /**
     * 从视频中提取姿态关键点
     */
    private fun extractPosesFromVideo(videoUri: Uri): List<PoseLandmarkerResult> {
        // 每次处理新视频时重新创建PoseLandmarker以重置状态
        resetPoseLandmarker()
        ensureInitialized()
        
        val retriever = MediaMetadataRetriever()
        val resultList = mutableListOf<PoseLandmarkerResult>()

        try {
            Log.d(TAG, "开始提取视频姿态: $videoUri")
            
            // 设置视频数据源
            try {
                retriever.setDataSource(context, videoUri)
            } catch (e: Exception) {
                Log.e(TAG, "无法打开视频文件", e)
                throw RuntimeException("无法打开视频文件，请检查文件是否存在且格式正确")
            }
            
            // 读取视频长度
            val videoLengthMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLong()
            if (videoLengthMs == null || videoLengthMs <= 0) {
                throw RuntimeException("视频长度无效，可能文件已损坏")
            }

            // 读取首帧
            val firstFrame = retriever.getFrameAtTime(0)
            if (firstFrame == null) {
                throw RuntimeException("无法读取视频首帧，视频可能已损坏或格式不支持")
            }

            val width = firstFrame.width
            val height = firstFrame.height

            Log.d(TAG, "视频信息: length=${videoLengthMs}ms, size=${width}x${height}")

            // 每隔INFERENCE_INTERVAL_MS提取一帧进行分析
            val numberOfFrameToRead = videoLengthMs.div(INFERENCE_INTERVAL_MS)
            Log.d(TAG, "计划提取 $numberOfFrameToRead 帧进行分析")

            var successfulFrames = 0
            var failedFrames = 0

            for (i in 0..numberOfFrameToRead) {
                val videoTimestampMs = i * INFERENCE_INTERVAL_MS
                // MediaPipe要求时间戳必须单调递增，使用帧索引计算
                val mediapipeTimestampMs = i.toLong()

                try {
                    retriever.getFrameAtTime(
                        videoTimestampMs * 1000, // 转换为微秒，用于从视频提取帧
                        MediaMetadataRetriever.OPTION_CLOSEST
                    )?.let { frame ->
                        // 确保格式为ARGB_8888
                        val argb8888Frame = if (frame.config == Bitmap.Config.ARGB_8888) {
                            frame
                        } else {
                            frame.copy(Bitmap.Config.ARGB_8888, false)
                        }

                        // 转换为MPImage
                        val mpImage = BitmapImageBuilder(argb8888Frame).build()

                        // 进行姿态检测 - 使用单调递增的时间戳
                        poseLandmarker?.detectForVideo(mpImage, mediapipeTimestampMs)?.let { result ->
                            // 只保留检测到姿态的帧
                            if (result.landmarks().isNotEmpty()) {
                                resultList.add(result)
                                successfulFrames++
                            } else {
                                failedFrames++
                                Log.d(TAG, "第 $i 帧未检测到姿态")
                            }
                        } ?: run {
                            failedFrames++
                            Log.w(TAG, "第 $i 帧姿态检测返回null")
                        }
                    } ?: run {
                        failedFrames++
                        Log.w(TAG, "无法读取第 $i 帧 (时间: ${videoTimestampMs}ms)")
                    }
                } catch (e: Exception) {
                    failedFrames++
                    Log.w(TAG, "处理第 $i 帧时出错: ${e.message}")
                }
            }

            Log.d(TAG, "姿态提取完成: 成功=${successfulFrames}, 失败=${failedFrames}, 总计=${resultList.size}")

            // 如果没有检测到任何姿态
            if (resultList.isEmpty()) {
                throw RuntimeException("未能从视频中检测到任何人体姿态\n可能原因：\n1. 视频中没有完整的人体\n2. 光线太暗\n3. 人物太小或被遮挡")
            }

            // 如果检测到的帧数太少
            if (resultList.size < 3) {
                Log.w(TAG, "检测到的姿态帧数较少: ${resultList.size}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "提取视频姿态失败: ${e.message}", e)
            if (e is RuntimeException && e.message?.contains("检测") == true) {
                // 已经是我们自定义的错误信息，直接抛出
                throw e
            } else {
                // 包装其他异常
                throw RuntimeException("视频处理失败: ${e.message}", e)
            }
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.e(TAG, "释放MediaMetadataRetriever失败", e)
            }
        }

        return resultList
    }

    /**
     * 计算两组姿态序列的相似度
     */
    private fun calculateSimilarity(
        recorded: List<PoseLandmarkerResult>,
        reference: List<PoseLandmarkerResult>
    ): Double {
        try {
            // 使用动态时间规整(DTW)算法对齐两个序列
            val alignedPairs = alignSequences(recorded, reference)

            if (alignedPairs.isEmpty()) {
                return 0.0
            }

            // 计算每一对姿态的相似度
            val similarities = alignedPairs.map { (rec, ref) ->
                comparePoses(rec, ref)
            }

            // 返回平均相似度
            return similarities.average()

        } catch (e: Exception) {
            Log.e(TAG, "计算相似度失败: ${e.message}", e)
            return 0.0
        }
    }

    /**
     * 对齐两个序列（简化版DTW）
     */
    private fun alignSequences(
        recorded: List<PoseLandmarkerResult>,
        reference: List<PoseLandmarkerResult>
    ): List<Pair<PoseLandmarkerResult, PoseLandmarkerResult>> {
        val pairs = mutableListOf<Pair<PoseLandmarkerResult, PoseLandmarkerResult>>()

        // 如果帧数相近，直接一对一匹配
        if (recorded.size == reference.size) {
            for (i in recorded.indices) {
                pairs.add(recorded[i] to reference[i])
            }
        } else {
            // 否则根据时间比例进行采样对齐
            val ratio = reference.size.toDouble() / recorded.size.toDouble()
            for (i in recorded.indices) {
                val refIndex = (i * ratio).toInt().coerceIn(0, reference.size - 1)
                pairs.add(recorded[i] to reference[refIndex])
            }
        }

        return pairs
    }

    /**
     * 比较两个姿态的相似度
     */
    private fun comparePoses(pose1: PoseLandmarkerResult, pose2: PoseLandmarkerResult): Double {
        // 如果任一姿态未检测到关键点，返回0
        if (pose1.landmarks().isEmpty() || pose2.landmarks().isEmpty()) {
            return 0.0
        }

        val landmarks1 = pose1.landmarks()[0]
        val landmarks2 = pose2.landmarks()[0]

        // 确保关键点数量相同
        if (landmarks1.size != landmarks2.size) {
            return 0.0
        }

        // 归一化关键点（相对于躯干中心）
        val normalized1 = normalizeLandmarks(landmarks1)
        val normalized2 = normalizeLandmarks(landmarks2)

        // 计算每个关键点的相似度
        var totalSimilarity = 0.0
        var totalWeight = 0.0

        for (i in normalized1.indices) {
            val weight = LANDMARK_WEIGHTS.getOrElse(i) { 1.0f }
            val distance = calculateDistance(normalized1[i], normalized2[i])

            // 将距离转换为相似度 (距离越小，相似度越高)
            val similarity = 1.0 / (1.0 + distance)

            totalSimilarity += similarity * weight
            totalWeight += weight
        }

        return if (totalWeight > 0) totalSimilarity / totalWeight else 0.0
    }

    /**
     * 归一化关键点（相对于躯干中心和尺度）
     */
    private fun normalizeLandmarks(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): List<Triple<Float, Float, Float>> {
        // 计算躯干中心（肩膀和臀部的中点）
        val leftShoulder = landmarks.getOrNull(11)
        val rightShoulder = landmarks.getOrNull(12)
        val leftHip = landmarks.getOrNull(23)
        val rightHip = landmarks.getOrNull(24)

        if (leftShoulder == null || rightShoulder == null || leftHip == null || rightHip == null) {
            // 如果关键点不完整，使用所有点的中心
            val centerX = landmarks.map { it.x() }.average().toFloat()
            val centerY = landmarks.map { it.y() }.average().toFloat()
            val scale = 1.0f

            return landmarks.map { lm ->
                Triple(
                    (lm.x() - centerX) / scale,
                    (lm.y() - centerY) / scale,
                    lm.z()
                )
            }
        }

        // 计算中心点
        val centerX = (leftShoulder.x() + rightShoulder.x() + leftHip.x() + rightHip.x()) / 4f
        val centerY = (leftShoulder.y() + rightShoulder.y() + leftHip.y() + rightHip.y()) / 4f

        // 计算躯干高度作为尺度
        val shoulderMidY = (leftShoulder.y() + rightShoulder.y()) / 2f
        val hipMidY = (leftHip.y() + rightHip.y()) / 2f
        val torsoHeight = Math.abs(shoulderMidY - hipMidY)
        val scale = if (torsoHeight > 0.01f) torsoHeight else 1.0f

        // 归一化所有关键点
        return landmarks.map { lm ->
            Triple(
                (lm.x() - centerX) / scale,
                (lm.y() - centerY) / scale,
                lm.z() / scale
            )
        }
    }

    /**
     * 计算两个3D点之间的欧氏距离
     */
    private fun calculateDistance(p1: Triple<Float, Float, Float>, p2: Triple<Float, Float, Float>): Double {
        val dx = p1.first - p2.first
        val dy = p1.second - p2.second
        val dz = p1.third - p2.third
        return sqrt((dx * dx + dy * dy + dz * dz).toDouble())
    }

    /**
     * 清理资源
     */
    fun close() {
        try {
            poseLandmarker?.close()
            poseLandmarker = null
            Log.d(TAG, "PoseLandmarker closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing PoseLandmarker: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "PracticeScorer"

        // MediaPipe参数
        private const val DEFAULT_POSE_DETECTION_CONFIDENCE = 0.5f
        private const val DEFAULT_POSE_TRACKING_CONFIDENCE = 0.5f
        private const val DEFAULT_POSE_PRESENCE_CONFIDENCE = 0.5f

        // 视频处理参数
        private const val INFERENCE_INTERVAL_MS = 300L  // 每300ms提取一帧

        // 关键点权重（某些关键点更重要）
        private val LANDMARK_WEIGHTS = mapOf(
            // 鼻子
            0 to 0.5f,
            // 眼睛
            1 to 0.3f, 2 to 0.3f, 3 to 0.3f, 4 to 0.3f,
            // 耳朵
            7 to 0.3f, 8 to 0.3f,
            // 嘴巴
            9 to 0.3f, 10 to 0.3f,
            // 肩膀（重要）
            11 to 1.5f, 12 to 1.5f,
            // 手肘（重要）
            13 to 1.5f, 14 to 1.5f,
            // 手腕（重要）
            15 to 1.3f, 16 to 1.3f,
            // 手指
            17 to 0.8f, 18 to 0.8f, 19 to 0.8f, 20 to 0.8f, 21 to 0.8f, 22 to 0.8f,
            // 臀部（重要）
            23 to 1.5f, 24 to 1.5f,
            // 膝盖（重要）
            25 to 1.5f, 26 to 1.5f,
            // 脚踝（重要）
            27 to 1.3f, 28 to 1.3f,
            // 脚
            29 to 0.8f, 30 to 0.8f, 31 to 0.8f, 32 to 0.8f
        )

        /**
         * 将raw资源转换为Uri
         */
        fun rawToUri(context: Context, rawResId: Int): Uri {
            return Uri.parse("android.resource://${context.packageName}/$rawResId")
        }
    }
}

