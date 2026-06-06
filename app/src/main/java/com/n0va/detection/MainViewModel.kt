package com.n0va.detection

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.n0va.detection.detection.DetectionResult
import com.n0va.detection.detection.TFLiteDetector
import com.n0va.detection.ui.components.LogEntry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.content.Context
import android.os.Build
import android.provider.OpenableColumns
import org.tensorflow.lite.Interpreter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicReference

class MainViewModel(application: Application) : AndroidViewModel(application) {

    data class SavedImage(
        val id: Long,
        val uri: Uri,
        val name: String,
        val dateStr: String,
        val dateMillis: Long,
        val stats: Map<String, Int> = emptyMap(),
        val isVideo: Boolean = false
    )

    companion object {
        private const val TAG = "MainViewModel"
        private const val PREFS_NAME = "miro_settings"
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_DRAW_BOXES = "draw_boxes_on_recording"
        private const val KEY_AUTO_SAVE_ENABLED = "auto_save_enabled"
        private const val KEY_AUTO_SAVE_CLASS = "auto_save_class"
        private const val KEY_CONF = "conf_threshold"
        private const val KEY_IOU = "iou_threshold"
        private const val KEY_MODEL = "active_model"
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // 从持久化恢复设置
        TFLiteDetector.confThreshold = prefs.getFloat(KEY_CONF, TFLiteDetector.confThreshold)
        TFLiteDetector.iouThreshold = prefs.getFloat(KEY_IOU, TFLiteDetector.iouThreshold)
    }

    // ── UI State ──
    private val _detections = MutableStateFlow<List<DetectionResult>>(emptyList())
    val detections: StateFlow<List<DetectionResult>> = _detections.asStateFlow()

    private val _cameraLog = MutableStateFlow<List<LogEntry>>(emptyList())
    val cameraLog: StateFlow<List<LogEntry>> = _cameraLog.asStateFlow()

    private val _fileLog = MutableStateFlow<List<LogEntry>>(emptyList())
    val fileLog: StateFlow<List<LogEntry>> = _fileLog.asStateFlow()

    private val _modelLoaded = MutableStateFlow(false)
    val modelLoaded: StateFlow<Boolean> = _modelLoaded.asStateFlow()

    private val _isDetecting = MutableStateFlow(false)
    val isDetecting: StateFlow<Boolean> = _isDetecting.asStateFlow()

    private val _fps = MutableStateFlow(0)
    val fps: StateFlow<Int> = _fps.asStateFlow()

    private val _frameW = MutableStateFlow(640)
    val frameW: StateFlow<Int> = _frameW.asStateFlow()

    private val _frameH = MutableStateFlow(480)
    val frameH: StateFlow<Int> = _frameH.asStateFlow()

    private val _mirrorX = MutableStateFlow(false)
    val mirrorX: StateFlow<Boolean> = _mirrorX.asStateFlow()

    private val _isDarkTheme = MutableStateFlow(prefs.getBoolean(KEY_DARK_THEME, true))
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _mediaBitmap = MutableStateFlow<Bitmap?>(null)
    val mediaBitmap: StateFlow<Bitmap?> = _mediaBitmap.asStateFlow()

    // ── 视频处理状态 ──
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _processingProgress = MutableStateFlow(0 to 0)  // (current, total)
    val processingProgress: StateFlow<Pair<Int, Int>> = _processingProgress.asStateFlow()

    // ── 模型切换动画 ──
    private val _isSwitchingModel = MutableStateFlow(false)
    val isSwitchingModel: StateFlow<Boolean> = _isSwitchingModel.asStateFlow()

    // ── 框显隐 ──
    private val _showBoxes = MutableStateFlow(true)
    val showBoxes: StateFlow<Boolean> = _showBoxes.asStateFlow()

    // ── 录像绘制检测框 ──
    private val _drawBoxesOnRecording = MutableStateFlow(prefs.getBoolean(KEY_DRAW_BOXES, true))
    val drawBoxesOnRecording: StateFlow<Boolean> = _drawBoxesOnRecording.asStateFlow()

