package com.n0va.detection.camera

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.Executors

/**
 * 相机管理：负责 CameraX 生命周期、NV21 帧提取、缩放/对焦/闪光灯/录像。
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onNv21Frame: (ByteArray, Int, Int, Int, Long) -> Unit
) {
    companion object {
        private const val TAG = "CameraManager"
    }

    /** 前后摄像头状态 */
    var lensFacing = CameraSelector.LENS_FACING_BACK
    private var currentPreview: PreviewView? = null
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var recorderRef: Recorder? = null
    private var overlayRecorder: OverlayVideoRecorder? = null
    private var overlayOutputFile: java.io.File? = null

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var nv21Cache = ByteArray(640 * 480 * 3 / 2)

    /** 当前帧尺寸和旋转角度 */
    var frameW = 0
        private set
    var frameH = 0
        private set
    var frameRotation = 0
        private set

    /** 当前缩放比例 */
    var zoomRatio = 1f
        private set

    /** 闪光灯状态 */
    var isTorchOn = false
        private set

    /** 最大缩放 */
    val maxZoomRatio: Float
        get() = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f

    /** 最小缩放 */
    val minZoomRatio: Float
        get() = camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f

    /** 是否正在录像 */
    var isRecording = false
        private set

    // ── 启动 ──

    /** 启动相机预览和分析（始终绑定 VideoCapture 以便随时录像） */
    fun startPreview(pv: PreviewView) {
        currentPreview = pv
        val selector = if (lensFacing == CameraSelector.LENS_FACING_FRONT)
            CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get(); cameraProvider = provider
                val rotation = pv.display?.rotation ?: 0

                val preview = Preview.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setTargetRotation(rotation).build()
                    .also { it.setSurfaceProvider(pv.surfaceProvider) }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(Size(640, 480))
                    .setTargetRotation(rotation).build()
                    .also { it.setAnalyzer(cameraExecutor) { ip -> analyzeFrame(ip) } }

                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.LOWEST))
                    .build()
                recorderRef = recorder
                videoCapture = VideoCapture.withOutput(recorder)

                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    lifecycleOwner, selector, preview, analysis, videoCapture
                )
            } catch (e: Exception) {
                Log.e(TAG, "相机启动失败", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // ── 录像 ──

    /** 开始录像，保存到指定文件 */
    fun startRecording(file: File) {
        val recorder = recorderRef ?: return
        if (isRecording) return
        try {
            val outputOptions = FileOutputOptions.Builder(file).build()
            val pendingRecording = recorder.prepareRecording(context, outputOptions)
            activeRecording = pendingRecording.start(
                ContextCompat.getMainExecutor(context)
            ) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        isRecording = true
                        Log.i(TAG, "录像开始: ${file.absolutePath}")
                    }
                    is VideoRecordEvent.Finalize -> {
                        isRecording = false
                        activeRecording = null
                        onRecordingFinished(file)
                    }
                    else -> {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "录像启动失败", e)
            isRecording = false
            activeRecording = null
        }
    }

    /**
     * 开始录像（带检测框叠加）。
     * 使用 OverlayVideoRecorder 替代 CameraX VideoCapture，把检测框绘制到每一帧。
     */
    fun startOverlayRecording(file: File, w: Int, h: Int) {
        if (isRecording) return
        try {
            overlayOutputFile = file
            overlayRecorder = OverlayVideoRecorder(
                outputFile = file,
                frameW = w,
                frameH = h,
                drawBoxes = true
            )
            if (overlayRecorder?.start() == true) {
                isRecording = true
                Log.i(TAG, "叠加录像开始: ${file.absolutePath}")
            } else {
                overlayRecorder = null
                Log.e(TAG, "叠加录像启动失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "叠加录像启动失败", e)
            overlayRecorder = null
        }
    }

    /** 叠加录像模式下传入当前检测结果 */
    fun setOverlayDetections(dets: List<com.n0va.detection.detection.DetectionResult>, rot: Int) {
        overlayRecorder?.setDetections(dets, rot)
    }

    private var onRecordingFinished: (File) -> Unit = {}

    fun setOnRecordingFinished(cb: (File) -> Unit) {
        onRecordingFinished = cb
    }

    /** 停止录像 */
    fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
        val ovRecorder = overlayRecorder
        if (ovRecorder != null) {
            overlayRecorder = null
            ovRecorder.stop()
            isRecording = false
            // overlay 模式同步完成，触发回调
            onRecordingFinished(overlayOutputFile ?: java.io.File(""))
            overlayOutputFile = null
            return
        }
        isRecording = false
    }

    // ── 前后切换 ──

    fun switchCamera() {
        stopRecording()
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        isTorchOn = false
        currentPreview?.let { startPreview(it) }
    }

    // ── 缩放 ──

    fun setZoom(ratio: Float) {
        val zoom = ratio.coerceIn(minZoomRatio, maxZoomRatio)
        camera?.cameraControl?.setZoomRatio(zoom)
        zoomRatio = zoom
    }

    // ── 对焦 ──

    fun startFocus(x: Float, y: Float, previewW: Int, previewH: Int) {
        val pv = currentPreview ?: return
        val point = pv.meteringPointFactory.createPoint(x / previewW, y / previewH)
        val action = FocusMeteringAction.Builder(point).build()
        camera?.cameraControl?.startFocusAndMetering(action)
    }

    // ── 闪光灯 ──

    fun toggleFlash() {
        isTorchOn = !isTorchOn
        camera?.cameraControl?.enableTorch(isTorchOn)
    }

    // ── 停止 ──

    /** 停止并释放相机 */
    fun stopPreview() {
        stopRecording()
        cameraProvider?.unbindAll()
        cameraProvider = null
        camera = null
        videoCapture = null
        recorderRef = null
        overlayRecorder = null
    }

    /** 完全关闭 */
    fun shutdown() {
        stopPreview()
        cameraExecutor.shutdown()
    }

    // ── 帧分析 ──

    private fun analyzeFrame(img: ImageProxy) {
        try {
            val w = img.width; val h = img.height
            if (w <= 0 || h <= 0) return
            val rotation = img.imageInfo.rotationDegrees
            frameW = w; frameH = h; frameRotation = rotation
            extractNv21(img, w, h)?.let { (nv21, timestamp) ->
                onNv21Frame(nv21, w, h, rotation, timestamp)
                // 叠加录像模式下转交帧到编码器
                overlayRecorder?.submitFrame(nv21, w, h, rotation, timestamp)
            }
        } catch (e: Exception) {
            Log.e(TAG, "帧分析异常", e)
        } finally {
            img.close()
        }
    }

    private fun extractNv21(img: ImageProxy, w: Int, h: Int): Pair<ByteArray, Long>? {
        return try {
            val p = img.planes; if (p.size < 3) return@extractNv21 null
            val yb = p[0].buffer; val ub = p[1].buffer; val vb = p[2].buffer
            val ys = p[0].rowStride; val us = p[1].rowStride; val vs = p[2].rowStride
            val up = p[1].pixelStride; val vp = p[2].pixelStride
            val ySize = w * h
            if (nv21Cache.size < ySize + ySize / 2) nv21Cache = ByteArray(ySize + ySize / 2)
            val nv21 = nv21Cache
            if (ys == w) { yb.get(nv21, 0, ySize) }
            else { for (r in 0 until h) { yb.position(r * ys); yb.get(nv21, r * w, w) } }
            var idx = ySize
            for (r in 0 until h / 2) for (c in 0 until w / 2) {
                nv21[idx++] = vb.get(r * vs + c * vp)
                nv21[idx++] = ub.get(r * us + c * up)
            }
            Pair(nv21, img.imageInfo.timestamp ?: System.nanoTime())
        } catch (e: Exception) { Log.e(TAG, "NV21 提取失败", e); null }
    }
}
