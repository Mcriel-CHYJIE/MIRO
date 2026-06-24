package com.n0va.detection.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.n0va.detection.detection.TFLiteDetector
import com.n0va.detection.ui.components.PageHeader
import com.n0va.detection.ui.theme.LocalTheme
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsTab(
    modelLoaded: Boolean,
    numClasses: Int = 0,
    labels: List<String> = emptyList(),
    device: String = "CPU",
    confThreshold: Float = 0.25f,
    iouThreshold: Float = 0.45f,
    onConfThresholdChange: (Float) -> Unit = {},
    onIouThresholdChange: (Float) -> Unit = {},
    availableModels: List<String> = emptyList(),
    activeModelIndex: Int = 0,
    onSwitchModel: (Int) -> Unit = {},
    isDarkTheme: Boolean = true,
    onToggleTheme: () -> Unit = {},
    drawBoxesOnRecording: Boolean = true,
    onToggleDrawBoxes: () -> Unit = {},
    autoSaveEnabled: Boolean = false,
    onToggleAutoSave: () -> Unit = {},
    autoSaveClass: String = "",
    onAutoSaveClassChange: (String) -> Unit = {},
    onImportModel: () -> Unit = {},
    onResetSettings: () -> Unit = {},
    onDeleteModel: (Int) -> Unit = {},
    onEditModel: (Int, String, String) -> Unit = { _, _, _ -> },
    webcamPort: String = "8080",
    onWebcamPortChange: (String) -> Unit = {},
    webcamQuality: Int = 80,
    onWebcamQualityChange: (Int) -> Unit = {},
    webcamResolution: String = "720p",
    onWebcamResolutionChange: (String) -> Unit = {},
    localIp: String = ""
) {
    val t = LocalTheme.current
    var editTarget by remember { mutableStateOf<Int?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(t.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            PageHeader(title = "设置")

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Spacer(Modifier.height(8.dp))

                // ── 模型选择 ──
                if (availableModels.isNotEmpty()) {
                    SectionHeader("模型选择", t.textSecondary)
                    Card(t.cardBg) {
                        availableModels.forEachIndexed { i, name ->
                            val isCustom = TFLiteDetector.availableModels.getOrNull(i)?.isCustom ?: false
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (isCustom) Modifier.combinedClickable(
                                            onClick = { onSwitchModel(i) },
                                            onLongClick = { editTarget = i }
                                        ) else Modifier.clickable { onSwitchModel(i) }
                                    )
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(name, fontSize = 13.sp, color = t.textPrimary)
                                if (i == activeModelIndex) {
                                    Text("当前", fontSize = 11.sp, color = t.accent,
                                        fontWeight = FontWeight.Medium)
                                }
                            }
                            if (i < availableModels.lastIndex) {
                                Spacer(Modifier.height(2.dp))
                                Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFF444444)))
                                Spacer(Modifier.height(2.dp))
                            }
                        }

                        // 导入按钮
                        Spacer(Modifier.height(2.dp))
                        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFF444444)))
                        Spacer(Modifier.height(2.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onImportModel() }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("＋ 导入模型", fontSize = 13.sp, color = t.accent,
                                fontWeight = FontWeight.Medium)
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }

                SectionHeader("外观", t.textSecondary)
                Card(t.cardBg) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onToggleTheme() }.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("深色主题", fontSize = 13.sp, color = t.textPrimary)
                        Text(if (isDarkTheme) "已开启" else "已关闭", fontSize = 11.sp,
                            color = t.accent, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.height(20.dp))

                SectionHeader("录像设置", t.textSecondary)
                Card(t.cardBg) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onToggleDrawBoxes() }.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("绘制检测框", fontSize = 13.sp, color = t.textPrimary)
                        Text(if (drawBoxesOnRecording) "已开启" else "已关闭", fontSize = 11.sp,
                            color = t.accent, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.height(20.dp))

                SectionHeader("IP Webcam 推流", t.textSecondary)
                Card(t.cardBg) {
                    // 端口 — 显示完整推流地址
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("端口", fontSize = 13.sp, color = t.textSecondary)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (localIp.isNotEmpty()) {
                                Text(
                                    "http://$localIp:",
                                    fontSize = 11.sp,
                                    color = t.textDim,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .width(56.dp)
                                    .height(28.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isDarkTheme) Color(0xFF3A3A3A) else Color(0xFFE8E8E8))
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                BasicTextField(
                                    value = webcamPort,
                                    onValueChange = { v ->
                                        if (v.all { it.isDigit() } && v.length <= 5) onWebcamPortChange(v)
                                    },
                                    singleLine = true,
                                    textStyle = LocalTextStyle.current.copy(
                                        fontSize = 12.sp, color = t.textPrimary,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    ),
                                    cursorBrush = SolidColor(t.accent),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Text(
                                "/stream",
                                fontSize = 11.sp,
                                color = t.textDim,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFF444444)))
                    Spacer(Modifier.height(2.dp))
                    // 画质
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("画质", fontSize = 13.sp, color = t.textSecondary)
                            Text("${webcamQuality}%", fontSize = 13.sp,
                                fontWeight = FontWeight.Medium, color = t.accent,
                                fontFamily = FontFamily.Monospace)
                        }
                        Slider(
                            value = webcamQuality.toFloat(),
                            onValueChange = { onWebcamQualityChange(it.toInt()) },
                            valueRange = 10f..100f,
                            modifier = Modifier.fillMaxWidth().height(24.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = t.accent, activeTrackColor = t.accent,
                                inactiveTrackColor = Color(0xFF555555)
                            )
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFF444444)))
                    Spacer(Modifier.height(2.dp))
                    // 分辨率
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("分辨率", fontSize = 13.sp, color = t.textSecondary)
                        Row {
                            listOf("480p", "720p", "1080p").forEach { res ->
                                FilterChip(
                                    selected = webcamResolution == res,
                                    onClick = { onWebcamResolutionChange(res) },
                                    label = { Text(res, fontSize = 10.sp) },
                                    modifier = Modifier.height(24.dp),
                                    shape = RoundedCornerShape(4.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = t.accent,
                                        selectedLabelColor = Color.White,
                                        containerColor = t.background
                                    )
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))

                SectionHeader("检测保存", t.textSecondary)
                Card(t.cardBg) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onToggleAutoSave() }.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("自动保存检测帧", fontSize = 13.sp, color = t.textPrimary)
                        Text(if (autoSaveEnabled) "已开启" else "已关闭", fontSize = 11.sp,
                            color = t.accent, fontWeight = FontWeight.Medium)
                    }
                    if (autoSaveEnabled) {
                        Spacer(Modifier.height(2.dp))
                        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFF444444)))
                        Spacer(Modifier.height(2.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("检测类别", fontSize = 13.sp, color = t.textSecondary)
                            var text by remember { mutableStateOf(autoSaveClass) }
                            BasicTextField(
                                value = text,
                                onValueChange = { text = it; onAutoSaveClassChange(it) },
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    color = Color.White, fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace
                                ),
                                singleLine = true,
                                modifier = Modifier
                                    .width(140.dp)
                                    .background(Color(0xFF3A3A3A), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))

                SectionHeader("检测参数", t.textSecondary)
                Card(t.cardBg) {
                    SliderRow("置信度阈值", confThreshold, onConfThresholdChange, 0.05f..0.95f, 17, t.accent, t.textSecondary)
                    Spacer(Modifier.height(6.dp))
                    SliderRow("NMS IoU", iouThreshold, onIouThresholdChange, 0.10f..0.90f, 7, t.accent, t.textSecondary)
                }

                Spacer(Modifier.height(20.dp))

                SectionHeader("模型信息", t.textSecondary)
                Card(t.cardBg) {
                    InfoRow("引擎", "TFLite $device 加速", t.textSecondary, t.textPrimary)
                    Spacer(Modifier.height(10.dp))
                    InfoRow("状态", if (modelLoaded) "已加载" else "未加载", t.textSecondary, t.textPrimary,
                        valueColor = if (modelLoaded) t.accent else Color(0xFFFF9800))
                    Spacer(Modifier.height(10.dp))
                    InfoRow("类别数", if (numClasses > 0) "$numClasses" else "—", t.textSecondary, t.textPrimary)
                    if (labels.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        InfoRow("标签", "${labels.size}类", t.textSecondary, t.textPrimary)
                        Spacer(Modifier.height(10.dp))
                        Text(labels.joinToString(" · "), fontSize = 11.sp, color = t.textDim,
                            fontFamily = FontFamily.Monospace, lineHeight = 18.sp)
                    }
                }

                Spacer(Modifier.height(20.dp))

                SectionHeader("应用信息", t.textSecondary)
                Card(t.cardBg) {
                    InfoRow("版本", "0.3.0", t.textSecondary, t.textPrimary)
                    Spacer(Modifier.height(10.dp))
                    InfoRow("模型", if (modelLoaded) "${TFLiteDetector.activeModelName}" else "—", t.textSecondary, t.textPrimary)
                    Spacer(Modifier.height(10.dp))
                    InfoRow("推理尺寸", if (modelLoaded) "${TFLiteDetector.availableModels.getOrNull(activeModelIndex)?.inputSize ?: 640}×${TFLiteDetector.availableModels.getOrNull(activeModelIndex)?.inputSize ?: 640}" else "—", t.textSecondary, t.textPrimary)
                }

                Spacer(Modifier.height(16.dp))

                Box(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp)).background(Color(0xFF4A4A4A))
                        .clickable { onResetSettings() }.padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("重置设置", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFFE0E0E0))
                }

                Spacer(Modifier.height(24.dp))
                Text("MIRO v0.3.0  ·  ©Mcriel_CHYJIE", fontSize = 11.sp, color = t.textDim,
                    modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(16.dp))
            }
        }

        // ── 编辑窗口 ──
        if (editTarget != null) {
            EditModelDialog(
                index = editTarget!!,
                isDark = isDarkTheme,
                onSave = { idx, name, labels ->
                    onEditModel(idx, name, labels)
                    editTarget = null
                },
                onDelete = { idx ->
                    onDeleteModel(idx)
                    editTarget = null
                },
                onCancel = { editTarget = null }
            )
        }
    }
}