    // ── 自动保存检测帧 ──
    private val _autoSaveEnabled = MutableStateFlow(prefs.getBoolean(KEY_AUTO_SAVE_ENABLED, false))
    val autoSaveEnabled: StateFlow<Boolean> = _autoSaveEnabled.asStateFlow()
    private val _autoSaveClass = MutableStateFlow(prefs.getString(KEY_AUTO_SAVE_CLASS, "") ?: "")
    val autoSaveClass: StateFlow<String> = _autoSaveClass.asStateFlow()

    // ── 闪光灯 ──
    private val _isFlashOn = MutableStateFlow(false)
    val isFlashOn: StateFlow<Boolean> = _isFlashOn.asStateFlow()

    // ── 保存的记录 ──
    private val _savedImages = MutableStateFlow<List<SavedImage>>(emptyList())
    val savedImages: StateFlow<List<SavedImage>> = _savedImages.asStateFlow()

    // ── 待导入模型 ──
    data class ImportCandidate(
        val srcPath: String,
        val originalName: String,
        val inputSize: Int,
        val numClasses: Int,
        val isPose: Boolean
    )
    private val _pendingImport = MutableStateFlow<ImportCandidate?>(null)
    val pendingImport: StateFlow<ImportCandidate?> = _pendingImport.asStateFlow()

    // ── 可调参数 ──
    private val _confThreshold = MutableStateFlow(prefs.getFloat(KEY_CONF, TFLiteDetector.confThreshold))
    val confThreshold: StateFlow<Float> = _confThreshold.asStateFlow()

    private val _iouThreshold = MutableStateFlow(prefs.getFloat(KEY_IOU, TFLiteDetector.iouThreshold))
    val iouThreshold: StateFlow<Float> = _iouThreshold.asStateFlow()

    // ── 模型切换 ──
    private val _activeModelIndex = MutableStateFlow(prefs.getInt(KEY_MODEL, TFLiteDetector.activeModelIndex))
    val activeModelIndex: StateFlow<Int> = _activeModelIndex.asStateFlow()

    val activeModelName: String
        get() = TFLiteDetector.activeModelName

    // ── 检测器 ──
    val detector = TFLiteDetector(getApplication())

    var cameraFrameW = 640
    var cameraFrameH = 480

    // ── 异步推理 ──
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val latestFrame = AtomicReference<ByteArray?>(null)
    private var infFrameW = 0
    private var infFrameH = 0
    private var infRotation = 0
    @Volatile
    private var running = false
    private var processingJob: Job? = null

    // 录像控制（由 Activity 注入 CameraManager）
    private var cameraManagerRef: com.n0va.detection.camera.CameraManager? = null

    fun setCameraManager(cm: com.n0va.detection.camera.CameraManager) {
        cameraManagerRef = cm
        cm.setOnRecordingFinished {
            loadSavedImages()
            _isRecording.value = false
        }
    }

    /** 独立录像开关 — 不依赖检测状态，随时可开可关 */
    fun toggleRecording() {
        val cm = cameraManagerRef ?: return
        if (cm.isRecording) {
            overlayDetectJob?.cancel()
            overlayDetectJob = null
            cm.stopRecording()
            _isRecording.value = false
        } else {
            try {
                val ctx = getApplication<Application>()
                val dir = java.io.File(ctx.filesDir, "MIRO")
                dir.mkdirs()
                val file = java.io.File(dir, "MIRO_${System.currentTimeMillis()}.mp4")

                if (_drawBoxesOnRecording.value) {
                    // 使用叠加录像（带检测框）
                    cm.startOverlayRecording(file, cm.frameW, cm.frameH)
                    // 启动协程持续传输检测结果到编码器
                    overlayDetectJob = scope.launch {
                        while (isActive && cm.isRecording) {
                            val dets = _detections.value
                            if (dets.isNotEmpty()) {
                                cm.setOverlayDetections(dets, cm.frameRotation)
                            }
                            delay(100)
                        }
                    }
                } else {
                    cm.startRecording(file)
                }
                _isRecording.value = true
                addCameraLog("录像开始", isSystem = true)
            } catch (e: Exception) {
                Log.e(TAG, "录像启动失败", e)
            }
        }
    }

    private var overlayDetectJob: Job? = null

    // ── 输入缓冲 ──
    private var saveFrameNv21: ByteArray? = null
    private var saveFrameW = 0
    private var saveFrameH = 0
    private var autoSaveStreak = 0  // 连续检测到目标类别的帧数
    private var lastAutoSaveMs = 0L  // 上次自动保存时间戳

