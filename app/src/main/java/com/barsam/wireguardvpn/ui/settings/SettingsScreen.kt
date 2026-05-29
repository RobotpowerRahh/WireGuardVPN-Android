package com.barsam.wireguardvpn.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.barsam.wireguardvpn.models.ConnectionMode
import com.barsam.wireguardvpn.services.UpdateState
import com.barsam.wireguardvpn.ui.MainViewModel
import com.barsam.wireguardvpn.ui.VpnUiState
import com.barsam.wireguardvpn.ui.theme.WireGuardTheme

@Composable
fun SettingsScreen(vm: MainViewModel, state: VpnUiState) {
    val context = LocalContext.current
    var connectionMode by remember { mutableStateOf(ConnectionMode.DIRECT) }
    var autoConnect by remember { mutableStateOf(false) }
    var killSwitch by remember { mutableStateOf(true) }
    var dns by remember { mutableStateOf("1.1.1.1") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "Settings",
            color = WireGuardTheme.text1,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(24.dp))

        SettingsSection("Connection Mode") {
            ConnectionModeSelector(
                selected = connectionMode,
                onSelect = { connectionMode = it }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSection("General") {
            ToggleRow("Auto-connect on launch", autoConnect) { autoConnect = it }
            Spacer(modifier = Modifier.height(8.dp))
            ToggleRow("Kill Switch", killSwitch) { killSwitch = it }
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSection("DNS Server") {
            DnsSelector(selected = dns, onSelect = { dns = it })
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSection("Updates") {
            when (val us = state.updateState) {
                is UpdateState.Idle -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("v1.0.0", color = WireGuardTheme.text2, fontSize = 13.sp)
                        TextButton(onClick = { vm.checkForUpdate(context) }) {
                            Text("Check for Updates", color = WireGuardTheme.accent, fontSize = 13.sp)
                        }
                    }
                }
                is UpdateState.Checking -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Checking for updates...", color = WireGuardTheme.text2, fontSize = 13.sp)
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = WireGuardTheme.accent
                        )
                    }
                }
                is UpdateState.UpToDate -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Up to date", color = WireGuardTheme.accent, fontSize = 13.sp)
                        TextButton(onClick = { vm.checkForUpdate(context) }) {
                            Text("Check Again", color = WireGuardTheme.accent, fontSize = 13.sp)
                        }
                    }
                }
                is UpdateState.UpdateAvailable -> {
                    Text(
                        "v${us.versionName} available",
                        color = WireGuardTheme.orange,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (us.changelog.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(us.changelog, color = WireGuardTheme.text3, fontSize = 11.sp)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { vm.downloadUpdate(context, us.downloadUrl) }) {
                            Text("Download Update", color = WireGuardTheme.accent, fontSize = 13.sp)
                        }
                    }
                }
                is UpdateState.Downloading -> {
                    Text(
                        "Downloading... ${us.progress}%",
                        color = WireGuardTheme.accent,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { us.progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(2.dp))
                            .height(4.dp),
                        color = WireGuardTheme.accent,
                        trackColor = WireGuardTheme.surface,
                    )
                }
                is UpdateState.ReadyToInstall -> {
                    Text(
                        "Update downloaded",
                        color = WireGuardTheme.accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { vm.installUpdate(context, us.filePath) }) {
                            Text("Install Update", color = WireGuardTheme.accent, fontSize = 13.sp)
                        }
                    }
                }
                is UpdateState.Error -> {
                    Text("Error: ${us.message}", color = WireGuardTheme.red, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { vm.checkForUpdate(context) }) {
                            Text("Retry", color = WireGuardTheme.accent, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "WireGuardVPN v1.0.0",
            color = WireGuardTheme.text3,
            fontSize = 12.sp
        )
        Text(
            "sing-box backend · 3 connection modes",
            color = WireGuardTheme.text3,
            fontSize = 11.sp
        )
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title,
            color = WireGuardTheme.text2,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(WireGuardTheme.radius.dp))
                .background(WireGuardTheme.surface)
                .border(1.dp, WireGuardTheme.border, RoundedCornerShape(WireGuardTheme.radius.dp))
                .padding(12.dp)
        ) {
            content()
        }
    }
}

@Composable
fun ConnectionModeSelector(selected: ConnectionMode, onSelect: (ConnectionMode) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ConnectionMode.entries.forEach { mode ->
            val isSelected = mode == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(WireGuardTheme.radiusSmall.dp))
                    .background(if (isSelected) WireGuardTheme.accentDim else WireGuardTheme.bg)
                    .border(
                        1.dp,
                        if (isSelected) WireGuardTheme.accentBorder else WireGuardTheme.border,
                        RoundedCornerShape(WireGuardTheme.radiusSmall.dp)
                    )
                    .clickable { onSelect(mode) }
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    when (mode) {
                        ConnectionMode.DIRECT -> "Direct"
                        ConnectionMode.STEALTH -> "Stealth"
                        ConnectionMode.WARP_STEALTH -> "WARP"
                    },
                    color = if (isSelected) WireGuardTheme.accent else WireGuardTheme.text2,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    when (mode) {
                        ConnectionMode.DIRECT -> "WireGuard UDP"
                        ConnectionMode.STEALTH -> "VLESS+Reality"
                        ConnectionMode.WARP_STEALTH -> "Via Cloudflare"
                    },
                    color = WireGuardTheme.text3,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!value) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = WireGuardTheme.text1, fontSize = 14.sp)
        Switch(
            checked = value,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = WireGuardTheme.accent.copy(alpha = 0.5f),
                checkedThumbColor = WireGuardTheme.accent,
                uncheckedTrackColor = WireGuardTheme.surface,
                uncheckedThumbColor = WireGuardTheme.text3
            )
        )
    }
}

@Composable
fun DnsSelector(selected: String, onSelect: (String) -> Unit) {
    val options = listOf("1.1.1.1" to "Cloudflare", "8.8.8.8" to "Google", "9.9.9.9" to "Quad9")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (ip, name) ->
            val isSelected = ip == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(WireGuardTheme.radiusSmall.dp))
                    .background(if (isSelected) WireGuardTheme.accentDim else WireGuardTheme.bg)
                    .border(
                        1.dp,
                        if (isSelected) WireGuardTheme.accentBorder else WireGuardTheme.border,
                        RoundedCornerShape(WireGuardTheme.radiusSmall.dp)
                    )
                    .clickable { onSelect(ip) }
                    .padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    name,
                    color = if (isSelected) WireGuardTheme.accent else WireGuardTheme.text2,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(ip, color = WireGuardTheme.text3, fontSize = 10.sp)
            }
        }
    }
}
