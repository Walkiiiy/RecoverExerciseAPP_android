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
class PracticeScorer(private val context: Context) : Closeable {

    // ************************** 可调参数区 **************************

    /** 采样的目标帧数（从每段视频中等间隔抽取），越大越精细但越慢。 */
    private val targetFramesPerVideo: Int = 64

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
    fun score(recordedUri: Uri, referenceUri: Uri): Double {
        return try {
            // 1) 为两段视频抽帧并获取每帧的姿态特征（角度向量）
            val recordedSeries = extractPoseFeatureSeries(recordedUri, targetFramesPerVideo)
            val referenceSeries = extractPoseFeatureSeries(referenceUri, targetFramesPerVideo)

            // 2) 质量控制：若有效帧过少，直接返回保守分（避免不稳定结果）
            if (recordedSeries.isEmpty() || referenceSeries.isEmpty() ||
                recordedSeries.size < minValidFrames || referenceSeries.size < minValidFrames) {
                return 35.0 // 信息不足时的保守分
            }

            // 3) 将两段时序特征重采样到相同长度
            val targetLen = min(recordedSeries.size, referenceSeries.size)
                .coerceIn(minValidFrames, targetFramesPerVideo)
            val recAligned = resampleSeries(recordedSeries, targetLen)
            val refAligned = resampleSeries(referenceSeries, targetLen)

            // 4) 逐帧计算余弦相似度并取平均
            var sum = 0.0
            var count = 0
            for (i in 0 until targetLen) {
                val a = recAligned[i]
                val b = refAligned[i]
                val sim = cosineSimilarity(a, b)
                if (!sim.isNaN()) {
                    sum += sim
                    count++
                }
            }
            if (count == 0) return 35.0
            val avgSim = (sum / count).coerceIn(-1.0, 1.0)

            // 5) 将 [-1,1] 的相似度映射到 [0,100]
            val baseScore = ((avgSim * similarityWeight) + 1.0) / 2.0 * 100.0

            // 6) 对覆盖率进行轻微惩罚：有效帧占比越低，打些折扣（上限 10 分）
            val coverage = (recordedSeries.size + referenceSeries.size) / (2.0 * targetFramesPerVideo)
            val coveragePenalty = (1.0 - coverage.coerceIn(0.0, 1.0)) * 10.0

            (baseScore - coveragePenalty).coerceIn(0.0, 100.0)
        } catch (e: Exception) {
            Log.w(TAG, "Scoring failed, returning fallback.", e)
            35.0
        }
    }