    private val frameTimestamps = LongArray(30)
    private var tsIdx = 0
    private var tsCount = 0L

    private val dateFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // ── 初始化 ──
    init {
        loadModel()
    }

    private fun loadModel() {
        scope.launch {
            try {
                addCameraLog("加载 TFLite 模型...", isSystem = true)
                TFLiteDetector.loadCustomModels(getApplication())
                detector.load(TFLiteDetector.activeModelIndex)
                _modelLoaded.value = true
                _activeModelIndex.value = TFLiteDetector.activeModelIndex
                addCameraLog("TFLite 就绪: ${TFLiteDetector.numClasses}类 (${TFLiteDetector.usedDevice})", isSystem = true)
            } catch (e: Exception) {
                Log.e(TAG, "模型加载失败", e)
                _modelLoaded.value = false
                addCameraLog("加载失败: ${e.message}", isSystem = true)
            }
        }
    }

    // ── Tab 切换 ──
    fun onTabSelected(index: Int) {
        // 切出文件页 → 取消视频处理
        if (index != 2 && _isProcessing.value) {
            cancelProcessing()
        }
        // 切出文件页 → 清除文件结果
        if (index != 2) {
            _mediaBitmap.value = null
        }
        // 进入记录页 → 刷新
        if (index == 1) {
            loadSavedImages()
        }
    }

    private fun forceStopDetection() {
        running = false
        _isDetecting.value = false
        _detections.value = emptyList()
        _fps.value = 0
        // 结束检测时自动停止录像
        cameraManagerRef?.let { cm ->
            if (cm.isRecording) {
                overlayDetectJob?.cancel()
                overlayDetectJob = null
                cm.stopRecording()
                _isRecording.value = false
                loadSavedImages()
                addCameraLog("录像已保存", isSystem = true)
            }
        }
        addCameraLog("检测已停止", isSystem = true)
    }

    // ── 检测控制 ──
    fun toggleDetection() {
        _isDetecting.value = !_isDetecting.value
        if (_isDetecting.value) {
            _detections.value = emptyList()
            _fps.value = 0
            running = true
            scope.launch { inferenceLoop() }
            addCameraLog("TFLite 检测开始 (${TFLiteDetector.usedDevice})", isSystem = true)
        } else {
            forceStopDetection()
        }
    }

    fun setMirrorX(mirror: Boolean) {
        _mirrorX.value = mirror
    }

    fun toggleTheme() {
        val v = !_isDarkTheme.value
        _isDarkTheme.value = v
        prefs.edit().putBoolean(KEY_DARK_THEME, v).apply()
    }

    fun toggleDrawBoxesOnRecording() {
        val v = !_drawBoxesOnRecording.value
        _drawBoxesOnRecording.value = v
        prefs.edit().putBoolean(KEY_DRAW_BOXES, v).apply()
    }

    fun toggleAutoSaveEnabled() {
        val v = !_autoSaveEnabled.value
        _autoSaveEnabled.value = v
        prefs.edit().putBoolean(KEY_AUTO_SAVE_ENABLED, v).apply()
    }

    fun setAutoSaveClass(cls: String) {
        _autoSaveClass.value = cls
        prefs.edit().putString(KEY_AUTO_SAVE_CLASS, cls).apply()
    }

    fun submitFrame(nv21: ByteArray, w: Int, h: Int, rotation: Int, _timestamp: Long) {
        if (!running) return
        val data = ByteArray(nv21.size).also { System.arraycopy(nv21, 0, it, 0, nv21.size) }
        saveFrameNv21 = data
        saveFrameW = w
        saveFrameH = h
        infFrameW = w
        infFrameH = h
        infRotation = rotation
        latestFrame.set(data)
    }

