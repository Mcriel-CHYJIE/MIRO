package com.n0va.detection.ui.records

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.n0va.detection.MainViewModel.SavedImage
import com.n0va.detection.ui.components.PageHeader
import com.n0va.detection.ui.theme.LocalTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordsTab(
    savedImages: List<SavedImage>,
    onRefresh: () -> Unit,
    onDelete: (Uri) -> Unit
) {
    var selectedImage by remember { mutableStateOf<SavedImage?>(null) }

    var activeFilter by remember { mutableStateOf<String?>(null) }

    val allClassNames = remember(savedImages) {
        savedImages.flatMap { it.stats.keys }.distinct().sorted()
    }

    val filteredImages = remember(savedImages, activeFilter) {
        if (activeFilter != null) {
            savedImages.filter { it.stats.containsKey(activeFilter) }
        } else {
            savedImages
        }
    }

    val pagerState = rememberPagerState(pageCount = { filteredImages.size })
    val scope = rememberCoroutineScope()
    val refreshRotation = remember { Animatable(0f) }

    Box(modifier = Modifier.fillMaxSize().background(LocalTheme.current.background)) {
        var showDeleteConfirm by remember { mutableStateOf<SavedImage?>(null) }
        var deleteMode by remember { mutableStateOf(false) }
        val t = LocalTheme.current
        val isDark = t.background == Color(0xFF1E1E1E)
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Header ──
            Box(modifier = Modifier.fillMaxWidth()) {
                PageHeader(title = "检测记录")
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 快捷删除开关
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { deleteMode = !deleteMode },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "快捷删除",
                            tint = if (deleteMode) Color(0xFFE53935) else Color(0xFF555555),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    // 刷新
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                onRefresh()
                                scope.launch {
                                    refreshRotation.snapTo(0f)
                                    refreshRotation.animateTo(
                                        360f,
                                        animationSpec = tween(600, easing = LinearEasing)
                                    )
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "刷新",
                            tint = Color(0xFF07C160),
                            modifier = Modifier
                                .size(20.dp)
                                .rotate(refreshRotation.value)
                        )
                    }
                }
            }

            // ── Filter chips ──
            if (savedImages.isNotEmpty() && allClassNames.isNotEmpty()) {
                FilterChipRow(
                    allClassNames = allClassNames,
                    activeFilter = activeFilter,
                    onFilterChange = { activeFilter = it }
                )
            }

            // ── Main content ──
            if (savedImages.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "暂无记录\n保存的检测画面将显示在这里",
                        color = t.textDim,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    )
                }
            } else if (filteredImages.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "未找到匹配的记录",
                        color = t.textDim,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        val img = filteredImages[page]
                        PagerPage(
                            savedImage = img,
                            onClick = { selectedImage = img },
                            deleteMode = deleteMode,
                            onDeleteRequest = { onDelete(img.uri) }
                        )
                    }
                }

                // ── Bottom info bar ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isDark) Color(0x80000000) else Color(0xCCFFFFFF))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = filteredImages.getOrNull(pagerState.currentPage)?.name ?: "",
                        color = t.textSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${pagerState.currentPage + 1}/${filteredImages.size}",
                        color = t.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clickable {
                                filteredImages.getOrNull(pagerState.currentPage)?.let {
                                    showDeleteConfirm = it
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = Color(0xFFE53935),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // ── Full screen preview ──
        AnimatedVisibility(
            visible = selectedImage != null,
            enter = scaleIn(initialScale = 0.85f, animationSpec = tween(250)) +
                    fadeIn(animationSpec = tween(200)),
            exit = scaleOut(targetScale = 0.85f, animationSpec = tween(200)) +
                    fadeOut(animationSpec = tween(150))
        ) {
            selectedImage?.let { img ->
                FullScreenPreview(
                    savedImage = img,
                    onClose = { selectedImage = null },
                    onRequestDelete = { showDeleteConfirm = img }
                )
            }
        }

        // ── 删除确认弹窗 ──
        showDeleteConfirm?.let { img ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x88000000))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showDeleteConfirm = null },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(min = 260.dp, max = 300.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF252525))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { /* 阻止点击穿透 */ },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "确认删除",
                        color = Color(0xFFE0E0E0),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "删除后不可恢复",
                        color = Color(0xFF888888),
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        img.name,
                        color = Color(0xFFAAAAAA),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider(color = Color(0xFF333333), thickness = 0.5.dp)
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = { showDeleteConfirm = null },
                            modifier = Modifier.weight(1f)
                                .height(44.dp)
                        ) {
                            Text("取消", color = Color(0xFF888888), fontSize = 14.sp)
                        }
                        Box(
                            modifier = Modifier
                                .width(0.5.dp)
                                .height(44.dp)
                                .background(Color(0xFF333333))
                        )
                        TextButton(
                            onClick = {
                                onDelete(img.uri)
                                showDeleteConfirm = null
                                selectedImage = null
                            },
                            modifier = Modifier.weight(1f)
                                .height(44.dp)
                        ) {
                            Text("删除", color = Color(0xFFE53935),
                                fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

// ── Filter chips ──

@Composable
private fun FilterChipRow(
    allClassNames: List<String>,
    activeFilter: String?,
    onFilterChange: (String?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChipItem(label = "全部", isActive = activeFilter == null, onClick = { onFilterChange(null) })
        allClassNames.forEach { className ->
            FilterChipItem(
                label = className,
                isActive = activeFilter == className,
                onClick = { onFilterChange(if (activeFilter == className) null else className) }
            )
        }
    }
}

@Composable
private fun FilterChipItem(label: String, isActive: Boolean, onClick: () -> Unit) {
    val t = LocalTheme.current
    val isDark = t.background == Color(0xFF1E1E1E)
    val inactiveBg = if (isDark) Color(0xFF3A3A3A) else Color(0xFFE0E0E0)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isActive) t.accent else inactiveBg)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = if (isActive) Color.White else t.textSecondary,
            fontSize = 12.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
        )
    }
}