    /**
     * 使用 MediaMetadataRetriever 以等间隔抽帧，并用 PoseLandmarker 提取每帧的骨骼特征向量。
     * 特征为一组**关节角度**（单位：弧度），相较于直接使用坐标，对尺度与位移更鲁棒。
     */
    private fun extractPoseFeatureSeries(uri: Uri, maxFrames: Int): List<DoubleArray> {
        val retriever = MediaMetadataRetriever()
        val features = ArrayList<DoubleArray>(maxFrames)
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(1L) ?: 1L

            // 等间隔抽帧（注意：getFrameAtTime 是近似，会取接近的关键帧并解码）
            for (i in 0 until maxFrames) {
                val tUs = (i * durationMs * 1000L) / max(1, (maxFrames - 1))
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
        return features
    }

    /** 将一帧位图送入 PoseLandmarker，返回该帧的角度特征向量。 */
    private fun detectPoseAndToFeature(bitmap: Bitmap): DoubleArray? {
        // 构造 MPImage（Bitmap 包装器）
        val mpImage = BitmapImageBuilder(bitmap).build()

        // 运行姿态检测（单帧）
        val result: PoseLandmarkerResult = poseLandmarker.detect(mpImage)
        // 只取第一位人物（大多数训练/康复场景只有一个人）
        val landmarks = result.landmarks().firstOrNull() ?: return null
        val world = result.worldLandmarks().firstOrNull() // 如果可用，优先 worldLandmarks（米制、弱透视影响）

        // 下标对照：参见 MediaPipe Pose 的 33 点拓扑
        // 为鲁棒性，这里只选择几组稳定的关节构成角度：
        val L_SHOULDER = 11; val R_SHOULDER = 12
        val L_ELBOW = 13; val R_ELBOW = 14
        val L_WRIST = 15; val R_WRIST = 16
        val L_HIP = 23; val R_HIP = 24
        val L_KNEE = 25; val R_KNEE = 26
        val L_ANKLE = 27; val R_ANKLE = 28

        fun p(i: Int): Triple<Double, Double, Double> {
            return if (world != null && world.size > i) {
                val w = world[i]
                Triple(w.x().toDouble(), w.y().toDouble(), w.z().toDouble())
            } else {
                val n = landmarks[i]
                // 归一化坐标系下的 (x,y) + 以 z 作为近似深度，可能受相机影响更大
                Triple(n.x().toDouble(), n.y().toDouble(), n.z().toDouble())
            }
        }

        // 角度定义：angle(a, b, c) 返回 ∠ABC （向量 BA 与 BC 的夹角）
        fun angle(aIdx: Int, bIdx: Int, cIdx: Int): Double {
            val (ax, ay, az) = p(aIdx)
            val (bx, by, bz) = p(bIdx)
            val (cx, cy, cz) = p(cIdx)
            val v1 = doubleArrayOf(ax - bx, ay - by, az - bz)
            val v2 = doubleArrayOf(cx - bx, cy - by, cz - bz)
            return vectorAngle(v1, v2)
        }

        // 左右上肢、下肢与躯干的关键角度（可按需要增/减）
        val angles = doubleArrayOf(
            angle(L_SHOULDER, L_ELBOW, L_WRIST),   // 左肘角
            angle(R_SHOULDER, R_ELBOW, R_WRIST),   // 右肘角
            angle(L_HIP, L_KNEE, L_ANKLE),         // 左膝角
            angle(R_HIP, R_KNEE, R_ANKLE),         // 右膝角
            angle(L_ELBOW, L_SHOULDER, L_HIP),     // 左肩夹角（上臂-躯干）
            angle(R_ELBOW, R_SHOULDER, R_HIP),     // 右肩夹角
            angle(L_SHOULDER, L_HIP, L_KNEE),      // 左髋夹角（躯干-大腿）
            angle(R_SHOULDER, R_HIP, R_KNEE)       // 右髋夹角
        )
        return angles
    }

    /**
     * 将时序特征重采样到指定长度（线性插值）。
     * 输入：List<DoubleArray>（每个元素为某帧的角度特征向量）。
     */
    private fun resampleSeries(series: List<DoubleArray>, targetLen: Int): List<DoubleArray> {
        if (series.size == targetLen) return series
        val out = ArrayList<DoubleArray>(targetLen)
        val n = series.size
        for (i in 0 until targetLen) {
            val pos = i * (n - 1).toDouble() / (targetLen - 1).toDouble()
            val idx0 = pos.toInt().coerceIn(0, n - 1)
            val idx1 = min(idx0 + 1, n - 1)
            val t = pos - idx0
            val v0 = series[idx0]
            val v1 = series[idx1]
            out.add(lerpVector(v0, v1, t))
        }
        return out
    }

    /** 余弦相似度，返回 [-1,1]。*/
    private fun cosineSimilarity(a: DoubleArray, b: DoubleArray): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom == 0.0) Double.NaN else (dot / denom).coerceIn(-1.0, 1.0)
    }

    /** 三维向量夹角（弧度），基于余弦定理。*/
    private fun vectorAngle(u: DoubleArray, v: DoubleArray): Double {
        var dot = 0.0
        var nu = 0.0
        var nv = 0.0
        for (i in 0..2) {
            dot += u[i] * v[i]
            nu += u[i] * u[i]
            nv += v[i] * v[i]
        }
        val denom = sqrt(nu) * sqrt(nv)
        if (denom == 0.0) return 0.0
        val cos = (dot / denom).coerceIn(-1.0, 1.0)
        return acos(cos)
    }

    /** 线性插值两向量（同维）。*/
    private fun lerpVector(a: DoubleArray, b: DoubleArray, t: Double): DoubleArray {
        val out = DoubleArray(a.size)
        for (i in out.indices) out[i] = a[i] * (1 - t) + b[i] * t
        return out
    }

    /** 构造 PoseLandmarker（单帧/视频皆可用，这里直接用 detect(mpImage) 简化调用）。*/
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
        fun rawToUri(context: Context, @androidx.annotation.RawRes resId: Int): Uri {
            return Uri.parse("android.resource://${context.packageName}/$resId")
        }
    }
}
