package com.n0va.detection.detection

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 模型管理器：负责 TFLite 模型列表、切换、导入/删除/编辑的持久化。
 * 与 [TFLiteDetector] 解耦——ModelManager 管「有哪些模型」，
 * TFLiteDetector 管「当前模型如何做推理」。
 */
class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelManager"
    }

    data class ModelInfo(
        val name: String,
        val tfliteFile: String,
        val labelsFile: String,
        val inputSize: Int,
        val isRawYolo: Boolean = false,
        val isPose: Boolean = false,
        val isCustom: Boolean = false,
        val customTflitePath: String? = null
    )

    val availableModels = mutableListOf(
        ModelInfo("YOLO26n 640", "model/yolo26n_float32.tflite", "model/coco_classes.txt", 640),
        ModelInfo("Pose 11n 640", "model/yolo11n-pose.tflite", "model/pose_classes.txt", 640, isRawYolo = true, isPose = true),
    )

    var activeModelIndex = 0

    val activeModelName: String
        get() = availableModels.getOrElse(activeModelIndex) { availableModels[0] }.name

    val currentModelInfo: ModelInfo
        get() = availableModels.getOrElse(activeModelIndex) { availableModels[0] }

    // ── 自定义模型 CRUD ──

    fun addCustomModel(name: String, tflitePath: String, labelsStr: String, inputSize: Int, classes: Int, isPose: Boolean = false, saveImmediately: Boolean = true) {
        val modelsDir = File(context.filesDir, "models")
        modelsDir.mkdirs()

        val labelsFile = File(modelsDir, "${name}_labels.txt")
        val labelLines = labelsStr.split(Regex("[,\n]")).map { it.trim() }.filter { it.isNotEmpty() }
        labelsFile.writeText(if (labelLines.isNotEmpty()) labelLines.joinToString("\n") else labelsStr)

        val srcFile = File(tflitePath)
        val destFile = File(modelsDir, "${name}.tflite")
        if (tflitePath.isNotEmpty()) {
            srcFile.copyTo(destFile, overwrite = true)
        }
        if (!destFile.exists()) {
            Log.w(TAG, "模型文件不存在: ${destFile.absolutePath}")
            return
        }

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
        if (saveImmediately) saveCustomModels()
    }

    fun editCustomModel(index: Int, newName: String, labelsStr: String) {
        val info = availableModels.getOrNull(index) ?: return
        if (!info.isCustom) return
        val labelLines = labelsStr.split(Regex("[,\n]")).map { it.trim() }.filter { it.isNotEmpty() }
        try { File(info.labelsFile).writeText(labelLines.joinToString("\n")) } catch (_: Exception) {}
        availableModels[index] = info.copy(name = newName)
        saveCustomModels()
    }

    fun removeCustomModel(index: Int) {
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
        saveCustomModels()
    }

    // ── 持久化 ──

    fun saveCustomModels() {
        val prefs = context.getSharedPreferences("miro_custom_models", Context.MODE_PRIVATE)
        val arr = JSONArray()
        for (m in availableModels) {
            if (!m.isCustom || m.customTflitePath == null) continue
            val labels = try { File(m.labelsFile).readText() } catch (_: Exception) { "" }
            val numClasses = labels.lines().filter { it.isNotBlank() }.size
            val obj = JSONObject().apply {
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

    fun loadCustomModels() {
        val prefs = context.getSharedPreferences("miro_custom_models", Context.MODE_PRIVATE)
        val json = prefs.getString("models_json", null) ?: return
        try {
            val arr = JSONArray(json)
            var loadedCount = 0
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                addCustomModel(
                    name = obj.getString("name"),
                    tflitePath = "",
                    labelsStr = obj.optString("labels", ""),
                    inputSize = obj.optInt("inputSize", 640),
                    classes = if (obj.optBoolean("isPose", false)) 0 else obj.optInt("classes", 0),
                    isPose = obj.optBoolean("isPose", false),
                    saveImmediately = false
                )
                loadedCount++
            }
            if (loadedCount > 0) saveCustomModels()
        } catch (_: Exception) {}
    }
}