    private suspend fun inferenceLoop() = withContext(Dispatchers.Default) {
        while (isActive && running) {
            val data = latestFrame.getAndSet(null)
            if (data == null) { delay(1); continue }
            if (!running) continue  // 停止后丢弃已入队的帧

            val w = infFrameW; val h = infFrameH
            val rotation = infRotation
            val results = detector.detectFromNV21(data, w, h, rotation)

            // 停止检测后丢弃结果
            if (!running) continue

            updateFps(System.nanoTime())

            _detections.value = results
            _frameW.value = w; _frameH.value = h
            _fps.value = calcFps()
            // 自动保存检测帧（每秒确认后保存，避免重复写入）
            if (_autoSaveEnabled.value && results.isNotEmpty()) {
                val targetClass = _autoSaveClass.value
                if (targetClass.isNotEmpty()) {
                    if (results.any { it.className == targetClass }) {
                        val now = System.currentTimeMillis()
                        if (now - lastAutoSaveMs >= 1000) {
                            lastAutoSaveMs = now
                            autoSaveStreak = 0
                            saveCurrentFrame()
                        }
                    } else {
                        autoSaveStreak = 0
                    }
                } else {
                    autoSaveStreak = 0
                }
            } else {
                autoSaveStreak = 0
            }
            if (results.isNotEmpty()) {
                val now = dateFmt.format(Date())
                appendCameraLog(results.take(5).map {
                    LogEntry(time = now, label = it.className, confidence = it.confidence)
                })
            }
        }
    }

