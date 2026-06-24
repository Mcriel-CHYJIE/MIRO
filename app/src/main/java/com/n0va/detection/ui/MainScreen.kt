package com.n0va.detection.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.n0va.detection.detection.DetectionResult
import com.n0va.detection.MainViewModel.SavedImage
import com.n0va.detection.ui.camera.CameraPreviewTab
import com.n0va.detection.ui.components.LogEntry
import com.n0va.detection.ui.file.FilePreviewTab
import com.n0va.detection.ui.records.RecordsTab
import com.n0va.detection.ui.settings.SettingsTab
import com.n0va.detection.ui.theme.LocalTheme
import com.n0va.detection.ui.theme.MiroTheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Composable
fun MainScreen(
    startCamera: (PreviewView) -> Unit,
    stopCamera: () -> Unit,
    detections: List<DetectionResult>,
    frameWidth: Int,
    frameHeight: Int,
    cameraLogEntries: List<LogEntry>,
    fileLogEntries: List<LogEntry>,
    modelLoaded: Boolean,
    isDetecting: Boolean,
    fps: Int,
    mediaBitmap: Bitmap?,
    savedImages: List<SavedImage>,
    numClasses: Int,
    modelLabels: List<String>,
    confThreshold: Float,
    iouThreshold: Float,
    isProcessing: Boolean,
    processingProgress: Pair<Int, Int>,
    onSelectFile: () -> Unit,
    onToggleDetection: () -> Unit,
    onTabSelected: (Int) -> Unit,
    onConfThresholdChange: (Float) -> Unit,
    onIouThresholdChange: (Float) -> Unit,
    onSaveFrame: () -> Unit = {},
    onSwitchCamera: () -> Unit = {},
    onRefreshRecords: () -> Unit = {},
    onDeleteRecord: (Uri) -> Unit = {},
    availableModels: List<String>,
    activeModelIndex: Int,
    onSwitchModel: (Int) -> Unit,
    onImportModel: () -> Unit = {},
    onResetSettings: () -> Unit = {},
    onDeleteModel: (Int) -> Unit = {},
    onEditModel: (Int, String, String) -> Unit = { _, _, _ -> },
    autoSaveEnabled: Boolean = false,
    onToggleAutoSave: () -> Unit = {},
    autoSaveClass: String = "",
    onAutoSaveClassChange: (String) -> Unit = {},
    pendingImport: com.n0va.detection.MainViewModel.ImportCandidate? = null,
    onConfirmImport: (String, String) -> Unit = { _, _ -> },
    onCancelImport: () -> Unit = {},
    isDarkTheme: Boolean = true,
    onToggleTheme: () -> Unit = {},
    drawBoxesOnRecording: Boolean = true,
    onToggleDrawBoxes: () -> Unit = {},
    isRecording: Boolean = false,
    onToggleRecording: () -> Unit = {},
    onToggleFlash: () -> Unit = {},
    onToggleBoxes: () -> Unit = {},
    onTapFocus: (Float, Float, Int, Int) -> Unit = { _, _, _, _ -> },
    onPinchZoom: (Float) -> Unit = {},
    showBoxes: Boolean = true,
    isFlashOn: Boolean = false,
    mirrorX: Boolean = false,
    isSwitchingModel: Boolean = false,
    isStreaming: Boolean = false,
    onToggleStreaming: () -> Unit = {},
    webcamPort: String = "8080",
    onWebcamPortChange: (String) -> Unit = {},
    webcamQuality: Int = 80,
    onWebcamQualityChange: (Int) -> Unit = {},
    webcamResolution: String = "720p",
    onWebcamResolutionChange: (String) -> Unit = {},
    localIp: String = ""
) {
    val theme = if (isDarkTheme) MiroTheme.Dark else MiroTheme.Light
    CompositionLocalProvider(LocalTheme provides theme) {
        var tabIndex by remember { mutableIntStateOf(0) }
        val tabs = listOf(
            Icons.Default.CameraAlt,
            Icons.Default.Collections,
            Icons.Default.Folder,
            Icons.Default.Settings
        )

        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                bottomBar = {
                    Surface(
                        color = theme.navBar,
                        tonalElevation = 0.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            tabs.forEachIndexed { i, icon ->
                                val iconScale by animateFloatAsState(
                                    targetValue = if (tabIndex == i) 1.2f else 1f,
                                    animationSpec = spring(dampingRatio = 0.5f, stiffness = 300f),
                                    label = "nav_scale_$i"
                                )
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) {
                                            if (tabIndex != i) {
                                                onTabSelected(i)
                                                tabIndex = i
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(26.dp)
                                            .graphicsLayer(scaleX = iconScale, scaleY = iconScale),
                                        tint = if (tabIndex == i) theme.accent else Color(0xFF888888)
                                    )
                                }
                            }
                        }
                    }
                }
            ) { pad ->
                Box(Modifier.fillMaxSize().background(theme.background).padding(pad)) {
                AnimatedContent(
                    targetState = tabIndex,
                    transitionSpec = {
                        fadeIn(tween(200)).togetherWith(fadeOut(tween(200)))
                    },
                    label = "tab_content"
                ) { index ->
                    when (index) {
                        0 -> CameraPreviewTab(
                        startCamera = startCamera,
                        stopCamera = stopCamera,
                        detections = detections,
                        frameWidth = frameWidth,
                        frameHeight = frameHeight,
                        logEntries = cameraLogEntries,
                        modelLoaded = modelLoaded,
                        isDetecting = isDetecting,
                        fps = fps,
                        showBoxes = showBoxes,
                        isFlashOn = isFlashOn,
                        onToggleDetection = onToggleDetection,
                        onSaveFrame = onSaveFrame,
                        onSwitchCamera = onSwitchCamera,
                        onToggleFlash = onToggleFlash,
                        onToggleBoxes = onToggleBoxes,
                        mirrorX = mirrorX,
                        isRecording = isRecording,
                        modelName = availableModels.getOrNull(activeModelIndex) ?: "",
                        onToggleRecording = onToggleRecording,
                        onTapFocus = onTapFocus,
                        onPinchZoom = onPinchZoom,
                        isStreaming = isStreaming,
                        onToggleStreaming = onToggleStreaming
                    )
                    1 -> RecordsTab(
                        savedImages = savedImages,
                        onRefresh = onRefreshRecords,
                        onDelete = onDeleteRecord
                    )
                    2 -> FilePreviewTab(
                        mediaBitmap = mediaBitmap,
                        detections = detections,
                        frameWidth = frameWidth,
                        frameHeight = frameHeight,
                        logEntries = fileLogEntries,
                        isProcessing = isProcessing,
                        processingProgress = processingProgress,
                        onSelectFile = onSelectFile
                    )
                    3 -> SettingsTab(
                        modelLoaded = modelLoaded,
                        numClasses = numClasses,
                        labels = modelLabels,
                        device = com.n0va.detection.detection.TFLiteDetector.usedDevice,
                        confThreshold = confThreshold,
                        iouThreshold = iouThreshold,
                        onConfThresholdChange = onConfThresholdChange,
                        onIouThresholdChange = onIouThresholdChange,
                        availableModels = availableModels,
                        activeModelIndex = activeModelIndex,
                        onSwitchModel = onSwitchModel,
                        isDarkTheme = isDarkTheme,
                        onToggleTheme = onToggleTheme,
                        drawBoxesOnRecording = drawBoxesOnRecording,
                        onToggleDrawBoxes = onToggleDrawBoxes,
                        autoSaveEnabled = autoSaveEnabled,
                        onToggleAutoSave = onToggleAutoSave,
                        autoSaveClass = autoSaveClass,
                        onAutoSaveClassChange = onAutoSaveClassChange,
                        onImportModel = onImportModel,
                        onResetSettings = onResetSettings,
                        onDeleteModel = onDeleteModel,
                        onEditModel = onEditModel,
                        webcamPort = webcamPort,
                        onWebcamPortChange = onWebcamPortChange,
                        webcamQuality = webcamQuality,
                        onWebcamQualityChange = onWebcamQualityChange,
                        webcamResolution = webcamResolution,
                        onWebcamResolutionChange = onWebcamResolutionChange,
                        localIp = localIp
                    )
                }
            }
            }
            }

        // ── 模型切换加载动画（全屏半透明遮罩） ──
        if (isSwitchingModel) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = theme.accent,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "加载模型中…",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        }

        // ── 导入模型确认对话框 ──
        if (pendingImport != null) {
            val pi = pendingImport
            ImportDialog(
                inputSize = pi.inputSize,
                numClasses = pi.numClasses,
                isPose = pi.isPose,
                originalName = pi.originalName,
                onConfirm = onConfirmImport,
                onCancel = onCancelImport
            )
        }
    }
}