// ── Pager page ──

@Composable
private fun PagerPage(
    savedImage: SavedImage,
    onClick: () -> Unit,
    deleteMode: Boolean = false,
    onDeleteRequest: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    val context = LocalContext.current
    val t = LocalTheme.current
    val isDark = t.background == Color(0xFF1E1E1E)

    // 上滑手势偏移量
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var dragConsumed by remember { mutableStateOf(false) }
    val deleteThreshold = with(LocalDensity.current) { 100.dp.toPx() }

    LaunchedEffect(savedImage.id) {
        if (!savedImage.isVideo) {
            bitmap = loadImageSample(context, savedImage.uri, 1920)
        } else {
            bitmap = loadVideoThumbnail(context, savedImage.uri)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp)
            .offset { IntOffset(0, dragOffsetY.roundToInt()) }
            .alpha((1f - (dragOffsetY / deleteThreshold).coerceIn(0f, 0.5f)).coerceIn(0.5f, 1f))
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1E1E1E))
            .then(
                if (deleteMode) Modifier.pointerInput(savedImage.id) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (-dragOffsetY > deleteThreshold && !dragConsumed) {
                                dragConsumed = true
                                onDeleteRequest()
                            }
                            dragOffsetY = 0f
                        },
                        onVerticalDrag = { _, dragAmount ->
                            dragOffsetY = (dragOffsetY + dragAmount).coerceIn(-deleteThreshold * 2, 0f)
                        }
                    )
                } else Modifier
            )
            .border(2.dp, Color(0xFF3A3A3A), RoundedCornerShape(10.dp))
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                if (savedImage.isVideo) {
                    // 视频：缩略图 + 播放图标叠加
                    Box(contentAlignment = Alignment.Center) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap!!.asImageBitmap(),
                                contentDescription = savedImage.name,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            CircularProgressIndicator(
                                color = Color(0xFF07C160),
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        // 半透明遮罩 + 播放图标
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color(0x88000000), RoundedCornerShape(24.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("▶", fontSize = 24.sp,
                                color = Color.White,
                                modifier = Modifier.padding(start = 2.dp))
                        }
                    }
                } else {
                    Crossfade(
                        targetState = bitmap,
                        animationSpec = tween(200),
                        label = "img_crossfade"
                    ) { bmp ->
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = savedImage.name,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            CircularProgressIndicator(
                                color = Color(0xFF07C160),
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
                // 右上角分享按钮
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(32.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { shareFile(context, savedImage.uri) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = "分享",
                        tint = Color(0xFF07C160),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isDark) Color(0xCC000000) else Color(0xCCFFFFFF))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                if (savedImage.stats.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        savedImage.stats.entries.forEach { (className, count) ->
                            StatChip(className, count, isDark)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                } else {
                    Text("未检测到目标", color = t.textDim, fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 2.dp))
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    text = savedImage.dateStr,
                    color = t.textSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

// ── Stat chip ──

@Composable
private fun StatChip(label: String, count: Int, isDark: Boolean = true) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isDark) Color(0xFF2E2E2E) else Color(0xFFE8E8E8))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, color = if (isDark) Color(0xFFE0E0E0) else Color(0xFF191919), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(5.dp))
            Box(
                modifier = Modifier
                    .background(Color(0xFF07C160), RoundedCornerShape(6.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(text = "$count", color = Color.White, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// ── Image loading helper ──

private suspend fun loadImageSample(context: Context, uri: Uri, maxDim: Int): Bitmap? =
    withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            val sample = Integer.highestOneBit(
                maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / maxDim)
            )
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            }
        } catch (_: Exception) { null }
    }

private suspend fun loadVideoThumbnail(context: Context, uri: Uri): Bitmap? =
    withContext(Dispatchers.IO) {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val bitmap = retriever.frameAtTime
            retriever.release()
            bitmap
        } catch (_: Exception) { null }
    }

