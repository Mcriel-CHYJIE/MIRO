package com.n0va.detection.ui.components

import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.n0va.detection.detection.DetectionResult
import com.n0va.detection.ui.theme.LocalTheme

@Composable
fun PreviewFrame(
    onPreviewCreated: (PreviewView) -> Unit,
    detections: List<DetectionResult>,
    frameWidth: Int,
    frameHeight: Int,
    showCamera: Boolean,
    showBoxes: Boolean = true,
    mirrorX: Boolean = false,
    onTapFocus: (Float, Float, Int, Int) -> Unit = { _, _, _, _ -> },
    onPinchZoom: (Float) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var previewW by remember { mutableIntStateOf(0) }
    var previewH by remember { mutableIntStateOf(0) }
    var cameraStarting by remember { mutableStateOf(false) }
    var hasShownFirstFrame by remember { mutableStateOf(false) }

    // 检测启动时设置加载标志
    LaunchedEffect(showCamera) {
        if (showCamera) {
            cameraStarting = true
            hasShownFirstFrame = false
        } else {
            cameraStarting = false
            hasShownFirstFrame = false
        }
    }

    // 首帧到达后清除加载标志（只执行一次）
    LaunchedEffect(detections) {
        if (showCamera && detections.isNotEmpty() && !hasShownFirstFrame) {
            hasShownFirstFrame = true
            cameraStarting = false
        }
    }

    // 超时保护：2秒后自动清除（防止无目标场景永远黑屏）
    LaunchedEffect(showCamera) {
        if (showCamera) {
            kotlinx.coroutines.delay(2000)
            cameraStarting = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(10.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    if (showCamera && previewW > 0 && previewH > 0) {
                        onTapFocus(offset.x, offset.y, previewW, previewH)
                    }
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    onPinchZoom(zoom)
                }
            }
    ) {
        // 相机预览
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    setBackgroundColor(android.graphics.Color.BLACK)
                    onPreviewCreated(this)
                    post { previewW = width; previewH = height }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 检测框叠加
        if (showCamera && showBoxes) {
            AndroidView(
                factory = { ctx -> BoxOverlayView(ctx) },
                update = { it.setDetections(detections, frameWidth, frameHeight, mirrorX) },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 检测启动时黑底遮罩（首帧到达后或超时自动消失，不会闪烁）
        if (!showCamera || cameraStarting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                if (showCamera) {
                    Text(
                        "相机启动中…",
                        color = Color(0xFF666666),
                        fontSize = 12.sp
                    )
                }
            }
        }

        // 边框线
        val t = LocalTheme.current
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(1.5.dp, t.textDim.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
        )
    }
}
