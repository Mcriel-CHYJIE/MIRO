package com.n0va.detection.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.n0va.detection.ui.theme.LocalTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun DetectionControlBar(
    modelLoaded: Boolean,
    isDetecting: Boolean,
    fps: Int,
    modelName: String = "",
    isRecording: Boolean = false,
    onToggleDetection: () -> Unit,
    onSaveFrame: () -> Unit = {},
    onToggleRecording: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val t = LocalTheme.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(t.cardBg.copy(alpha = 0.85f))
            .padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            if (modelLoaded) t.accent else Color(0xFFFF9800),
                            shape = RoundedCornerShape(3.dp)
                        )
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    if (isDetecting) "检测中 · $modelName" else if (modelLoaded) modelName else "就绪",
                    fontSize = 11.sp, color = t.textSecondary
                )
                if (isRecording) {
                    var recSeconds by remember { mutableIntStateOf(0) }
                    LaunchedEffect(Unit) {
                        recSeconds = 0
                        while (isActive) {
                            delay(1000)
                            recSeconds++
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .background(Color(0xFFE53935), RoundedCornerShape(2.5.dp))
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "REC ${recSeconds / 60}:${"%02d".format(recSeconds % 60)}",
                        color = Color(0xFFE53935),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            if (fps > 0) {
                Text(
                    "${fps} FPS",
                    fontSize = 11.sp, color = t.textSecondary,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val btnScale by animateFloatAsState(
                targetValue = if (isDetecting) 1f else 0.9f,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
                label = "detect_btn_scale"
            )
            // ── 录像按钮（同心圆/方，仅检测时可用） ──
            val recScale by animateFloatAsState(
                targetValue = if (isDetecting) 1f else 0.9f,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
                label = "rec_btn_scale"
            )
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .graphicsLayer(scaleX = recScale, scaleY = recScale)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isDetecting) Color(0xFF43A047)
                        else Color(0xFF444444)
                    )
                    .clickable(enabled = isDetecting) { onToggleRecording() },
                contentAlignment = Alignment.Center
            ) {
                if (isRecording) {
                    // 录制中：同心方（外框+内实心红方）
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(Color.Transparent, RoundedCornerShape(3.dp))
                            .border(2.dp, Color.White, RoundedCornerShape(3.dp))
                    )
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(Color(0xFFE53935), RoundedCornerShape(2.dp))
                    )
                } else {
                    // 待机：同心圆（外圈+内圆）
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(Color.Transparent, RoundedCornerShape(12.dp))
                            .border(2.dp, Color.White, RoundedCornerShape(12.dp))
                    )
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(Color(0xFFFF4444), RoundedCornerShape(8.dp))
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .graphicsLayer(scaleX = btnScale, scaleY = btnScale)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (!modelLoaded) Color(0xFF444444)
                        else if (isDetecting) Color(0xFFE53935)
                        else t.accent
                    )
                    .clickable(enabled = modelLoaded) { onToggleDetection() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (!modelLoaded) "加载中"
                    else if (isDetecting) "停止检测"
                    else "开始检测",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
            }

            Spacer(Modifier.width(10.dp))

            val saveScale by animateFloatAsState(
                targetValue = if (isDetecting) 1f else 0.9f,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
                label = "save_btn_scale"
            )
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .graphicsLayer(scaleX = saveScale, scaleY = saveScale)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isDetecting) t.accent else Color(0xFF444444)
                    )
                    .clickable(enabled = isDetecting) { onSaveFrame() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Save,
                    contentDescription = "保存",
                    tint = if (isDetecting) Color.White else Color(0xFF888888),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
