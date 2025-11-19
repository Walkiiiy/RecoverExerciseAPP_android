//poselandmarker
//worldlandmark
package com.walkiiiy.recover.data.scoring

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker.PoseLandmarkerOptions
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.io.Closeable
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt


import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.io.IOException


/**
 * PosePracticeScorerMODEL_ASSET
 *
 * 使用 MediaPipe Pose Landmarker（视频模式）分别对【录制视频】与【参考视频】进行人体关键点检测，
 * 将每一帧的骨骼姿态编码为一组关节角度特征，随后将两段视频的时序特征进行时间对齐（均匀重采样），
 * 最终计算逐帧的余弦相似度并求平均，得到 0~100 的动作相似度分数。
 *
 * 依赖（Gradle）：
 * implementation("com.google.mediapipe:tasks-vision:0.202.0") // 或者当前最新版本
 *
 * 模型放置：
 * 将官方提供的 .task 模型文件（如 pose_landmarker_full.task）放到 app/src/main/assets/
 * 并在 BaseOptions 中通过 setModelAssetPath 指定。
 *
 * 注意：
 * - 该实现为了简单可用，采用 MediaMetadataRetriever 以固定间隔抽帧（位图），然后对每帧做一次 Pose 检测；
 *   对长视频或高分辨率视频可能较慢，建议在 IO/Default 调度器或后台线程执行。
 * - 若需要更高实时性，可改用解码管线 + Surface/Texture 结合 MediaPipe 的 GPU 路径。
 */

class PracticeScorer3(private val context: Context) : Closeable {

    // ************************** 可调参数区 **************************

    /** 采样的目标帧数（从每段视频中等间隔抽取），越大越精细但越慢。 */
    // 含义改成了每隔多少帧长取一帧
    private val targetFramesPerVideo: Int = 2

    /** 当某一视频可用的关键点帧数低于该阈值时，认为检测不足，直接返回较低的保守分。 */
    private val minValidFrames: Int = 12

    /** 余弦相似度 -> 分数 的线性映射强度，允许轻微拉伸区间。 */
    private val similarityWeight: Double = 1.0

    /** 模型资产文件名（放在 assets/ 目录） */
    private val poseModelAssetName: String = MODEL_ASSET

    // ***************************************************************

    private val poseLandmarker: PoseLandmarker by lazy { createPoseLandmarker() }

    /**
     * 对两段视频进行相似度评分。
     *
     * @param recordedUri   录制视频的 Uri（content:// 或 file:// 均可）
     * @param referenceUri  参考示范视频的 Uri（可以是 android.resource://<pkg>/<resId>）
     * @return 0.0 ~ 100.0 的相似度，数值越大越接近。
     */
    // 算分函数

    fun applyCustomMapping(distance: Double): Double {
        // 1. 归一化 distance 到 [0, 1] 范围
        val normalizedDistance = min(max(distance, 0.0), 1.0)

        // 2. 定义 Sigmoid 映射函数，k 为控制变化速度的参数
        val k = 10.0 // 可以调整 k 来改变 0.5 附近变化的速度
        val mappedDistance = 1 / (1 + Math.exp(-k * (normalizedDistance - 0.5)))

        return mappedDistance
    }

    fun score(recordedUri: Uri, referenceUri: Uri): Double {
        return try {
            val finalRecordedUri = recordedUri
            // 1) 为两段视频抽帧并获取每帧的姿态特征（关键点坐标）
            val recordedSeries = extractPoseFeatureSeries(finalRecordedUri, targetFramesPerVideo)
            val referenceSeries = extractPoseFeatureSeries(referenceUri, targetFramesPerVideo)

            // 2) 质量控制：若有效帧过少，直接返回保守分（避免不稳定结果）
            if (recordedSeries.isEmpty() || referenceSeries.isEmpty() ||
                recordedSeries.size < minValidFrames || referenceSeries.size < minValidFrames) {
                return 10.0 // 信息不足时的保守分
            }
            ////// ---------------------------------------------------------------------------------------------------
            // 3) 确定两个序列中的较短长度来计算相似度总和
            val targetLen = min(recordedSeries.size, referenceSeries.size)

            // 4) 逐帧计算欧式距离并求和
            var sum = 0.0
            var count = 0
            for (i in 0 until targetLen) {
                val recFrame = recordedSeries[i]  // 录制视频的当前帧
                val refFrame = referenceSeries[i] // 参考视频的当前帧

                // 对每一对对应的关键点计算欧式距离
                for (j in recFrame.indices) {
                    val a = recFrame[j] // 录制视频的某个关键点
                    val b = refFrame[j] // 参考视频的某个关键点

                    // 计算欧式距离
                    val distance = euclideanDistance(a, b)
                    val temp = (0.6 - applyCustomMapping(distance)) * 1.667
//                    val temp = min(1.0, max(1 / (0.618 + distance) - 0.618, 0.0))

                    sum += temp

                    count++
                }
            }

            if (count <= 0) return 10.0

            // 直接返回总和 sum 作为最终得分  //**********************************
            val finalScore = sum / max(recordedSeries.size, referenceSeries.size) / 0.33
            ////// ---------------------------------------------------------------------------------------------------

            // 返回最终的得分，不做限制
            finalScore
        } catch (e: Exception) {
            Log.w(TAG, "Scoring failed, returning fallback.", e)
            10.0
        }
    }


