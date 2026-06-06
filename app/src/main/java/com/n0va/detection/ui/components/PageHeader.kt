package com.n0va.detection.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val HeaderBg = Color(0xFF252525)

@Composable
fun PageHeader(
    title: String,
    subtitle: String = "",
    modifier: Modifier = Modifier
) {
    val bg = com.n0va.detection.ui.theme.LocalTheme.current.headerBg
    val tc = com.n0va.detection.ui.theme.LocalTheme.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = tc.textPrimary,
                textAlign = TextAlign.Center
            )
        }

        if (subtitle.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
            ) {
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = tc.accent
                )
            }
        }
    }
}