    // ── 保存当前帧 ──
    fun saveCurrentFrame() {
        saveFrameNv21 ?: return
        val dets = _detections.value
        if (dets.isEmpty()) return

        scope.launch {
            try {
                val nv21 = saveFrameNv21 ?: return@launch
                val w = saveFrameW
                val h = saveFrameH
                val rot = infRotation

                // NV21 → Bitmap
                val yuv = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, w, h, null)
                val out = java.io.ByteArrayOutputStream()
                yuv.compressToJpeg(android.graphics.Rect(0, 0, w, h), 100, out)
                var bitmap = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size()) ?: return@launch

                // 旋转与屏幕方向一致
                if (rot != 0) {
                    val m = android.graphics.Matrix().apply { postRotate(rot.toFloat()) }
                    bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
                }

                // 画检测框（受录制绘制开关控制）
                val drawBoxes = _drawBoxesOnRecording.value
                Log.d(TAG, "保存帧: drawBoxes=$drawBoxes, dets=${dets.size}")
                if (drawBoxes) {
                val canvas = android.graphics.Canvas(bitmap)
                val boxPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.GREEN
                    strokeWidth = 5f
                    style = android.graphics.Paint.Style.STROKE
                }
                val labelPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.GREEN
                    textSize = 38f
                    style = android.graphics.Paint.Style.FILL
                }
                for (det in dets) {
                    val left = (det.cx - det.w / 2f) * bitmap.width
                    val top = (det.cy - det.h / 2f) * bitmap.height
                    val right = (det.cx + det.w / 2f) * bitmap.width
                    val bottom = (det.cy + det.h / 2f) * bitmap.height
                    canvas.drawRect(left, top, right, bottom, boxPaint)
                    canvas.drawText("${det.className} ${"%.0f".format(det.confidence * 100)}%", left, top - 10f, labelPaint)

                    // ── 姿态骨架 ──
                    val kpts = det.keypoints
                    if (kpts != null && kpts.size == 17) {
                        val bw = bitmap.width.toFloat()
                        val bh = bitmap.height.toFloat()
                        val skelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.argb(180, 0, 255, 100)
                            style = android.graphics.Paint.Style.STROKE
                            strokeWidth = 3f
                            strokeCap = android.graphics.Paint.Cap.ROUND
                        }
                        val kptPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.GREEN
                            style = android.graphics.Paint.Style.FILL
                        }
                        for (edge in com.n0va.detection.detection.PoseConstants.SKELETON) {
                            val p1 = kpts[edge[0]]
                            val p2 = kpts[edge[1]]
                            if (!p1.isVisible || !p2.isVisible) continue
                            canvas.drawLine(p1.x * bw, p1.y * bh, p2.x * bw, p2.y * bh, skelPaint)
                        }
                        for (kpt in kpts) {
                            if (!kpt.isVisible) continue
                            canvas.drawCircle(kpt.x * bw, kpt.y * bh, 6f, kptPaint)
                        }
                    }
                }
                canvas.setBitmap(null)
                }

                // ── 统计元数据 ──
                val stats = dets.groupBy({ it.className }).mapValues { (_, v) -> v.size }
                val statsJson = stats.entries.joinToString("|") { "${it.key}:${it.value}" }

                // 保存到应用私有目录（不暴露相册）
                val ctx = getApplication<Application>()
                val ts = System.currentTimeMillis()
                val dir = java.io.File(ctx.filesDir, "MIRO")
                dir.mkdirs()
                val file = java.io.File(dir, "MIRO_$ts.jpg")
                file.outputStream().use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                }
                bitmap.recycle()

                // 保存统计到 SharedPreferences
                if (statsJson.isNotEmpty()) {
                    ctx.getSharedPreferences("miro_stats", Context.MODE_PRIVATE)
                        .edit()
                        .putString("stats_$ts", statsJson)
                        .apply()
                }

                loadSavedImages()

                withContext(Dispatchers.Main) {
                    addCameraLog("已保存检测画面", isSystem = true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "保存失败", e)
            }
        }
    }

    // ── 文件处理 ──
    fun handleFile(uri: Uri) {
        // 如果正在处理视频则取消
        processingJob?.cancel()
        processingJob = null
        _isProcessing.value = false

        val ctx = getApplication<Application>()
        val type = ctx.contentResolver.getType(uri)
        if (type?.startsWith("video/") == true) processVideo(uri)
        else loadImage(uri)
    }

    fun cancelProcessing() {
        processingJob?.cancel()
        processingJob = null
        _isProcessing.value = false
        _processingProgress.value = 0 to 0
        addFileLog("处理已取消", isSystem = true)
    }

    private fun loadImage(uri: Uri) {
        scope.launch {
            try {
                addFileLog("加载图片...", isSystem = true)
                val ctx = getApplication<Application>()
                ctx.contentResolver.openInputStream(uri).use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    if (bitmap == null) { addFileLog("图片加载失败", isSystem = true); return@launch }
                    _mediaBitmap.value = bitmap
                    _frameW.value = bitmap.width; _frameH.value = bitmap.height

                    val results = detector.detect(bitmap)
                    _detections.value = results
                    addFileLog("图片检测完成: ${results.size}目标", isSystem = true)
                    results.forEach { addFileLog("${it.className} ${"%.2f".format(it.confidence)}", isSystem = false) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "图片处理失败", e)
                addFileLog("图片处理失败: ${e.message}", isSystem = true)
            }
        }
    }

    private fun processVideo(uri: Uri) {
        processingJob = scope.launch {
            try {
                _isProcessing.value = true
                _detections.value = emptyList()
                addFileLog("加载视频...", isSystem = true)

                val ctx = getApplication<Application>()
                var retriever: MediaMetadataRetriever? = null
                try {
                    retriever = MediaMetadataRetriever()
                    retriever.setDataSource(ctx, uri)
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                    val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 640
                    val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 480
                    _frameW.value = w; _frameH.value = h
                    addFileLog("视频: ${duration / 1000}s ${w}x$h", isSystem = true)

                    // 均匀采样 30 帧
                    val totalFrames = 30
                    val durationUs = duration * 1000L
                    val interval = if (durationUs > 0) durationUs / totalFrames else 1_000_000L

                    // 线程安全的最近帧引用
                    val latestFrame = AtomicReference<Bitmap?>(null)
                    val latestDets = AtomicReference<List<DetectionResult>>(emptyList())
                    var totalDetected = 0

                    // ── 显示协程：每 200ms 把最新帧推到 UI ──
                    val displayJob = launch(Dispatchers.Main) {
                        while (isActive) {
                            val frame = latestFrame.getAndSet(null)
                            if (frame != null) {
                                _mediaBitmap.value = frame
                                _detections.value = latestDets.get()
                            }
                            delay(200)
                        }
                    }

                    // ── 处理协程：全速检测，不碰 UI ──
                    for (i in 0 until totalFrames) {
                        if (!isActive) break

                        _processingProgress.value = i + 1 to totalFrames
                        val timeUs = i * interval
                        val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue

                        val dets = detector.detect(frame)
                        totalDetected += dets.size

                        // 替换最近帧，旧帧若未被显示则回收
                        val old = latestFrame.getAndSet(frame)
                        if (old != null) old.recycle()
                        latestDets.set(dets)

                        if (dets.isNotEmpty()) {
                            val now = dateFmt.format(Date())
                            appendFileLog(dets.take(3).map {
                                LogEntry(time = now, label = it.className, confidence = it.confidence)
                            })
                        }
                    }

                    displayJob.cancel()

                    // 显示最后一帧
                    val last = latestFrame.getAndSet(null)
                    if (last != null) {
                        withContext(Dispatchers.Main) {
                            _mediaBitmap.value = last
                            _detections.value = latestDets.get()
                        }
                    }

                    _isProcessing.value = false
                    _processingProgress.value = 0 to 0
                    addFileLog("视频检测完成: $totalDetected 总目标", isSystem = true)
                } finally {
                    retriever?.release()
                }
            } catch (e: CancellationException) {
                _isProcessing.value = false
                _processingProgress.value = 0 to 0
            } catch (e: Exception) {
                Log.e(TAG, "视频处理失败", e)
                _isProcessing.value = false
                _processingProgress.value = 0 to 0
                addFileLog("视频处理失败: ${e.message}", isSystem = true)
            }
        }
    }

    // ── 记录管理 ──

    fun loadSavedImages() {
        scope.launch {
            val ctx = getApplication<Application>()
            val dir = java.io.File(ctx.filesDir, "MIRO")
            if (!dir.exists()) { _savedImages.value = emptyList(); return@launch }

            try {
                val jpgs = dir.listFiles { f -> f.name.startsWith("MIRO_") && f.name.endsWith(".jpg") }
                    ?.sortedByDescending { it.lastModified() } ?: emptyList()
                val mp4s = dir.listFiles { f -> f.name.startsWith("MIRO_") && f.name.endsWith(".mp4") }
                    ?.sortedByDescending { it.lastModified() } ?: emptyList()
                val allFiles = (jpgs + mp4s).sortedByDescending { it.lastModified() }
                val images = allFiles.mapNotNull { file ->
                    val name = file.name
                    val isVideo = name.endsWith(".mp4")
                    val ext = if (isVideo) ".mp4" else ".jpg"
                    val ts = name.removePrefix("MIRO_").removeSuffix(ext)
                    val date = file.lastModified()
                    val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(date))
                    val statsJson = ctx.getSharedPreferences("miro_stats", Context.MODE_PRIVATE).getString("stats_$ts", null)
                    val stats = if (!statsJson.isNullOrBlank()) {
                        statsJson.split("|").mapNotNull { part ->
                            val kv = part.split(":", limit = 2)
                            if (kv.size == 2) kv[0] to (kv[1].toIntOrNull() ?: 0) else null
                        }.toMap()
                    } else emptyMap()
                    SavedImage(ts.hashCode().toLong(), Uri.fromFile(file), name, dateStr, date, stats, isVideo)
                }
                _savedImages.value = images
            } catch (e: Exception) {
                Log.e(TAG, "加载记录失败", e)
            }
        }
    }

    fun deleteSavedImage(uri: Uri) {
        scope.launch {
            try {
                val file = java.io.File(uri.path ?: "")
                if (file.exists()) file.delete()
                loadSavedImages()
            } catch (e: Exception) {
                Log.e(TAG, "删除记录失败", e)
            }
        }
    }

    // ── FPS ──
    private fun updateFps(timestamp: Long) {
        frameTimestamps[(tsIdx++ % 30).also { if (tsCount < 30) tsCount++ }] = timestamp
    }

    private fun calcFps(): Int {
        if (tsCount < 2) return 0
        val idx = tsIdx
        val newest = frameTimestamps[(idx - 1 + 30) % 30]
        val oldest = frameTimestamps[(idx - tsCount.toInt() + 30) % 30]
        val elapsed = newest - oldest
        return if (elapsed > 0) ((tsCount - 1) * 1_000_000_000L / elapsed).toInt() else 0
    }

    // ── 日志 ──
    fun addCameraLog(msg: String, isSystem: Boolean = false) {
        val now = dateFmt.format(Date())
        appendCameraLog(listOf(LogEntry(time = now, label = msg, isSystem = isSystem)))
    }

    private fun appendCameraLog(entries: List<LogEntry>) {
        _cameraLog.value = (_cameraLog.value + entries).takeLast(200)
    }

    fun exportCameraLog(): Uri? {
        val ctx = getApplication<Application>()
        val entries = _cameraLog.value
        if (entries.isEmpty()) return null
        try {
            val file = java.io.File(ctx.cacheDir, "miro_detection_log.csv")
            file.writer().use { w ->
                w.write("time,label,confidence,type\n")
                for (e in entries) {
                    w.write("${e.time},${e.label},${e.confidence},${if (e.isSystem) "system" else "detect"}\n")
                }
            }
            return androidx.core.content.FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", file
            )
        } catch (e: Exception) {
            Log.e(TAG, "导出日志失败", e)
            return null
        }
    }

    fun addFileLog(msg: String, isSystem: Boolean = false) {
        val now = dateFmt.format(Date())
        appendFileLog(listOf(LogEntry(time = now, label = msg, isSystem = isSystem)))
    }

    private fun appendFileLog(entries: List<LogEntry>) {
        _fileLog.value = (_fileLog.value + entries).takeLast(200)
    }

    // ── 框显隐切换 ──
    fun toggleBoxes() {
        _showBoxes.value = !_showBoxes.value
    }

    // ── 闪光灯切换 ──
    fun toggleFlash() {
        _isFlashOn.value = !_isFlashOn.value
    }

    // ── 重置设置 ──
    fun resetSettings() {
        _confThreshold.value = 0.25f
        TFLiteDetector.confThreshold = 0.25f
        _iouThreshold.value = 0.45f
        TFLiteDetector.iouThreshold = 0.45f
        _showBoxes.value = true
        _isFlashOn.value = false
        _activeModelIndex.value = 0
        addCameraLog("设置已重置", isSystem = true)
    }

    // ── 参数更新 ──
    fun updateConfThreshold(value: Float) {
        _confThreshold.value = value
        TFLiteDetector.confThreshold = value
        prefs.edit().putFloat(KEY_CONF, value).apply()
        addCameraLog("置信度阈值: $value", isSystem = true)
    }

    fun updateIouThreshold(value: Float) {
        _iouThreshold.value = value
        TFLiteDetector.iouThreshold = value
        prefs.edit().putFloat(KEY_IOU, value).apply()
        addCameraLog("NMS IoU: $value", isSystem = true)
    }

    // ── 模型切换 ──
    fun switchModel(index: Int) {
        if (index < 0 || index >= TFLiteDetector.availableModels.size) return
        if (index == TFLiteDetector.activeModelIndex) return

        // 停止检测
        if (_isDetecting.value) forceStopDetection()

        val modelName = TFLiteDetector.availableModels[index].name
        addCameraLog("切换模型: $modelName...", isSystem = true)

        _isSwitchingModel.value = true

        scope.launch {
            try {
                detector.close()
                detector.load(index)
                _modelLoaded.value = true
                _activeModelIndex.value = index
                prefs.edit().putInt(KEY_MODEL, index).apply()
                addCameraLog("模型已切换: $modelName (${TFLiteDetector.usedDevice})", isSystem = true)
            } catch (e: Exception) {
                Log.e(TAG, "模型切换失败", e)
                _modelLoaded.value = false
                addCameraLog("切换失败: ${e.message}", isSystem = true)
            } finally {
                _isSwitchingModel.value = false
            }
        }
    }

    // ── 导入自定义模型 ──

    fun importModel(uri: Uri, context: Context) {
        scope.launch {
            try {
                addCameraLog("正在导入模型...", isSystem = true)

                // 0. 提取原始文件名
                var originalName = "model"
                try {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) cursor.getString(idx)?.let { originalName = it }
                        }
                    }
                } catch (_: Exception) {}

                // 1. 复制文件到内部存储
                val modelsDir = java.io.File(context.filesDir, "models")
                modelsDir.mkdirs()
                val uniqueName = "custom_${System.currentTimeMillis()}"
                val destFile = java.io.File(modelsDir, "${uniqueName}.tflite")

                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: run {
                    addCameraLog("导入失败：无法读取文件", isSystem = true)
                    return@launch
                }

                // 2. 使用 TFLite Interpreter 读取模型属性
                var inputSize = 640
                var numClasses = 0
                var isPoseModel = false

                try {
                    val interpreter = Interpreter(destFile)
                    val inputShape = interpreter.getInputTensor(0).shape()
                    val outputShape = interpreter.getOutputTensor(0).shape()

                    // 输入形状 [1, height, width, 3] 或 [1, 3, height, width]
                    if (inputShape.size >= 4) {
                        inputSize = when {
                            inputShape[1] == 3 || inputShape[3] == 3 -> {
                                maxOf(inputShape[1], inputShape[2])
                            }
                            else -> 640
                        }
                    }

                    // 输出形状判断
                    when {
                        outputShape.size >= 3 && outputShape[1] == 56 -> {
                            // 姿态模型 [1, 56, N]
                            isPoseModel = true
                            numClasses = 0
                        }
                        outputShape.size >= 3 -> {
                            // 原始 YOLO [1, 4+cls, N]
                            numClasses = outputShape[1] - 4
                        }
                        outputShape.size >= 2 && outputShape[1] == 300 -> {
                            // NMS 格式 [1, 300, 6]
                            numClasses = 0
                        }
                        else -> {
                            numClasses = 0
                        }
                    }

                    // 防止负数
                    if (numClasses < 0) numClasses = 0

                    interpreter.close()
                } catch (e: Exception) {
                    Log.w(TAG, "模型属性读取失败，使用默认值: ${e.message}")
                }

                // 3. 设置待导入（等待用户确认名称和标签）
                _pendingImport.value = ImportCandidate(
                    srcPath = destFile.absolutePath,
                    originalName = originalName,
                    inputSize = inputSize,
                    numClasses = numClasses,
                    isPose = isPoseModel
                )
                addCameraLog("请确认模型名称和类别标签", isSystem = true)
            } catch (e: Exception) {
                Log.e(TAG, "模型导入失败", e)
                addCameraLog("导入失败: ${e.message}", isSystem = true)
            }
        }
    }

    fun confirmImport(name: String, labelsStr: String) {
        val pending = _pendingImport.value ?: return
        _pendingImport.value = null
        val ctx = getApplication<Application>()
        val displayName = name.ifBlank { "Custom ${pending.inputSize}" }
        TFLiteDetector.addCustomModel(
            context = ctx,
            name = displayName,
            tflitePath = pending.srcPath,
            labelsStr = labelsStr,
            inputSize = pending.inputSize,
            classes = if (pending.isPose) 0 else pending.numClasses,
            isPose = pending.isPose
        )
        addCameraLog("模型已导入: $displayName (${pending.inputSize}x${pending.inputSize})", isSystem = true)
    }

    fun cancelImport() {
        val pending = _pendingImport.value ?: return
        _pendingImport.value = null
        // 清理临时复制文件
        try { java.io.File(pending.srcPath).delete() } catch (_: Exception) {}
        addCameraLog("导入已取消", isSystem = true)
    }

    fun deleteCustomModel(index: Int) {
        val ctx = getApplication<Application>()
        val name = TFLiteDetector.availableModels.getOrNull(index)?.name ?: return
        val wasActive = index == TFLiteDetector.activeModelIndex
        TFLiteDetector.removeCustomModel(ctx, index)
        _activeModelIndex.value = TFLiteDetector.activeModelIndex
        addCameraLog("模型已删除: $name", isSystem = true)
        // 如果删的是当前模型，重新加载
        if (wasActive && TFLiteDetector.availableModels.isNotEmpty()) {
            scope.launch {
                detector.close()
                detector.load(TFLiteDetector.activeModelIndex)
                _modelLoaded.value = true
            }
        }
    }

    fun editCustomModel(index: Int, name: String, labels: String) {
        val ctx = getApplication<Application>()
        TFLiteDetector.editCustomModel(ctx, index, name, labels)
        _activeModelIndex.value = TFLiteDetector.activeModelIndex
        addCameraLog("模型已更新: $name", isSystem = true)
        // 如果更新的是当前模型，重新加载标签
        if (index == TFLiteDetector.activeModelIndex) {
            scope.launch {
                detector.close()
                detector.load(index)
                _modelLoaded.value = true
            }
        }
    }

    // ── 清理 ──
    override fun onCleared() {
        running = false
        processingJob?.cancel()
        scope.cancel()
        detector.close()
        super.onCleared()
    }
}