// ── Full screen preview ──

@Composable
private fun FullScreenPreview(
    savedImage: SavedImage,
    onClose: () -> Unit,
    onRequestDelete: () -> Unit
) {
    val context = LocalContext.current
    var fullBitmap by remember { mutableStateOf<Bitmap?>(null) }

    BackHandler(onBack = onClose)

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (savedImage.isVideo) {
            // 视频：VideoView + 进度控制
            val vv = remember { android.widget.VideoView(context) }
            var isPlaying by remember { mutableStateOf(true) }
            var currentPos by remember { mutableIntStateOf(0) }
            var duration by remember { mutableIntStateOf(0) }

            LaunchedEffect(savedImage.id) {
                try {
                    vv.setVideoURI(savedImage.uri)
                    vv.setOnPreparedListener { mp ->
                        duration = mp.duration.coerceAtLeast(1)
                        mp.isLooping = false
                        vv.start()
                        isPlaying = true
                    }
                    vv.setOnErrorListener { _, _, _ -> true }
                    vv.setOnCompletionListener { isPlaying = false }
                } catch (_: Exception) {}
            }

            // 进度更新协程
            LaunchedEffect(isPlaying) {
                while (isActive && isPlaying) {
                    currentPos = vv.currentPosition.coerceAtMost(duration)
                    delay(200)
                }
            }

            AndroidView(
                factory = { vv },
                modifier = Modifier.fillMaxSize()
            )

            // 底部控制栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color(0xBB000000))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 播放/暂停
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clickable {
                            if (vv.isPlaying) { vv.pause(); isPlaying = false }
                            else { vv.start(); isPlaying = true }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(4.dp))
                // 进度条
                android.widget.SeekBar(context).let { seekBar ->
                    LaunchedEffect(duration) { seekBar.max = duration }
                    LaunchedEffect(currentPos) { seekBar.progress = currentPos }
                    AndroidView(
                        factory = { seekBar },
                        modifier = Modifier.weight(1f).height(32.dp),
                        update = { sb ->
                            sb.max = duration
                            sb.progress = currentPos
                            sb.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                                override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                                    if (fromUser) { vv.seekTo(p); currentPos = p }
                                }
                                override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                                override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
                            })
                        }
                    )
                }
                Spacer(Modifier.width(8.dp))
                // 时间
                Text(
                    text = "${currentPos / 60000}:${"%02d".format((currentPos / 1000) % 60)}" +
                            " / ${duration / 60000}:${"%02d".format((duration / 1000) % 60)}",
                    color = Color(0xFFAAAAAA),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        } else {
            LaunchedEffect(savedImage.id) {
                try {
                    context.contentResolver.openInputStream(savedImage.uri)?.use { stream ->
                        fullBitmap = BitmapFactory.decodeStream(stream)
                    }
                } catch (_: Exception) {}
            }
            if (fullBitmap != null) {
                Image(
                    bitmap = fullBitmap!!.asImageBitmap(),
                    contentDescription = savedImage.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                if (scale > 1f) {
                                    offsetX += pan.x
                                    offsetY += pan.y
                                } else {
                                    offsetX = 0f; offsetY = 0f
                                }
                            }
                        }
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        )
                )
            }
        }

        // Top bar: close + name + share + delete
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x99000000))
                .padding(horizontal = 4.dp, vertical = 8.dp)
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clickable { onClose() },
                contentAlignment = Alignment.Center
            ) {
                Text("✕", color = Color.White, fontSize = 18.sp)
            }
            Text(
                text = savedImage.name,
                color = Color(0xFFE0E0E0),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
            )
            // 分享
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clickable { shareFile(context, savedImage.uri) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = "分享",
                    tint = Color(0xFF07C160),
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clickable { onRequestDelete() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Delete, contentDescription = "删除",
                    tint = Color(0xFFE53935), modifier = Modifier.size(20.dp))
            }
        }
    }
}

private fun shareFile(context: Context, uri: Uri) {
    try {
        // uri 是 file://，转为 content:// 通过 FileProvider 分享
        val file = java.io.File(uri.path ?: return)
        val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val mimeType = when {
            file.name.endsWith(".mp4") -> "video/mp4"
            file.name.endsWith(".jpg") || file.name.endsWith(".jpeg") -> "image/jpeg"
            file.name.endsWith(".png") -> "image/png"
            else -> context.contentResolver.getType(contentUri) ?: "*/*"
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "分享到").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (_: Exception) {}
}