    // 欧氏距离计算函数
    fun euclideanDistance(a: Triple<Double, Double, Double>, b: Triple<Double, Double, Double>): Double {
        val dx = a.first - b.first
        val dy = a.second - b.second
        val dz = a.third - b.third
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * 使用 MediaMetadataRetriever 以等间隔抽帧，并用 PoseLandmarker 提取每帧的骨骼特征向量。
     * 特征为一组**关节角度**（单位：弧度），相较于直接使用坐标，对尺度与位移更鲁棒。
     */



    private fun extractPoseFeatureSeries(uri: Uri, frameInterval: Int): ArrayList<List<Triple<Double, Double, Double>>> {
        val retriever = MediaMetadataRetriever()
        val features = ArrayList<List<Triple<Double, Double, Double>>>()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(1L) ?: 1L

            // 获取视频的总时长（秒）
            val totalDurationSeconds = durationMs / 1000L
            val fps = 30
            val totalFrames = totalDurationSeconds * fps
            val maxFrames = totalFrames / frameInterval

            // 按照每 x 帧抽取
            for (i in 0 until maxFrames) {
                val tUs = (i * frameInterval * durationMs * 1000L) / totalFrames
                val bmp = retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                val fv = detectPoseAndToFeature(bmp) ?: continue
                features.add(fv)
                bmp.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractPoseFeatureSeries failed for $uri", e)
        } finally {
            retriever.release()
        }

        // 对提取的数据进行标准化
        val standardizedFeatures = standardizePoseData(features)

        // 将数据保存为 JSON 文件
        savePoseFeaturesToJsonFile(standardizedFeatures)

        return standardizedFeatures
    }

    /**
     * 将 Pose 特征数据保存到 JSON 文件。
     */
    private fun savePoseFeaturesToJsonFile(features: ArrayList<List<Triple<Double, Double, Double>>>) {
        // 将数据转换为 JSON 字符串
        val gson = Gson()
        val jsonString = gson.toJson(features)

        // 创建文件
        val file = File(context.getExternalFilesDir(null), "pose_features.json")

        try {
            // 写入 JSON 字符串到文件
            FileOutputStream(file).use { outputStream ->
                outputStream.write(jsonString.toByteArray())
                outputStream.flush()
            }
            Log.d(TAG, "JSON file saved to: ${file.absolutePath}")
        } catch (e: IOException) {
            Log.w(TAG, "Failed to save JSON file", e)
        }
    }



    /** 将一帧位图送入 PoseLandmarker，返回该帧的角度特征向量。 */

    private fun detectPoseAndToFeature(bitmap: Bitmap): List<Triple<Double, Double, Double>>? {
        // 构造 MPImage（Bitmap 包装器）
        val mpImage = BitmapImageBuilder(bitmap).build()

        // 运行姿态检测（单帧）
        val result: PoseLandmarkerResult = poseLandmarker.detect(mpImage)
        // 只取第一位人物（大多数训练/康复场景只有一个人）
        val landmarks = result.landmarks().firstOrNull() ?: return null
        val world = result.worldLandmarks().firstOrNull() // 如果可用，优先 worldLandmarks（米制、弱透视影响）

        // 提取各个关键点的坐标
        fun p(i: Int): Triple<Double, Double, Double> {
            return if (world != null && world.size > i) {
                val w = world[i]
                Triple(w.x().toDouble(), w.y().toDouble(), w.z().toDouble()) // 使用世界坐标（如果有）
            } else {
                val n = landmarks[i]
                // 归一化坐标系下的 (x,y) + 以 z 作为近似深度
                Triple(n.x().toDouble(), n.y().toDouble(), n.z().toDouble())
            }
        }

        // 你可以选择返回所有的 33 个关键点坐标
        val allLandmarks = List(landmarks.size) { i -> p(i) }

        // 返回关键点坐标列表
        return allLandmarks
    }




    // 平移位置标准化



    private fun normalizePoseData(features: ArrayList<MutableList<Triple<Double, Double, Double>>>): ArrayList<MutableList<Triple<Double, Double, Double>>> {
        // 获取一个参考点（例如，左髋和右髋的中点）
        features.forEach { frame ->
            val leftHip = frame[23]  // 假设第 23 个关键点是左髋
            val rightHip = frame[24] // 假设第 24 个关键点是右髋

            // 计算两髋的中点
            val centerX = (leftHip.first + rightHip.first) / 2
            val centerY = (leftHip.second + rightHip.second) / 2
            val centerZ = (leftHip.third + rightHip.third) / 2

            // 对每个关键点进行平移，使得两髋的中点成为原点
            frame.forEachIndexed { index, point ->
                val normalizedPoint = Triple(
                    point.first - centerX,  // 平移 x 坐标
                    point.second - centerY, // 平移 y 坐标
                    point.third - centerZ   // 平移 z 坐标
                )
                frame[index] = normalizedPoint  // 修改每个点的坐标
            }
        }
        return features
    }
    // 尺度标准化


    private fun scalePoseData(features: ArrayList<MutableList<Triple<Double, Double, Double>>>): ArrayList<MutableList<Triple<Double, Double, Double>>> {
        // 获取每帧数据
        features.forEach { frame ->
            // 获取参考的两点：左肩和左髋
            val leftShoulder = frame[11]  // 假设第 11 个关键点是左肩
            val leftHip = frame[23]  // 假设第 23 个关键点是左髋

            // 计算左肩到左髋的距离
            val scale = euclideanDistance(leftShoulder, leftHip)

            // 使用这个尺度对每个关键点进行缩放
            frame.forEachIndexed { index, point ->
                val scaledPoint = Triple(
                    point.first / scale,  // 缩放 x 坐标
                    point.second / scale, // 缩放 y 坐标
                    point.third / scale   // 缩放 z 坐标
                )
                frame[index] = scaledPoint  // 修改每个点的坐标
            }
        }
        return features
    }
    // 总和标准化

    private fun standardizePoseData(features: ArrayList<List<Triple<Double, Double, Double>>>): ArrayList<List<Triple<Double, Double, Double>>> {
        // 将 features 转换为 MutableList，允许修改其中的元素
        val mutableFeatures = features.map { it.toMutableList() } as ArrayList<MutableList<Triple<Double, Double, Double>>>

        // 执行标准化：平移、缩放
        var standardizedFeatures = mutableFeatures
        standardizedFeatures = normalizePoseData(standardizedFeatures)
        standardizedFeatures = scalePoseData(standardizedFeatures)

        // 将修改后的数据转换回不可变的 List
        return standardizedFeatures.map { it.toList() } as ArrayList<List<Triple<Double, Double, Double>>>
    }







    /** 构造 PoseLandmarker（单帧/视频皆可用，这里直接用 detect(mpImage) 简化调用）。*/
    // 创建 poselandmarker 实例
    private fun createPoseLandmarker(): PoseLandmarker {
        val base = BaseOptions.builder()
            .setModelAssetPath(poseModelAssetName)
            .build()
        val options = PoseLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.IMAGE)
            .setNumPoses(1)
            .build()
        return PoseLandmarker.createFromOptions(context, options)
    }

    // 关闭实例
    override fun close() {
        try { poseLandmarker.close() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "PracticeScorer"
        private const val MODEL_ASSET = "pose_landmarker_full.task" // 放在 assets/

        /**
         * 工具函数：从 raw 资源 id 构建 android.resource:// 的 Uri
         * 用法：score(recordedUri, rawToUri(context, R.raw.demo))
         */
        // 用于从 raw 资源目录中加载文件
        fun rawToUri(context: Context, @androidx.annotation.RawRes resId: Int): Uri {
            return Uri.parse("android.resource://${context.packageName}/$resId")
        }
    }
}
