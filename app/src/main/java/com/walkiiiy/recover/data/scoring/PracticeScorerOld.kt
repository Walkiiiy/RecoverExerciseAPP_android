package com.walkiiiy.recover.data.scoring

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * PracticeScorer
 *
 * 该类基于两种策略对一次“练习录制视频”的质量进行打分：
 * 1) **启发式评分（heuristic）**：当 TensorFlow Lite 模型不可用或推理失败时，
 *    使用视频时长比（录制/参考）与文件大小近似的“稳定性因子”进行估分。
 * 2) **TFLite 模型评分**：若模型加载成功，使用简单的 3 维特征向量做一次前向推理得到分数。
 *
 * 设计目标：
 * - 尽量健壮：模型或推理失败时，回退到启发式评分，避免崩溃或返回非法值。
 * - 低耦合：只依赖 Context、TFLite、MediaMetadataRetriever 和基础 I/O。
 * - 资源安全：对 retriever/stream 等资源严格关闭；Interpreter 提供 close()。
 *
 * ⚠️ 注意：
 * - 当前实现是同步的，如在主线程调用，可能引发卡顿（I/O、元数据提取、推理）。
 *   生产实践中建议在后台线程/协程（Dispatchers.IO）执行。
 * - Interpreter 不是线程安全的，如需并发评分，需要额外同步或串行化访问。
 */
class PracticeScorerLegacy(context: Context) {

    /**
     * 使用 applicationContext 避免持有短生命周期的 Activity 引用，防止潜在内存泄漏。
     */
    private val appContext = context.applicationContext

    /**
     * TensorFlow Lite 解释器：
     * - 尝试从 assets 加载模型并构造 Interpreter；
     * - 若任何环节失败，记录告警日志并置为 null（回退启发式评分）。
     *
     * 加载模型的关键点：
     * - 使用 AssetFileDescriptor + FileChannel.map 进行内存映射（零拷贝）；
     * - 要求 .tflite 在打包时 noCompress，否则 openFd 可能失败。
     */
    private val interpreter: Interpreter? = try {
        val model = loadModelFile(MODEL_FILE)
        Interpreter(model)
    } catch (error: Exception) {
        Log.w(TAG, "Failed to load TFLite model. Falling back to heuristic scoring.", error)
        null
    }

    /**
     * 对一次练习进行打分。
     *
     * @param recordedVideoPath 录制视频的**文件路径**（注意：对 content:// Uri 不适配，见下文备注）。
     * @param referenceVideoResId 参考演示视频的 raw 资源 ID（如 R.raw.demo）。
     * @return 分数（0.0 ~ 100.0），保证范围合法。
     *
     * 评分流程概述：
     * 1) 通过 MediaMetadataRetriever 提取两段视频的时长（毫秒），计算时长比 sizeRatio；
     * 2) 基于录制视频文件大小估算“稳定性因子”（非严格含义，仅作近似特征）；
     * 3) 基于上述两个特征构造启发式基线分 normalizedBase（范围 30~95）；
     * 4) 若 TFLite Interpreter 可用，则构造 3 维输入特征（sizeRatio、stabilityFactor、sizeMB）做推理，
     *    成功时返回模型输出（裁剪到 0~100），失败时回退到 normalizedBase。
     *
     * ⚠️ 线程模型：当前同步执行，包含 I/O、元数据提取与推理，不适合在主线程直接调用。
     */
    fun score(recordedVideoPath: String, referenceVideoResId: Int): Double {
        // 录制视频文件句柄（仅适用于有实际文件路径的场景；对 content:// 需改为 Uri 与 CR 查询大小）
        val recordedFile = File(recordedVideoPath)

        // 读取两段视频时长（毫秒），并对结果做下限保护，避免除零/异常情况放大影响。
        val recordedDuration = extractDurationMillis(recordedVideoPath).coerceAtLeast(1L)
        val referenceDuration = extractRawResourceDurationMillis(referenceVideoResId).coerceAtLeast(1L)

        // 时长比：越接近 1 通常表示时长更匹配；若参考时长异常为 0，回落为 1.0，避免影响评分。
        val sizeRatio = if (referenceDuration > 0) {
            recordedDuration.toDouble() / referenceDuration.toDouble()
        } else {
            1.0
        }

        // 根据文件大小粗略估计“稳定性”：
        // - 直觉上更大的文件常意味着更高的分辨率/码率/时长，从而“可能”更稳定或清晰；
        // - 这只是弱相关的近似指标，并非视频抖动/模糊的真实量化。
        val stabilityFactor = computeFileStability(recordedFile)

        // 启发式基线分：
        // - 70% 权重给时长比（上限 1.2x，避免时长远大于参考上分过度），
        // - 20% 权重给稳定性因子；
        // - 最后裁剪到 [30, 95]，避免极端取值。
        val baseScore = 70.0 * min(1.2, sizeRatio) + 20.0 * stabilityFactor
        val normalizedBase = baseScore.coerceIn(30.0, 95.0)

        // 若 Interpreter 不可用，直接返回启发式基线分。
        val interpreter = interpreter
        if (interpreter == null) {
            return normalizedBase
        }

        // 构造 TFLite 输入：这里使用 shape = [1, 3] 的二维数组（batch=1）。
        // 三个特征依次为：时长比、稳定性因子、文件大小（MB）。
        val modelInput = arrayOf(
            floatArrayOf(
                sizeRatio.toFloat(),
                stabilityFactor.toFloat(),
                (recordedFile.length().coerceAtLeast(1L) / 1_000_000.0).toFloat()
            )
        )
        // 模型输出：shape = [1, 1]，预期单一分数。
        val modelOutput = Array(1) { FloatArray(1) }

        return try {
            // 进行一次前向推理；注意：Interpreter 不是线程安全的，若并发调用需要外层同步。
            interpreter.run(modelInput, modelOutput)

            // 读取输出分数；若取值异常则回退到启发式分；最终裁剪到 [0, 100]。
            val rawScore = modelOutput[0].getOrNull(0)?.toDouble() ?: normalizedBase
            rawScore.coerceIn(0.0, 100.0)
        } catch (error: Exception) {
            // 推理失败时兜底：记录日志并返回启发式分。
            Log.w(TAG, "Interpreter execution failed. Using heuristic score.", error)
            normalizedBase
        }
    }

