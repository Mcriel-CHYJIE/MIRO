package com.n0va.detection.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
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
 * TFLite YOLO 检测器，支持 GPU / NNAPI / CPU 三级加速。
 *
 * 同时支持两种输出格式：
 *   1）NMS 后处理格式 [1, 300, 6] — x1,y1,x2,y2,conf,cls（如 YOLO26n）
 *   2）原始 YOLO 格式 [1, 4+num_classes, total_cells] — 需 decode + NMS（如 YOLO11n）
 */
class TFLiteDetector(
    private val context: Context
) {
    companion object {
        private const val TAG = "TFLiteDetector"

        data class ModelInfo(
            val name: String,
            val tfliteFile: String,
            val labelsFile: String,
            val inputSize: Int,
            val isRawYolo: Boolean = false,   // true = [1, 4+cls, N] 原始输出
            val isPose: Boolean = false,      // true = 姿态模型 [1, 56, N]
            val isCustom: Boolean = false,    // true = 用户导入的自定义模型
            val customTflitePath: String? = null // 自定义模型的绝对路径
        )

        val availableModels = mutableListOf(
            ModelInfo("YOLO26n 640", "model/yolo26n_float32.tflite", "model/coco_classes.txt", 640),
            ModelInfo("Pose 11n 640", "model/yolo11n-pose.tflite", "model/pose_classes.txt", 640, isRawYolo = true, isPose = true),
        )

        private val currentModelInfo: ModelInfo
            get() = availableModels.getOrElse(activeModelIndex) { availableModels[0] }

        var activeModelIndex = 0
            private set

        val activeModelName: String
            get() = currentModelInfo.name

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

        /**
         * 添加用户导入的自定义模型。
         * @param context Android 上下文
         * @param name 模型显示名称
         * @param tflitePath 源 .tflite 文件路径（将被复制到内部存储）
         * @param labelsStr 标签文本（每行一个类别，或逗号分隔）
         * @param inputSize 模型输入尺寸（如 640）
         * @param classes 类别数（>0 = 原始 YOLO 格式，0 = NMS 后处理格式）
         * @param isPose 是否为姿态模型
         */
        fun addCustomModel(context: Context, name: String, tflitePath: String, labelsStr: String, inputSize: Int, classes: Int, isPose: Boolean = false) {
            val modelsDir = File(context.filesDir, "models")
            modelsDir.mkdirs()

            // 写入标签文件
            val labelsFile = File(modelsDir, "${name}_labels.txt")
            val labelLines = labelsStr.split(Regex("[,\n]")).map { it.trim() }.filter { it.isNotEmpty() }
            labelsFile.writeText(if (labelLines.isNotEmpty()) labelLines.joinToString("\n") else labelsStr)

            // 复制 .tflite 文件
            val srcFile = File(tflitePath)
            val destFile = File(modelsDir, "${name}.tflite")
            if (tflitePath.isNotEmpty()) {
                srcFile.copyTo(destFile, overwrite = true)
            }
            if (!destFile.exists()) {
                Log.w(TAG, "模型文件不存在: ${destFile.absolutePath}")
                return
            }

            // 判断格式：classes > 0 表示原始 YOLO（ultralytics 导出格式），classes == 0 表示 NMS 格式
            val isRawYolo = classes > 0

            availableModels.add(ModelInfo(
                name = name,
                tfliteFile = destFile.absolutePath,
                labelsFile = labelsFile.absolutePath,
                inputSize = inputSize,
                isRawYolo = isRawYolo,
                isPose = isPose,
                isCustom = true,
                customTflitePath = destFile.absolutePath
            ))
            Log.i(TAG, "自定义模型已添加: $name ($inputSize, ${if (isRawYolo) "原始YOLO" else "NMS后处理"}, ${if (isPose) "姿态" else "检测"})")
            saveCustomModels(context)
        }

        /** 编辑自定义模型（名称+标签） */
        fun editCustomModel(context: Context, index: Int, newName: String, labelsStr: String) {
            val info = availableModels.getOrNull(index) ?: return
            if (!info.isCustom) return
            val labelLines = labelsStr.split(Regex("[,\n]")).map { it.trim() }.filter { it.isNotEmpty() }
            try { File(info.labelsFile).writeText(labelLines.joinToString("\n")) } catch (_: Exception) {}
            availableModels[index] = info.copy(name = newName)
            saveCustomModels(context)
        }

        /** 删除自定义模型 */
        fun removeCustomModel(context: Context, index: Int) {
            val info = availableModels.getOrNull(index) ?: return
            if (!info.isCustom) return
            try { File(info.customTflitePath ?: info.tfliteFile).delete() } catch (_: Exception) {}
            try { File(info.labelsFile).delete() } catch (_: Exception) {}
            availableModels.removeAt(index)
            if (activeModelIndex >= availableModels.size) {
                activeModelIndex = (availableModels.size - 1).coerceAtLeast(0)
            } else if (index < activeModelIndex) {
                activeModelIndex--
            }
            saveCustomModels(context)
        }

        /** 持久化自定义模型列表到 SharedPreferences */
        fun saveCustomModels(context: Context) {
            val prefs = context.getSharedPreferences("miro_custom_models", Context.MODE_PRIVATE)
            val arr = org.json.JSONArray()
            for (m in availableModels) {
                if (!m.isCustom || m.customTflitePath == null) continue
                val labels = try { File(m.labelsFile).readText() } catch (_: Exception) { "" }
                val numClasses = labels.lines().filter { it.isNotBlank() }.size
                val obj = org.json.JSONObject().apply {
                    put("name", m.name)
                    put("labels", labels)
                    put("inputSize", m.inputSize)
                    put("classes", numClasses)
                    put("isPose", m.isPose)
                }
                arr.put(obj)
            }
            prefs.edit().putString("models_json", arr.toString()).apply()
        }

        /** 从 SharedPreferences 恢复自定义模型 */
        fun loadCustomModels(context: Context) {
            val prefs = context.getSharedPreferences("miro_custom_models", Context.MODE_PRIVATE)
            val json = prefs.getString("models_json", null) ?: return
            try {
                val arr = org.json.JSONArray(json)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    addCustomModel(
                        context = context,
                        name = obj.getString("name"),
                        tflitePath = "",
                        labelsStr = obj.optString("labels", ""),
                        inputSize = obj.optInt("inputSize", 640),
                        classes = if (obj.optBoolean("isPose", false)) 0 else obj.optInt("classes", 0),
                        isPose = obj.optBoolean("isPose", false)
                    )
                }
            } catch (_: Exception) {}
        }
    }

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnApiDelegate: NnApiDelegate? = null

    // ── 输入缓存 ──
    private var inputBuffer: ByteBuffer? = null
    private var pixelBuffer: IntArray? = null

    // ── 输出缓存（分两种格式） ──
    private var outputBufferNMS: Array<Array<FloatArray>>? = null    // [1][300][6]
    private var outputBufferRaw: Array<Array<FloatArray>>? = null    // [1][C][N]

    // ── 当前模型信息 ──
    private var currentModelFile: String = availableModels[0].tfliteFile
    private var currentLabelsFile: String = availableModels[0].labelsFile
    private var currentInputSize: Int = availableModels[0].inputSize
    private var currentIsRawYolo: Boolean = availableModels[0].isRawYolo
    private var currentIsPose: Boolean = availableModels[0].isPose
    private var currentIsCustom: Boolean = availableModels[0].isCustom
    private var currentCustomTflitePath: String? = availableModels[0].customTflitePath

    // ── 加载 ──

    fun load(modelIndex: Int = 0) {
        activeModelIndex = modelIndex
        val model = availableModels.getOrElse(modelIndex) { availableModels[0] }
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
                setPrecisionLossAllowed(true)  // fp16 加速，RTX 5070 Ti 完全支持
            }
            val gpu = org.tensorflow.lite.gpu.GpuDelegate(gpuOptions)
            options.addDelegate(gpu)
            options.setAllowBufferHandleOutput(true)  // 减少 GPU→CPU 拷贝
            gpuDelegate = gpu
            gpuAttempted = true
            usedDevice = "GPU"
            Log.i(TAG, "GPU delegate added.")
        } catch (e: Exception) {
            Log.w(TAG, "GPU unavailable: ${e.message}")
            gpuDelegate = null
            usedDevice = "CPU"
        }

        try {
            return Interpreter(model, options)
        } catch (e: Exception) {
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
        val yuv = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, imgW, imgH, null)
        val out = java.io.ByteArrayOutputStream()
        yuv.compressToJpeg(android.graphics.Rect(0, 0, imgW, imgH), 80, out)
        val jpegData = out.toByteArray()
        var bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
        // 竖屏时相机输出仍为横屏（rotationDeg=90/270），把图转正再检测
        if (rotationDeg == 90 || rotationDeg == 270) {
            val matrix = android.graphics.Matrix()
            matrix.postRotate(rotationDeg.toFloat())
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
        val results = detect(bitmap)
        bitmap.recycle()
        return results
    }

    fun detectFromNV21Direct(nv21: ByteArray, imgW: Int, imgH: Int): List<DetectionResult> {
        if (!isLoaded) return emptyList()
        val input = preprocessNV21(nv21, imgW, imgH)
        if (currentIsRawYolo) {
            val output = reuseOutputRaw()
            interpreter?.run(input, output)
            return postprocessRawYolo(output, imgW, imgH)
        } else {
            val output = reuseOutputNMS()
            interpreter?.run(input, output)
            return postprocess(output[0], imgW, imgH)
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

    private fun preprocessNV21(nv21: ByteArray, imgW: Int, imgH: Int): ByteBuffer {
        val s = currentInputSize
        val scale = minOf(s.toFloat() / imgW, s.toFloat() / imgH)
        val sw = (imgW * scale).toInt()
        val sh = (imgH * scale).toInt()
        val padX = (s - sw) / 2f
        val padY = (s - sh) / 2f

        val buf = inputBuffer ?: ByteBuffer.allocateDirect(4 * 3 * s * s).also {
            inputBuffer = it
        }
        buf.rewind()
        buf.order(ByteOrder.nativeOrder())

        val yStride = imgW
        val uvStart = imgW * imgH

        for (outY in 0 until s) {
            for (outX in 0 until s) {
                val srcX = ((outX - padX) * imgW / sw).toInt().coerceIn(0, imgW - 1)
                val srcY = ((outY - padY) * imgH / sh).toInt().coerceIn(0, imgH - 1)

                val yIdx = srcY * yStride + srcX
                val uvIdx = uvStart + (srcY / 2) * imgW + (srcX / 2) * 2
                val y = nv21[yIdx].toInt() and 0xFF
                val u = nv21[uvIdx].toInt() and 0xFF
                val v = nv21[uvIdx + 1].toInt() and 0xFF

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
            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue
                val ix1 = maxOf(sorted[i].x1(1f), sorted[j].x1(1f))
                val iy1 = maxOf(sorted[i].y1(1f), sorted[j].y1(1f))
                val ix2 = minOf(sorted[i].x2(1f), sorted[j].x2(1f))
                val iy2 = minOf(sorted[i].y2(1f), sorted[j].y2(1f))
                val iw = (ix2 - ix1).coerceAtLeast(0f)
                val ih = (iy2 - iy1).coerceAtLeast(0f)
                val inter = iw * ih
                val areaI = (sorted[i].x2(1f) - sorted[i].x1(1f)) * (sorted[i].y2(1f) - sorted[i].y1(1f))
                val areaJ = (sorted[j].x2(1f) - sorted[j].x1(1f)) * (sorted[j].y2(1f) - sorted[j].y1(1f))
                val union = areaI + areaJ - inter
                if (union > 0f && inter / union > iouThreshold) suppressed[j] = true
            }
        }

        return result
    }

    // ── 释放 ──

    fun close() {
        try { nnApiDelegate?.close() } catch (_: Exception) {}
        try { gpuDelegate?.close() } catch (_: Exception) {}
        try { interpreter?.close() } catch (_: Exception) {}
        interpreter = null
        gpuDelegate = null
        nnApiDelegate = null
        inputBuffer = null
        pixelBuffer = null
        outputBufferNMS = null
        outputBufferRaw = null
        isLoaded = false
    }
}
