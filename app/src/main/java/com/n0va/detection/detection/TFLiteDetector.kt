package com.n0va.detection.detection

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.max

/**
 * TFLite YOLO 检测器，支持 GPU / CPU 两级加速。
 *
 * 同时支持两种输出格式：
 *   1）NMS 后处理格式 [1, 300, 6] — x1,y1,x2,y2,conf,cls（如 YOLO26n）
 *   2）原始 YOLO 格式 [1, 4+num_classes, total_cells] — 需 decode + NMS（如 YOLO11n）
 */
class TFLiteDetector(
    private val context: Context,
    private val modelManager: ModelManager
) {
    companion object {
        private const val TAG = "TFLiteDetector"

        var confThreshold = 0.25f
        var iouThreshold = 0.45f

        var isLoaded = false
            private set
        var usedDevice = "CPU"
            private set
        var numClasses = 0
            private set
        var labels: List<String> = emptyList()
            private set
    }

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    // ── 输入缓存 ──
    private var inputBuffer: ByteBuffer? = null
    private var pixelBuffer: IntArray? = null

    // ── 输出缓存（分两种格式） ──
    private var outputBufferNMS: Array<Array<FloatArray>>? = null    // [1][300][6]
    private var outputBufferRaw: Array<Array<FloatArray>>? = null    // [1][C][N]

    // ── 当前模型信息 ──
    private var currentModelFile: String = modelManager.availableModels[0].tfliteFile
    private var currentLabelsFile: String = modelManager.availableModels[0].labelsFile
    private var currentInputSize: Int = modelManager.availableModels[0].inputSize
    private var currentIsRawYolo: Boolean = modelManager.availableModels[0].isRawYolo
    private var currentIsPose: Boolean = modelManager.availableModels[0].isPose
    private var currentIsCustom: Boolean = modelManager.availableModels[0].isCustom
    private var currentCustomTflitePath: String? = modelManager.availableModels[0].customTflitePath

    // ── 加载 ──

    fun load(modelIndex: Int = 0) {
        val model = modelManager.availableModels.getOrElse(modelIndex) { modelManager.availableModels[0] }
        modelManager.activeModelIndex = modelIndex
        currentModelFile = model.tfliteFile
        currentLabelsFile = model.labelsFile
        currentInputSize = model.inputSize
        currentIsRawYolo = model.isRawYolo
        currentIsPose = model.isPose
        currentIsCustom = model.isCustom
        currentCustomTflitePath = model.customTflitePath
        // 清空输出缓冲，重新适配输出张量形状
        outputBufferNMS = null
        outputBufferRaw = null
        try {
            labels = loadLabels()
            numClasses = labels.size
            Log.i(TAG, "标签加载: ${numClasses}类")

            val modelBuf = loadModelFile()
            interpreter = createInterpreter(modelBuf)
            isLoaded = true
            Log.i(TAG, "TFLite 就绪, 模型: ${model.name}, 设备: $usedDevice, 格式: ${if (currentIsRawYolo) "原始YOLO" else "NMS后处理"}")
        } catch (e: Exception) {
            isLoaded = false
            Log.e(TAG, "模型加载失败", e)
            throw e
        }
    }

    private fun loadLabels(): List<String> {
        return if (currentIsCustom) {
            File(currentLabelsFile).readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        } else {
            context.assets.open(currentLabelsFile).bufferedReader().readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
    }

    private fun loadModelFile(): MappedByteBuffer {
        return if (currentIsCustom && currentCustomTflitePath != null) {
            val file = File(currentCustomTflitePath!!)
            val inputStream = FileInputStream(file)
            val channel = inputStream.channel
            channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
        } else {
            val afd = context.assets.openFd(currentModelFile)
            val inputStream = FileInputStream(afd.fileDescriptor)
            val channel = inputStream.channel
            val startOffset = afd.startOffset
            val declaredLength = afd.declaredLength
            channel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        }
    }

    private fun createInterpreter(model: MappedByteBuffer): Interpreter {
        val options = Interpreter.Options().apply {
            setNumThreads(4)
            setUseXNNPACK(true)
        }

        // GPU delegate 优先
        var gpuAttempted = false
        try {
            val gpuOptions = org.tensorflow.lite.gpu.GpuDelegateFactory.Options().apply {
                inferencePreference = org.tensorflow.lite.gpu.GpuDelegateFactory.Options.INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER
                setPrecisionLossAllowed(true)  // fp16 加速
            }
            val gpu = org.tensorflow.lite.gpu.GpuDelegate(gpuOptions)
            options.addDelegate(gpu)
            options.setAllowBufferHandleOutput(true)  // 减少 GPU→CPU 拷贝
            gpuDelegate = gpu
            gpuAttempted = true
            usedDevice = "GPU"
            Log.i(TAG, "GPU delegate added.")
        } catch (e: Throwable) {
            Log.w(TAG, "GPU unavailable: ${e.message}")
            gpuDelegate = null
            usedDevice = "CPU"
        }

        try {
            return Interpreter(model, options)
        } catch (e: Throwable) {
            if (gpuAttempted) {
                // GPU delegate 不兼容 → 关闭 GPU 用 CPU 重试
                Log.w(TAG, "GPU delegate incompatible, falling back to CPU: ${e.message}")
                gpuDelegate?.close()
                gpuDelegate = null
                usedDevice = "CPU"
                val cpuOptions = Interpreter.Options().apply {
                    setNumThreads(4)
                    setUseXNNPACK(true)
                }
                return Interpreter(model, cpuOptions)
            }
            throw e
        }
    }

    // ── 检测入口 ──

    fun detect(bitmap: Bitmap): List<DetectionResult> {
        if (!isLoaded) return emptyList()

        val input = preprocess(bitmap)
        if (currentIsPose) {
            val output = reuseOutputRaw()
            interpreter?.run(input, output)
            return postprocessRawPose(output, bitmap.width, bitmap.height)
        } else if (currentIsRawYolo) {
            val output = reuseOutputRaw()
            interpreter?.run(input, output)
            return postprocessRawYolo(output, bitmap.width, bitmap.height)
        } else {
            val output = reuseOutputNMS()
            interpreter?.run(input, output)
            return postprocess(output[0], bitmap.width, bitmap.height)
        }
    }

    fun detectFromNV21(nv21: ByteArray, imgW: Int, imgH: Int, rotationDeg: Int = 0): List<DetectionResult> {
        if (!isLoaded) return emptyList()
        // 直接使用 NV21 预处理路径（支持旋转），跳过冗余的 NV21→JPEG→Bitmap 转换
        return detectFromNV21Direct(nv21, imgW, imgH, rotationDeg)
    }

    /**
     * 直接的 NV21 检测管线，不经过 JPEG 编解码。
     * 通过像素级重映射支持旋转，结果坐标系为旋转后的图像空间。
     */
    fun detectFromNV21Direct(nv21: ByteArray, imgW: Int, imgH: Int, rotationDeg: Int = 0): List<DetectionResult> {
        if (!isLoaded) return emptyList()
        val input = preprocessNV21(nv21, imgW, imgH, rotationDeg)
        // 有效图像尺寸：旋转后宽高互换，postprocess 需要基于此计算 letterbox
        val rotated = rotationDeg == 90 || rotationDeg == 270
        val effW = if (rotated) imgH else imgW
        val effH = if (rotated) imgW else imgH

        return if (currentIsPose) {
            val output = reuseOutputRaw()
            interpreter?.run(input, output)
            postprocessRawPose(output, effW, effH)
        } else if (currentIsRawYolo) {
            val output = reuseOutputRaw()
            interpreter?.run(input, output)
            postprocessRawYolo(output, effW, effH)
        } else {
            val output = reuseOutputNMS()
            interpreter?.run(input, output)
            postprocess(output[0], effW, effH)
        }
    }

    private fun reuseOutputNMS(): Array<Array<FloatArray>> {
        // 每次读取实际输出张量形状，与缓存比较，不一致则重新分配
        val outShape = interpreter?.getOutputTensor(0)?.shape() ?: intArrayOf(1, 300, 6)
        val dim1 = if (outShape.size > 1) outShape[1] else 300
        val dim2 = if (outShape.size > 2) outShape[2] else 6
        if (outputBufferNMS == null || outputBufferNMS!![0].size != dim1 || outputBufferNMS!![0][0].size != dim2) {
            outputBufferNMS = Array(outShape[0]) { Array(dim1) { FloatArray(dim2) } }
        }
        return outputBufferNMS!!
    }

    private fun reuseOutputRaw(): Array<Array<FloatArray>> {
        // 每次读取实际输出张量形状，与缓存比较，不一致则重新分配
        val outShape = interpreter?.getOutputTensor(0)?.shape() ?: intArrayOf(1, 8, 11109)
        val dim2 = if (outShape.size > 2) outShape[2] else 11109
        val dim1 = if (outShape.size > 1) outShape[1] else 8
        if (outputBufferRaw == null || outputBufferRaw!![0][0].size != dim2 || outputBufferRaw!![0].size != dim1) {
            outputBufferRaw = Array(outShape[0]) { Array(dim1) { FloatArray(dim2) } }
        }
        return outputBufferRaw!!
    }

    // ── 预处理 ──

    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        val s = currentInputSize
        val ow = bitmap.width
        val oh = bitmap.height
        val scale = minOf(s.toFloat() / ow, s.toFloat() / oh)
        val nw = (ow * scale).toInt()
        val nh = (oh * scale).toInt()

        val resized = Bitmap.createScaledBitmap(bitmap, nw, nh, true)
        val canvas = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(canvas)
        c.drawColor(android.graphics.Color.rgb(114, 114, 114))
        c.drawBitmap(resized, (s - nw) / 2f, (s - nh) / 2f, null)
        resized.recycle()

        val buf = inputBuffer ?: ByteBuffer.allocateDirect(4 * 3 * s * s).also {
            inputBuffer = it
        }
        buf.rewind()
        buf.order(ByteOrder.nativeOrder())

        val pixels = pixelBuffer ?: IntArray(s * s).also { pixelBuffer = it }
        canvas.getPixels(pixels, 0, s, 0, 0, s, s)
        canvas.recycle()

        for (pixel in pixels) {
            buf.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            buf.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
            buf.putFloat((pixel and 0xFF) / 255.0f)
        }
        buf.rewind()
        return buf
    }

    /**
     * 直接 NV21 → 模型输入的预处理，支持旋转。
     * 通过像素级重映射避免 NV21→JPEG→Bitmap 的冗余转换。
     * @param rotationDeg 顺时针旋转角度 (0/90/180/270)
     */
    private fun preprocessNV21(nv21: ByteArray, imgW: Int, imgH: Int, rotationDeg: Int = 0): ByteBuffer {
        val s = currentInputSize

        // 旋转后的有效图像尺寸（90°/270° 时宽高互换）
        val rotated = rotationDeg == 90 || rotationDeg == 270
        val rW = if (rotated) imgH else imgW
        val rH = if (rotated) imgW else imgH

        val scale = minOf(s.toFloat() / rW, s.toFloat() / rH)
        val sw = (rW * scale).toInt()
        val sh = (rH * scale).toInt()
        val padX = (s - sw) / 2f
        val padY = (s - sh) / 2f

        val buf = inputBuffer ?: ByteBuffer.allocateDirect(4 * 3 * s * s).also {
            inputBuffer = it
        }
        buf.rewind()
        buf.order(ByteOrder.nativeOrder())

        val uvStart = imgW * imgH

        for (outY in 0 until s) {
            for (outX in 0 until s) {
                // 计算在旋转后的图像中的坐标
                var srcX = ((outX - padX) / scale).toInt()
                var srcY = ((outY - padY) / scale).toInt()

                // 通过重映射将旋转后坐标转回原始 NV21 缓冲坐标
                when (rotationDeg) {
                    90 -> {
                        // CW 90°: destX = imgH-1-srcY, destY = srcX
                        val tmp = srcX
                        srcX = srcY
                        srcY = imgH - 1 - tmp
                    }
                    270 -> {
                        // CCW 90° (CW 270°): destX = srcY, destY = imgW-1-srcX
                        val tmp = srcX
                        srcX = imgW - 1 - srcY
                        srcY = tmp
                    }
                    180 -> {
                        srcX = imgW - 1 - srcX
                        srcY = imgH - 1 - srcY
                    }
                }
                srcX = srcX.coerceIn(0, imgW - 1)
                srcY = srcY.coerceIn(0, imgH - 1)

                val yIdx = srcY * imgW + srcX
                val uvIdx = uvStart + (srcY / 2) * imgW + (srcX / 2) * 2
                val y = nv21[yIdx].toInt() and 0xFF
                // NV21 存储顺序为 V, U（先 V 后 U）
                val v = nv21[uvIdx].toInt() and 0xFF
                val u = nv21[uvIdx + 1].toInt() and 0xFF

                val r = (y + 1.402f * (v - 128)).coerceIn(0f, 255f) / 255f
                val g = (y - 0.344f * (u - 128) - 0.714f * (v - 128)).coerceIn(0f, 255f) / 255f
                val b = (y + 1.772f * (u - 128)).coerceIn(0f, 255f) / 255f

                buf.putFloat(r)
                buf.putFloat(g)
                buf.putFloat(b)
            }
        }
        buf.rewind()
        return buf
    }

    // ── 后处理（NMS 格式 [300,6]） ──

    private fun postprocess(
        output: Array<FloatArray>,
        origW: Int, origH: Int
    ): List<DetectionResult> {
        val s = currentInputSize.toFloat()
        val scale = minOf(s / origW, s / origH)
        val padX = (s - origW * scale) / 2f
        val padY = (s - origH * scale) / 2f

        val results = mutableListOf<DetectionResult>()

        for (i in 0 until 300) {
            val row = output[i]
            val confidence = row[4]
            if (confidence < confThreshold) continue

            val classId = row[5].toInt()

            val x1 = ((row[0] * s - padX) / (scale * origW)).coerceIn(0f, 1f)
            val y1 = ((row[1] * s - padY) / (scale * origH)).coerceIn(0f, 1f)
            val x2 = ((row[2] * s - padX) / (scale * origW)).coerceIn(0f, 1f)
            val y2 = ((row[3] * s - padY) / (scale * origH)).coerceIn(0f, 1f)

            val cx = (x1 + x2) / 2f
            val cy = (y1 + y2) / 2f
            val w = (x2 - x1).coerceAtLeast(0f)
            val h = (y2 - y1).coerceAtLeast(0f)

            results.add(DetectionResult(
                cx = cx, cy = cy, w = w, h = h,
                confidence = confidence,
                classId = classId,
                className = labels.getOrElse(classId) { "cls_$classId" }
            ))
        }

        return applyNMS(results)
    }

    // ── 后处理（原始 YOLO 格式 [1, 4+cls, total_cells]） ──
    //
    // 针对 ultralytics YOLO TFLite（nms=False）输出：
    //   - 输出形状 [1, 4+num_classes, total_cells]
    //   - total_cells = Σ(H*W) for stride {32, 16, 8} = 8400 (640) / 11109 (736)
    //   - 8 通道: [cx, cy, w, h, cls0, cls1, cls2, cls3]
    //   - bbox = 像素坐标 (0 ~ inputSize)，需归一化到 [0,1]
    //   - class scores = 已做过 sigmoid，直接读取

    private fun postprocessRawYolo(
        output: Array<Array<FloatArray>>,
        origW: Int, origH: Int
    ): List<DetectionResult> {
        val s = currentInputSize.toFloat()
        val numClasses = labels.size
        val total = output[0][0].size

        // Letterbox 参数（还原到原图坐标）
        val scale = minOf(s / origW, s / origH)
        val padX = (s - origW * scale) / 2f
        val padY = (s - origH * scale) / 2f

        val candidates = mutableListOf<DetectionResult>()

        for (i in 0 until total) {
            // bbox 在像素坐标 [cx, cy, w, h] (0 ~ inputSize)
            val cx = output[0][0][i].toDouble() / s  // → [0,1]
            val cy = output[0][1][i].toDouble() / s
            val w  = output[0][2][i].toDouble() / s
            val h  = output[0][3][i].toDouble() / s

            if (w <= 0.0 || h <= 0.0) continue

            // class scores 已 sigmoid，直接读
            var bestScore = 0.0
            var bestCls = 0
            for (c in 0 until numClasses) {
                val score = output[0][4 + c][i].toDouble()
                if (score > bestScore) {
                    bestScore = score
                    bestCls = c
                }
            }

            if (bestScore < confThreshold) continue

            // [cx,cy,w,h] → [x1,y1,x2,y2]（归一化 [0,1]）
            val x1 = (cx - w / 2.0).coerceIn(0.0, 1.0)
            val y1 = (cy - h / 2.0).coerceIn(0.0, 1.0)
            val x2 = (cx + w / 2.0).coerceIn(0.0, 1.0)
            val y2 = (cy + h / 2.0).coerceIn(0.0, 1.0)

            // letterbox 反算 → 原图归一化坐标
            val ox1 = ((x1 * s - padX) / (scale * origW)).toFloat().coerceIn(0f, 1f)
            val oy1 = ((y1 * s - padY) / (scale * origH)).toFloat().coerceIn(0f, 1f)
            val ox2 = ((x2 * s - padX) / (scale * origW)).toFloat().coerceIn(0f, 1f)
            val oy2 = ((y2 * s - padY) / (scale * origH)).toFloat().coerceIn(0f, 1f)

            candidates.add(DetectionResult(
                cx = ((ox1 + ox2) / 2f),
                cy = ((oy1 + oy2) / 2f),
                w  = (ox2 - ox1).coerceAtLeast(0f),
                h  = (oy2 - oy1).coerceAtLeast(0f),
                confidence = bestScore.toFloat(),
                classId = bestCls,
                className = labels.getOrElse(bestCls) { "cls_$bestCls" }
            ))
        }

        return applyNMS(candidates)
    }

    // ── 后处理（姿态模型 [1, 56, total_cells]） ──
    //
    // YOLO11n-pose TFLite（nms=False）输出：
    //   - 形状 [1, 56, 8400]
    //   - ch[0:3] = [cx, cy, w, h] 归一化 [0,1]
    //   - ch[4]   = person 置信度（已 sigmoid）
    //   - ch[5:55] = 17 关键点 × 3 (x, y, visibility)，已归一化 [0,1]

    private fun postprocessRawPose(
        output: Array<Array<FloatArray>>,
        origW: Int, origH: Int
    ): List<DetectionResult> {
        val s = currentInputSize.toFloat()
        val total = output[0][0].size

        val scale = minOf(s / origW, s / origH)
        val padX = (s - origW * scale) / 2f
        val padY = (s - origH * scale) / 2f

        val candidates = mutableListOf<DetectionResult>()

        for (i in 0 until total) {
            val personScore = output[0][4][i].toDouble()
            if (personScore < confThreshold) continue

            // bbox [cx, cy, w, h] 归一化 [0,1]
            val cx = output[0][0][i].toDouble()
            val cy = output[0][1][i].toDouble()
            val w  = output[0][2][i].toDouble()
            val h  = output[0][3][i].toDouble()

            if (w <= 0.0 || h <= 0.0) continue

            // letterbox 反算 → 原图归一化坐标
            val x1 = (cx - w / 2.0).coerceIn(0.0, 1.0)
            val y1 = (cy - h / 2.0).coerceIn(0.0, 1.0)
            val x2 = (cx + w / 2.0).coerceIn(0.0, 1.0)
            val y2 = (cy + h / 2.0).coerceIn(0.0, 1.0)

            val ox1 = ((x1 * s - padX) / (scale * origW)).toFloat().coerceIn(0f, 1f)
            val oy1 = ((y1 * s - padY) / (scale * origH)).toFloat().coerceIn(0f, 1f)
            val ox2 = ((x2 * s - padX) / (scale * origW)).toFloat().coerceIn(0f, 1f)
            val oy2 = ((y2 * s - padY) / (scale * origH)).toFloat().coerceIn(0f, 1f)

            // 解析 17 个关键点
            val kpts = mutableListOf<KeyPoint>()
            for (k in 0 until 17) {
                val base = 5 + k * 3
                val kx = output[0][base][i]
                val ky = output[0][base + 1][i]
                val kv = output[0][base + 2][i]

                // 关键点 letterbox 反算
                val okx = ((kx * s - padX) / (scale * origW)).toFloat().coerceIn(0f, 1f)
                val oky = ((ky * s - padY) / (scale * origH)).toFloat().coerceIn(0f, 1f)
                kpts.add(KeyPoint(okx, oky, kv))
            }

            candidates.add(DetectionResult(
                cx = ((ox1 + ox2) / 2f),
                cy = ((oy1 + oy2) / 2f),
                w  = (ox2 - ox1).coerceAtLeast(0f),
                h  = (oy2 - oy1).coerceAtLeast(0f),
                confidence = personScore.toFloat(),
                classId = 0,
                className = "person",
                keypoints = kpts
            ))
        }

        return applyNMS(candidates)
    }

    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + kotlin.math.exp(-x))

    private fun applyNMS(boxes: List<DetectionResult>): List<DetectionResult> {
        val sorted = boxes.sortedByDescending { it.confidence }
        val result = mutableListOf<DetectionResult>()
        val suppressed = BooleanArray(sorted.size)

        for (i in sorted.indices) {
            if (suppressed[i]) continue
            result.add(sorted[i])
            val areaI = (sorted[i].x2(1f) - sorted[i].x1(1f)) * (sorted[i].y2(1f) - sorted[i].y1(1f))
            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue
                val ix1 = maxOf(sorted[i].x1(1f), sorted[j].x1(1f))
                val iy1 = maxOf(sorted[i].y1(1f), sorted[j].y1(1f))
                val ix2 = minOf(sorted[i].x2(1f), sorted[j].x2(1f))
                val iy2 = minOf(sorted[i].y2(1f), sorted[j].y2(1f))
                val iw = (ix2 - ix1).coerceAtLeast(0f)
                val ih = (iy2 - iy1).coerceAtLeast(0f)
                val inter = iw * ih
                val areaJ = (sorted[j].x2(1f) - sorted[j].x1(1f)) * (sorted[j].y2(1f) - sorted[j].y1(1f))
                val union = areaI + areaJ - inter
                if (union > 0f && inter / union > iouThreshold) suppressed[j] = true
            }
        }

        return result
    }

    // ── 释放 ──

    fun close() {
        try { gpuDelegate?.close() } catch (_: Exception) {}
        try { interpreter?.close() } catch (_: Exception) {}
        interpreter = null
        gpuDelegate = null
        inputBuffer = null
        pixelBuffer = null
        outputBufferNMS = null
        outputBufferRaw = null
        isLoaded = false
    }
}