    /**
     * 从 assets 目录中以**内存映射**的方式加载 tflite 模型文件。
     *
     * @param fileName 模型文件名（位于 app/src/main/assets）。
     * @return MappedByteBuffer，可用于构造 Interpreter。
     *
     * 关键注意事项：
     * - 要保证该资产在打包时未压缩（noCompress），否则 openFd 无法获取偏移与长度；
     * - 推荐在 Gradle 配置：aaptOptions { noCompress "tflite" }；
     * - 若环境不满足，可考虑退化为 InputStream 复制到 ByteBuffer（性能较差）。
     */
    private fun loadModelFile(fileName: String): MappedByteBuffer {
        val fileDescriptor = appContext.assets.openFd(fileName)
        FileInputStream(fileDescriptor.fileDescriptor).use { input ->
            val channel = input.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            return channel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        }
    }

    /**
     * 提取**本地文件路径**对应视频的时长（毫秒）。
     *
     * @param path 录制视频的本地文件路径（如 /sdcard/.../xxx.mp4）。
     * @return 时长（毫秒），失败或解析不到时返回 0。
     *
     * 备注：若你的数据源是 content:// Uri（分区存储、SAF、MediaStore），
     *       请改用 setDataSource(context, uri) 那个重载版本。
     */
    private fun extractDurationMillis(path: String): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationStr?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            // 必须释放 retriever 资源，避免句柄泄漏。
            retriever.release()
        }
    }

    /**
     * 提取 raw 资源视频的时长（毫秒）。
     *
     * @param referenceId 原始资源 ID（例如 R.raw.demo）。
     * @return 时长（毫秒），失败或解析不到时返回 0。
     *
     * 实现：构造 android.resource://<pkg>/<resId> 的 Uri，交给 retriever 解析。
     */
    private fun extractRawResourceDurationMillis(@androidx.annotation.RawRes referenceId: Int): Long {
        val uri = Uri.parse("android.resource://${appContext.packageName}/$referenceId")
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            retriever.release()
        }
    }

    /**
     * 基于**文件大小**粗略计算一个“稳定性因子”。
     *
     * @param recordedFile 录制视频的文件对象。
     * @return [0.3, 1.0] 区间的值。文件越大，一般返回越高；最小不低于 0.3。
     *
     * 公式解释：
     * - sizeInMb = file.length / 1_000_000.0
     * - normalizedSize = min(1.0, sizeInMb / 25.0)
     * - return = max(0.3, normalizedSize)
     *
     * 直觉：把 25MB 作为“满分”规模阈值，过小的视频不至于得到过低分（下限 0.3），
     * 但这个指标并不等价于真实画面稳定/清晰度，仅作为低成本近似特征。
     */
    private fun computeFileStability(recordedFile: File): Double {
        if (!recordedFile.exists()) return 0.5 // 文件不存在时给一个温和中位值，避免极端影响。
        val sizeInMb = recordedFile.length() / 1_000_000.0
        val normalizedSize = min(1.0, sizeInMb / 25.0)
        return max(0.3, normalizedSize)
    }

    /**
     * 释放底层 TFLite 资源。
     *
     * 调用时机：
     * - 组件不再需要时（如 ViewModel.onCleared 或 Application 关闭等生命周期节点）。
     * - 若在多次构造/销毁的场景中使用，务必确保调用以避免 native 句柄泄漏。
     */
    fun close() {
        interpreter?.close()
    }

    companion object {
        /** 日志 TAG。 */
        private const val TAG = "PracticeScorer"
        /** assets 下的 TFLite 模型文件名。 */
        private const val MODEL_FILE = "practice_quality_model.tflite"
    }
}
