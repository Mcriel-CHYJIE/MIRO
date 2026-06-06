package com.n0va.detection.ui.camera

import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.n0va.detection.detection.DetectionResult
import com.n0va.detection.ui.components.DetectionControlBar
import com.n0va.detection.ui.components.LogEntry
import com.n0va.detection.ui.components.LogPanel
import com.n0va.detection.ui.components.PageHeader
import com.n0va.detection.ui.components.PreviewFrame
import com.n0va.detection.ui.theme.LocalTheme
import kotlinx.coroutines.launch

@Composable
fun CameraPreviewTab(
    startCamera: (PreviewView) -> Unit,
    stopCamera: () -> Unit,
    detections: List<DetectionResult>,
    frameWidth: Int,
    frameHeight: Int,
    logEntries: List<LogEntry>,
    modelLoaded: Boolean,
    isDetecting: Boolean,
    fps: Int,
    showBoxes: Boolean,
    isFlashOn: Boolean,
    onToggleDetection: () -> Unit,
    onSaveFrame: () -> Unit = {},
    onToggleRecording: () -> Unit = {},
    onSwitchCamera: () -> Unit = {},
    onToggleFlash: () -> Unit = {},
    onToggleBoxes: () -> Unit = {},
    mirrorX: Boolean = false,
    isRecording: Boolean = false,
    modelName: String = "",
    onTapFocus: (Float, Float, Int, Int) -> Unit = { _, _, _, _ -> },
    onPinchZoom: (Float) -> Unit = {}
) {
    val t = LocalTheme.current
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    val scope = rememberCoroutineScope()
    val switchRotation = remember { Animatable(0f) }

    LaunchedEffect(isDetecting) {
        val pv = previewView
        if (isDetecting && pv != null) startCamera(pv)
        else if (!isDetecting) stopCamera()
    }

    Column(modifier = Modifier.fillMaxSize().background(t.background)) {
        // ── 页头 + 操作按钮 ──
        Box(modifier = Modifier.fillMaxWidth()) {
            PageHeader(title = "实时检测")
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 框显隐
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onToggleBoxes() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (showBoxes) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = "框显隐",
                        tint = if (showBoxes) t.accent else t.textDim,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // 闪光灯
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onToggleFlash() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isFlashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = "闪光灯",
                        tint = if (isFlashOn) Color(0xFFFFC107) else t.textDim,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // 前后切换
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            onSwitchCamera()
                            scope.launch {
                                switchRotation.snapTo(0f)
                                switchRotation.animateTo(
                                    -360f,
                                    animationSpec = tween(600, easing = LinearEasing)
                                )
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.FlipCameraAndroid,
                        contentDescription = "切换相机",
                        tint = t.accent,
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(switchRotation.value)
                    )
                }
            }
        }

        // ── 画面区 ──
        Box(modifier = Modifier.weight(1f)) {
            PreviewFrame(
                onPreviewCreated = { previewView = it },
                detections = detections,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                showCamera = isDetecting,
                showBoxes = showBoxes,
                mirrorX = mirrorX,
                onTapFocus = onTapFocus,
                onPinchZoom = onPinchZoom,
                modifier = Modifier.fillMaxSize()
            )

            // ── 目标计数（原始样式无底纹） ──
            if (isDetecting && detections.isNotEmpty()) {
                val counts = detections.groupBy({ it.className }).mapValues { (_, v) -> v.size }
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    counts.entries.forEach { (cls, cnt) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(t.accent, RoundedCornerShape(3.dp))
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "$cls: $cnt",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // ── 录像提示 ──
            if (isRecording) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .background(Color(0xBB000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(Color(0xFFE53935), RoundedCornerShape(4.dp))
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "REC",
                        color = Color(0xFFE53935),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // ── 日志区 ──
        LogPanel(
            entries = logEntries,
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
        )

        // ── 控制区 ──
        DetectionControlBar(
            modelLoaded = modelLoaded,
            isDetecting = isDetecting,
            fps = fps,
            modelName = modelName,
            isRecording = isRecording,
            onToggleDetection = onToggleDetection,
            onSaveFrame = onSaveFrame,
            onToggleRecording = onToggleRecording
        )
    }
}
