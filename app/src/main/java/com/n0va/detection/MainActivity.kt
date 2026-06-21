package com.n0va.detection

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.view.PreviewView
import androidx.camera.core.CameraSelector
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import com.n0va.detection.camera.CameraManager
import com.n0va.detection.ui.MainScreen

class MainActivity : ComponentActivity() {

    companion object {
        private const val PERM_CAMERA = Manifest.permission.CAMERA
    }

    private val viewModel: MainViewModel by viewModels()
    private var cameraManager: CameraManager? = null
    private var pendingPreviewView: PreviewView? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.handleFile(uri)
    }

    private val modelPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri -> viewModel.importModel(uri, this) }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingPreviewView?.let { pv -> startCamera(pv) }
        } else {
            Toast.makeText(this, "需要相机权限", Toast.LENGTH_LONG).show()
        }
    }

    private val mediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "需要图片访问权限才能查看已保存的记录", Toast.LENGTH_LONG).show()
        }
    }

    private fun startCamera(pv: PreviewView) {
        if (cameraManager == null) {
            cameraManager = CameraManager(
                context = this,
                lifecycleOwner = this,
                onNv21Frame = { nv21, w, h, rot, ts ->
                    viewModel.cameraFrameW = w
                    viewModel.cameraFrameH = h
                    viewModel.submitFrame(nv21, w, h, rot, ts)
                }
            )
        }
        cameraManager?.startPreview(pv)
        viewModel.setMirrorX(cameraManager?.lensFacing == CameraSelector.LENS_FACING_FRONT)
        viewModel.setCameraManager(cameraManager ?: return)
        viewModel.addCameraLog("相机已启动", isSystem = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        viewModel.addCameraLog("初始化 TFLite 引擎...", isSystem = true)

        setContent {
            val detections by viewModel.detections.collectAsState()
            val cameraLog by viewModel.cameraLog.collectAsState()
            val fileLog by viewModel.fileLog.collectAsState()
            val modelLoaded by viewModel.modelLoaded.collectAsState()
            val isDetecting by viewModel.isDetecting.collectAsState()
            val fps by viewModel.fps.collectAsState()
            val frameW by viewModel.frameW.collectAsState()
            val frameH by viewModel.frameH.collectAsState()
            val mediaBitmap by viewModel.mediaBitmap.collectAsState()
            val savedImages by viewModel.savedImages.collectAsState()
            val confThreshold by viewModel.confThreshold.collectAsState()
            val iouThreshold by viewModel.iouThreshold.collectAsState()
            val isProcessing by viewModel.isProcessing.collectAsState()
            val processingProgress by viewModel.processingProgress.collectAsState()
            val activeModelIndex by viewModel.activeModelIndex.collectAsState()
            val isSwitchingModel by viewModel.isSwitchingModel.collectAsState()
            val showBoxes by viewModel.showBoxes.collectAsState()
            val isFlashOn by viewModel.isFlashOn.collectAsState()
            val mirrorX by viewModel.mirrorX.collectAsState()
            val isDarkTheme by viewModel.isDarkTheme.collectAsState()
            val drawBoxesOnRecording by viewModel.drawBoxesOnRecording.collectAsState()
            val autoSaveEnabled by viewModel.autoSaveEnabled.collectAsState()
            val autoSaveClass by viewModel.autoSaveClass.collectAsState()
            val isRecording by viewModel.isRecording.collectAsState()
            var currentZoom by remember { mutableStateOf(1f) }

            // 同步系统导航栏颜色到主题
            val activity = LocalContext.current as? androidx.activity.ComponentActivity
            LaunchedEffect(isDarkTheme) {
                val w = activity?.window ?: return@LaunchedEffect
                w.navigationBarColor = android.graphics.Color.parseColor(if (isDarkTheme) "#2E2E2E" else "#F0F0F0")
                w.statusBarColor = android.graphics.Color.parseColor(if (isDarkTheme) "#1E1E1E" else "#E8E8E8")
                androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).apply {
                    isAppearanceLightStatusBars = !isDarkTheme
                    isAppearanceLightNavigationBars = !isDarkTheme
                }
            }

            MainScreen(
                startCamera = { pv ->
                    if (ContextCompat.checkSelfPermission(this@MainActivity, PERM_CAMERA)
                        != PackageManager.PERMISSION_GRANTED) {
                        pendingPreviewView = pv
                        permissionLauncher.launch(PERM_CAMERA)
                    } else {
                        startCamera(pv)
                    }
                },
                stopCamera = { cameraManager?.stopPreview() },
                detections = detections,
                frameWidth = frameW,
                frameHeight = frameH,
                cameraLogEntries = cameraLog,
                fileLogEntries = fileLog,
                modelLoaded = modelLoaded,
                isDetecting = isDetecting,
                fps = fps,
                mediaBitmap = mediaBitmap,
                savedImages = savedImages,
                numClasses = com.n0va.detection.detection.TFLiteDetector.numClasses,
                modelLabels = com.n0va.detection.detection.TFLiteDetector.labels,
                confThreshold = confThreshold,
                iouThreshold = iouThreshold,
                isProcessing = isProcessing,
                processingProgress = processingProgress,
                onSelectFile = { filePicker.launch("*/*") },
                onToggleDetection = { viewModel.toggleDetection() },
                onTabSelected = { tab ->
                    viewModel.onTabSelected(tab)
                    if (tab == 1) {
                        if (ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.READ_MEDIA_IMAGES
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            if (shouldShowRequestPermissionRationale(
                                    Manifest.permission.READ_MEDIA_IMAGES
                                )
                            ) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "需要图片访问权限才能查看已保存的检测记录",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            mediaPermissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
                        }
                    }
                },
                onConfThresholdChange = { viewModel.updateConfThreshold(it) },
                onIouThresholdChange = { viewModel.updateIouThreshold(it) },
                onSaveFrame = { viewModel.saveCurrentFrame() },
                onSwitchCamera = {
                    cameraManager?.switchCamera()
                    viewModel.setMirrorX(cameraManager?.lensFacing == CameraSelector.LENS_FACING_FRONT)
                    if (cameraManager != null) viewModel.setCameraManager(cameraManager!!)
                },
                onRefreshRecords = { viewModel.loadSavedImages() },
                showBoxes = showBoxes,
                isFlashOn = isFlashOn,
                mirrorX = mirrorX,
                isDarkTheme = isDarkTheme,
                onToggleTheme = { viewModel.toggleTheme() },
                drawBoxesOnRecording = drawBoxesOnRecording,
                onToggleDrawBoxes = { viewModel.toggleDrawBoxesOnRecording() },
                autoSaveEnabled = autoSaveEnabled,
                onToggleAutoSave = { viewModel.toggleAutoSaveEnabled() },
                autoSaveClass = autoSaveClass,
                onAutoSaveClassChange = { viewModel.setAutoSaveClass(it) },
                isRecording = isRecording,
                onToggleRecording = { viewModel.toggleRecording() },
                onToggleBoxes = { viewModel.toggleBoxes() },
                onToggleFlash = {
                    viewModel.toggleFlash()
                    cameraManager?.toggleFlash()
                },
                onTapFocus = { x, y, w, h -> cameraManager?.startFocus(x, y, w, h) },
                onPinchZoom = { zoomDelta ->
                    currentZoom = (currentZoom * zoomDelta).coerceIn(1f, 8f)
                    cameraManager?.setZoom(currentZoom)
                },
                onDeleteRecord = { uri -> viewModel.deleteSavedImage(uri) },
                availableModels = com.n0va.detection.detection.TFLiteDetector.availableModels.map { it.name },
                activeModelIndex = activeModelIndex,
                onSwitchModel = { viewModel.switchModel(it) },
                isSwitchingModel = isSwitchingModel,
                onImportModel = {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/octet-stream"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                            "application/octet-stream",
                            "application/x-tflite",
                            "*/*"
                        ))
                    }
                    try {
                        modelPicker.launch(intent)
                    } catch (_: Exception) {
                        modelPicker.launch(intent.apply { type = "*/*" })
                    }
                },
                onResetSettings = { viewModel.resetSettings() },
                onDeleteModel = { index -> viewModel.deleteCustomModel(index) },
                onEditModel = { index, name, labels -> viewModel.editCustomModel(index, name, labels) },
                pendingImport = viewModel.pendingImport.collectAsState().value,
                onConfirmImport = { name, labels -> viewModel.confirmImport(name, labels) },
                onCancelImport = { viewModel.cancelImport() }
            )
        }
    }

    override fun onDestroy() {
        cameraManager?.shutdown()
        cameraManager = null
        super.onDestroy()
    }
}
