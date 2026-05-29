package com.barsam.wireguardvpn.ui.traffic

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.barsam.wireguardvpn.ui.theme.WireGuardTheme

@Composable
fun TrafficScreen(state: com.barsam.wireguardvpn.ui.VpnUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "Traffic Monitor",
            color = WireGuardTheme.text1,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Text("Real-time bandwidth usage", color = WireGuardTheme.text2, fontSize = 13.sp)

        Spacer(modifier = Modifier.height(24.dp))

        // Speed display
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SpeedDisplay(
                "DOWNLOAD",
                formatSpeed(state.statistics.downloadSpeed),
                WireGuardTheme.accent
            )
            SpeedDisplay(
                "UPLOAD",
                formatSpeed(state.statistics.uploadSpeed),
                WireGuardTheme.blue
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Traffic graph placeholder
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(WireGuardTheme.radius.dp))
                .background(WireGuardTheme.surface)
                .border(1.dp, WireGuardTheme.border, RoundedCornerShape(WireGuardTheme.radius.dp))
                .padding(16.dp)
        ) {
            // Simple live speed visualization
            SpeedGraph(
                downloadSpeed = state.statistics.downloadSpeed,
                uploadSpeed = state.statistics.uploadSpeed,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Session totals
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(WireGuardTheme.radius.dp))
                .background(WireGuardTheme.surface)
                .border(1.dp, WireGuardTheme.border, RoundedCornerShape(WireGuardTheme.radius.dp))
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SESSION TOTAL", color = WireGuardTheme.text3, fontSize = 9.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "↓ ${formatBytes(state.statistics.bytesReceived)}",
                    color = WireGuardTheme.accent,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "↑ ${formatBytes(state.statistics.bytesSent)}",
                    color = WireGuardTheme.blue,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun SpeedDisplay(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = WireGuardTheme.text3, fontSize = 11.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            value,
            color = color,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun SpeedGraph(downloadSpeed: Double, uploadSpeed: Double, modifier: Modifier = Modifier) {
    // Simple bar visualization of current speeds
    val maxSpeed = maxOf(downloadSpeed, uploadSpeed, 1.0)
    val downRatio = (downloadSpeed / maxSpeed).toFloat().coerceIn(0f, 1f)
    val upRatio = (uploadSpeed / maxSpeed).toFloat().coerceIn(0f, 1f)

    Canvas(modifier = modifier) {
        val barWidth = size.width * 0.35f
        val barMaxHeight = size.height - 20f

        // Download bar
        drawRoundRect(
            color = WireGuardTheme.accent.copy(alpha = 0.3f),
            topLeft = Offset(size.width * 0.1f, size.height - barMaxHeight),
            size = androidx.compose.ui.geometry.Size(barWidth, barMaxHeight)
        )
        drawRoundRect(
            color = WireGuardTheme.accent,
            topLeft = Offset(size.width * 0.1f, size.height - barMaxHeight * downRatio),
            size = androidx.compose.ui.geometry.Size(barWidth, barMaxHeight * downRatio)
        )

        // Upload bar
        drawRoundRect(
            color = WireGuardTheme.blue.copy(alpha = 0.3f),
            topLeft = Offset(size.width * 0.55f, size.height - barMaxHeight),
            size = androidx.compose.ui.geometry.Size(barWidth, barMaxHeight)
        )
        drawRoundRect(
            color = WireGuardTheme.blue,
            topLeft = Offset(size.width * 0.55f, size.height - barMaxHeight * upRatio),
            size = androidx.compose.ui.geometry.Size(barWidth, barMaxHeight * upRatio)
        )
    }
}

private fun formatSpeed(bytesPerSec: Double): String {
    if (bytesPerSec <= 0) return "0 B/s"
    val units = listOf("B/s", "KB/s", "MB/s", "GB/s")
    var value = bytesPerSec
    var idx = 0
    while (value >= 1024 && idx < units.size - 1) { value /= 1024; idx++ }
    return String.format("%.1f ${units[idx]}", value)
}

private fun formatBytes(bytes: Long): String {
    if (bytes == 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var idx = 0
    while (value >= 1024 && idx < units.size - 1) { value /= 1024; idx++ }
    return String.format("%.1f ${units[idx]}", value)
}
