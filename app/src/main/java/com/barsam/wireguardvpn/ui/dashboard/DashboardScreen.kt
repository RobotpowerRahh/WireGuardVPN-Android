package com.barsam.wireguardvpn.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.barsam.wireguardvpn.models.ConnectionState
import com.barsam.wireguardvpn.models.ConnectionMode
import com.barsam.wireguardvpn.ui.MainViewModel
import com.barsam.wireguardvpn.ui.VpnUiState
import com.barsam.wireguardvpn.ui.theme.WireGuardTheme

@Composable
fun DashboardScreen(vm: MainViewModel, state: VpnUiState) {
    val context = LocalContext.current
    val connected = state.connectionState == ConnectionState.CONNECTED
    val connecting = state.connectionState == ConnectionState.CONNECTING ||
            state.connectionState == ConnectionState.DISCONNECTING
    val idle = state.connectionState == ConnectionState.DISCONNECTED ||
            state.connectionState == ConnectionState.ERROR

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        // Header
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = when (state.connectionState) {
                    ConnectionState.CONNECTED -> "VPN Active"
                    ConnectionState.CONNECTING -> "Establishing Connection"
                    ConnectionState.DISCONNECTING -> "Disconnecting"
                    ConnectionState.DISCONNECTED -> "Ready to Connect"
                    ConnectionState.ERROR -> "Error: ${state.errorMessage ?: "Unknown"}"
                },
                color = WireGuardTheme.text1,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (connected) "Traffic is being routed through the VPN tunnel"
                else "Select a server profile and connect",
                color = WireGuardTheme.text2,
                fontSize = 13.sp
            )
        }

        Spacer(modifier = Modifier.height(36.dp))

        // Power button
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .clip(CircleShape)
                    .border(
                        width = 2.dp,
                        color = if (connected) WireGuardTheme.accentBorder else WireGuardTheme.border,
                        shape = CircleShape
                    )
            )
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .clip(CircleShape)
                    .background(
                        if (connected) WireGuardTheme.accentDim
                        else WireGuardTheme.accentDim.copy(alpha = 0.02f)
                    )
            )
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .clickable(enabled = !connecting) {
                        if (connected) {
                            vm.disconnect(context)
                        } else if (idle) {
                            state.profiles.firstOrNull()?.let { profile ->
                                vm.requestConnect(
                                    context as android.app.Activity,
                                    profile,
                                    state.activeMode
                                )
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.PowerSettingsNew,
                    contentDescription = "Connect",
                    modifier = Modifier.size(48.dp),
                    tint = if (connected) WireGuardTheme.accent else WireGuardTheme.text2
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = when {
                connected -> "Connected"
                connecting -> "Connecting..."
                else -> "Disconnected"
            },
            color = when (state.connectionState) {
                ConnectionState.CONNECTED -> WireGuardTheme.accent
                ConnectionState.CONNECTING, ConnectionState.DISCONNECTING -> WireGuardTheme.orange
                ConnectionState.DISCONNECTED -> WireGuardTheme.text3
                ConnectionState.ERROR -> WireGuardTheme.red
            },
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )

        if (!connected && state.profiles.isNotEmpty()) {
            Text(
                text = "Server: ${state.profiles.first().name}",
                color = WireGuardTheme.text3,
                fontSize = 11.sp
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Stats grid
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("DOWNLOAD", formatSpeed(state.statistics.downloadSpeed), Modifier.weight(1f))
            StatCard("UPLOAD", formatSpeed(state.statistics.uploadSpeed), Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("TOTAL DOWN", formatBytes(state.statistics.bytesReceived), Modifier.weight(1f))
            StatCard("TOTAL UP", formatBytes(state.statistics.bytesSent), Modifier.weight(1f))
        }

        if (connected) {
            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(WireGuardTheme.radius.dp))
                    .background(WireGuardTheme.surface)
                    .border(1.dp, WireGuardTheme.border, RoundedCornerShape(WireGuardTheme.radius.dp))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                InfoChip("Uptime", formatDuration(state.connectedDuration))
                InfoChip("Server", state.activeProfile?.name ?: "Unknown")
                if (state.activeMode != ConnectionMode.DIRECT) {
                    InfoChip(
                        "Mode",
                        if (state.activeMode == ConnectionMode.STEALTH) "VLESS+Reality" else "WARP+Reality"
                    )
                }
            }
        }
    }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(WireGuardTheme.radius.dp))
            .background(WireGuardTheme.surface)
            .border(1.dp, WireGuardTheme.border, RoundedCornerShape(WireGuardTheme.radius.dp))
            .padding(vertical = 10.dp, horizontal = 12.dp)
    ) {
        Text(text = label, color = WireGuardTheme.text3, fontSize = 9.sp)
        Text(
            text = value,
            color = WireGuardTheme.text1,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun InfoChip(label: String, value: String) {
    Column {
        Text(text = label, color = WireGuardTheme.text3, fontSize = 9.sp)
        Text(
            text = value,
            color = WireGuardTheme.text1,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace
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

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> String.format("%dh %dm %ds", h, m, s)
        m > 0 -> String.format("%dm %ds", m, s)
        else -> String.format("%ds", s)
    }
}
