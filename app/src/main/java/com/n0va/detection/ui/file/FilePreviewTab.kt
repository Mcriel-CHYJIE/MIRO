package com.n0va.detection.ui.file

import android.graphics.Bitmap
import android.view.ViewGroup
import android.widget.ImageView
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.n0va.detection.detection.DetectionResult
import com.n0va.detection.ui.components.LogEntry
import com.n0va.detection.ui.components.LogPanel
import com.n0va.detection.ui.components.PageHeader
import com.n0va.detection.ui.theme.LocalTheme

@Composable
fun FilePreviewTab(
    mediaBitmap: Bitmap?,
    detections: List<DetectionResult>,
    frameWidth: Int,
    frameHeight: Int,
    logEntries: List<LogEntry>,
    isProcessing: Boolean = false,
    processingProgress: Pair<Int, Int> = 0 to 0,
    onSelectFile: () -> Unit
) {
    val t = LocalTheme.current

    Column(modifier = Modifier.fillMaxSize().background(t.background)) {
        PageHeader(title = "文件检测")

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
            ) {
                if (mediaBitmap != null) {
                    AndroidView(
                        factory = { ctx ->
                            ImageView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                scaleType = ImageView.ScaleType.FIT_CENTER
                                setBackgroundColor(android.graphics.Color.BLACK)
                            }
                        },
                        update = { it.setImageBitmap(mediaBitmap) },
                        modifier = Modifier.fillMaxSize()
                    )

                    AndroidView(
                        factory = { ctx -> com.n0va.detection.ui.components.BoxOverlayView(ctx) },
                        update = { it.setDetections(detections, frameWidth, frameHeight) },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(t.background),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("选择文件", color = t.textDim, fontSize = 15.sp,
                            fontWeight = FontWeight.Medium)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(1.5.dp, t.textDim.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                )
            }
        }

        LogPanel(
            entries = logEntries,
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(t.cardBg)
                .padding(horizontal = 24.dp, vertical = 14.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (isProcessing) "处理中 ${processingProgress.first}/${processingProgress.second}"
                        else "${detections.size} 个目标",
                        fontSize = 11.sp, color = t.textSecondary
                    )
                }

                Spacer(Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isProcessing) Color(0xFF444444) else t.accent)
                        .clickable(enabled = !isProcessing) { onSelectFile() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (isProcessing) "处理中..." else "选择文件",
                        color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
