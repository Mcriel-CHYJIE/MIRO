package com.n0va.detection.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.n0va.detection.ui.theme.LocalTheme

data class LogEntry(
    val time: String,
    val label: String,
    val confidence: Float = 0f,
    val isSystem: Boolean = false
) {
    val displayText: String
        get() = if (isSystem) "[$time] $label"
                else "[$time] $label ${"%.2f".format(confidence)}"
}

@Composable
fun LogPanel(
    entries: List<LogEntry>,
    modifier: Modifier = Modifier
) {
    val t = LocalTheme.current
    val listState = rememberLazyListState()

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.size - 1)
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(t.navBar)
    ) {
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("暂无检测数据", fontSize = 12.sp, color = t.textDim)
            }
        } else {
            SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                itemsIndexed(entries, key = { index, _ -> index }) { _, entry ->
                    Text(
                        text = entry.displayText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = if (entry.isSystem) t.textDim else t.accent,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
            }
        }
    }
}