// ── 编辑模型弹窗 ──

@Composable
private fun EditModelDialog(
    index: Int,
    isDark: Boolean,
    onSave: (Int, String, String) -> Unit,
    onDelete: (Int) -> Unit,
    onCancel: () -> Unit
) {
    val info = TFLiteDetector.availableModels.getOrNull(index) ?: run { onCancel(); return }
    val origLabels = remember(info) {
        try { File(info.labelsFile).readLines().map { it.trim() }.filter { it.isNotEmpty() } }
        catch (_: Exception) { emptyList() }
    }
    val numClasses = origLabels.size

    val bgCard = if (isDark) Color(0xFF252525) else Color(0xFFF0F0F0)
    val bgInput = if (isDark) Color(0xFF333333) else Color(0xFFE0E0E0)
    val divider = if (isDark) Color(0xFF333333) else Color(0xFFD0D0D0)
    val textPrimary = if (isDark) Color(0xFFE0E0E0) else Color(0xFF191919)
    val textSecondary = if (isDark) Color(0xFFAAAAAA) else Color(0xFF666666)
    val textDim = if (isDark) Color(0xFF666666) else Color(0xFF999999)

    var modelName by remember { mutableStateOf(info.name) }
    val labelValues = remember {
        mutableStateListOf<String>().apply {
            val count = if (numClasses > 0) numClasses else 1
            repeat(count) { add(origLabels.getOrElse(it) { "" }) }
        }
    }
    val classLabels = remember(numClasses) {
        if (numClasses > 0) (0 until numClasses).map { "class_$it" }
        else listOf("object")
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0x88000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onCancel() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.widthIn(min = 300.dp, max = 340.dp)
                .clip(RoundedCornerShape(16.dp)).background(bgCard)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))
            Text("编辑模型", color = textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(16.dp))

            // 名称
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("名称", color = textSecondary, fontSize = 13.sp, modifier = Modifier.width(48.dp))
                Box(
                    modifier = Modifier.weight(1f)
                        .clip(RoundedCornerShape(8.dp)).background(bgInput)
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    BasicTextField(
                        value = modelName, onValueChange = { modelName = it },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = textPrimary),
                        cursorBrush = SolidColor(Color(0xFF07C160)),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            Box { if (modelName.isEmpty()) Text("模型名称", color = textDim, fontSize = 13.sp); inner() }
                        }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = divider, thickness = 0.5.dp)
            Spacer(Modifier.height(8.dp))

            // 类别
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                    .heightIn(max = 200.dp).verticalScroll(rememberScrollState())
            ) {
                classLabels.forEachIndexed { i, defaultLabel ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(defaultLabel, color = textSecondary, fontSize = 13.sp, modifier = Modifier.width(56.dp))
                        Box(
                            modifier = Modifier.weight(1f)
                                .clip(RoundedCornerShape(8.dp)).background(bgInput)
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                        ) {
                            BasicTextField(
                                value = labelValues[i], onValueChange = { labelValues[i] = it },
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = textPrimary),
                                cursorBrush = SolidColor(Color(0xFF07C160)),
                                modifier = Modifier.fillMaxWidth(),
                                decorationBox = { inner ->
                                    Box { if (labelValues[i].isEmpty()) Text(defaultLabel, color = textDim, fontSize = 13.sp); inner() }
                                }
                            )
                        }
                    }
                    if (i < classLabels.lastIndex) Spacer(Modifier.height(6.dp))
                }
            }
            Spacer(Modifier.height(4.dp))

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = divider, thickness = 0.5.dp)
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { onDelete(index) }, modifier = Modifier.weight(1f).height(48.dp)) {
                    Text("删除", color = Color(0xFFFF4444), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.width(0.5.dp).height(48.dp).background(divider))
                TextButton(
                    onClick = {
                        val name = modelName.ifBlank { info.name }
                        val labels = classLabels.mapIndexed { i, def -> labelValues[i].ifBlank { def } }.joinToString(",")
                        onSave(index, name, labels)
                    },
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text("保存", color = Color(0xFF07C160), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ── 组件 ──

@Composable
private fun SectionHeader(title: String, textSecondary: Color) {
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = textSecondary,
        modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun Card(bgColor: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bgColor).padding(14.dp),
        content = content)
}

@Composable
private fun InfoRow(label: String, value: String, textSecondary: Color, textPrimary: Color, valueColor: Color? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = textSecondary)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = valueColor ?: textPrimary,
            fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SliderRow(label: String, value: Float, onValueChange: (Float) -> Unit, valueRange: ClosedFloatingPointRange<Float>, steps: Int, accent: Color, textSecondary: Color) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, color = textSecondary)
            Text("% .2f".format(value), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = accent, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(2.dp))
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, steps = steps,
            modifier = Modifier.fillMaxWidth().height(24.dp),
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = Color(0xFF555555)))
    }
}