// ── 导入模型确认对话框（暗色卡片风格，与删除弹窗一致） ──

@Composable
private fun ImportDialog(
    inputSize: Int,
    numClasses: Int,
    isPose: Boolean,
    originalName: String,
    onConfirm: (String, String) -> Unit,
    onCancel: () -> Unit
) {
    val autoName = remember(originalName) {
        originalName.substringBeforeLast(".").ifBlank { "Custom $inputSize" }
    }
    var modelName by remember { mutableStateOf(autoName) }
    val labelValues = remember {
        mutableStateListOf<String>().apply {
            val count = if (numClasses > 0) numClasses else 1
            repeat(count) { add("") }
        }
    }
    val classLabels = remember(numClasses, isPose) {
        if (numClasses > 0) (0 until numClasses).map { "class_$it" }
        else if (isPose) listOf("person")
        else listOf("object")
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x88000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onCancel() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 300.dp, max = 340.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF252525))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { /* 阻止点击穿透 */ },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))
            Text("导入模型", color = Color(0xFFE0E0E0), fontSize = 17.sp,
                fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Text("检测到: ${inputSize}×${inputSize}  ${if (isPose) "姿态" else "${numClasses}类"}",
                color = Color(0xFF888888), fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))

            // 名称
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("名称", color = Color(0xFFAAAAAA), fontSize = 13.sp,
                    modifier = Modifier.width(48.dp))
                Box(
                    modifier = Modifier.weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF333333))
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    BasicTextField(
                        value = modelName,
                        onValueChange = { modelName = it },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = 13.sp, color = Color(0xFFE0E0E0)
                        ),
                        cursorBrush = SolidColor(Color(0xFF07C160)),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            Box {
                                if (modelName.isEmpty())
                                    Text("模型名称", color = Color(0xFF666666), fontSize = 13.sp)
                                inner()
                            }
                        }
                    )
                }
                }
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = Color(0xFF333333), thickness = 0.5.dp)
                Spacer(Modifier.height(8.dp))

            // 类别（每个类别独立输入框，可滚动）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                classLabels.forEachIndexed { i, defaultLabel ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(defaultLabel, color = Color(0xFFAAAAAA), fontSize = 13.sp,
                            modifier = Modifier.width(56.dp))
                        Box(
                            modifier = Modifier.weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF333333))
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                        ) {
                            BasicTextField(
                                value = labelValues[i],
                                onValueChange = { labelValues[i] = it },
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(
                                    fontSize = 13.sp, color = Color(0xFFE0E0E0)
                                ),
                                cursorBrush = SolidColor(Color(0xFF07C160)),
                                modifier = Modifier.fillMaxWidth(),
                                decorationBox = { inner ->
                                    Box {
                                        if (labelValues[i].isEmpty())
                                            Text(defaultLabel, color = Color(0xFF666666), fontSize = 13.sp)
                                        inner()
                                    }
                                }
                            )
                        }
                    }
                    if (i < classLabels.size - 1) Spacer(Modifier.height(6.dp))
                }
            }
            Spacer(Modifier.height(4.dp))

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = Color(0xFF333333), thickness = 0.5.dp)

            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text("取消", color = Color(0xFF888888), fontSize = 14.sp)
                }
                Box(
                    modifier = Modifier
                        .width(0.5.dp)
                        .height(48.dp)
                        .background(Color(0xFF333333))
                )
                TextButton(
                    onClick = {
                        val name = modelName.ifBlank { "Custom $inputSize" }
                        val labels = classLabels.mapIndexed { index, default ->
                            labelValues[index].ifBlank { default }
                        }.joinToString(",")
                        onConfirm(name, labels)
                    },
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text("导入", color = Color(0xFF07C160), fontSize = 14.sp,
                        fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
