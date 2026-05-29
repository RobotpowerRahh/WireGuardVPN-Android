package com.barsam.wireguardvpn.ui.servers

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.barsam.wireguardvpn.models.PeerConfig
import com.barsam.wireguardvpn.models.InterfaceConfig
import com.barsam.wireguardvpn.models.VPNProfile
import com.barsam.wireguardvpn.models.WireGuardConfig
import com.barsam.wireguardvpn.ui.MainViewModel
import com.barsam.wireguardvpn.ui.VpnUiState
import com.barsam.wireguardvpn.ui.theme.WireGuardTheme

@Composable
fun ServersScreen(vm: MainViewModel, state: VpnUiState) {
    val context = LocalContext.current
    var showAdd by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val text = context.contentResolver.openInputStream(it)?.bufferedReader()?.readText()
            val config = text?.let { WireGuardConfig.parse(it) }
            if (config != null) {
                val name = it.lastPathSegment?.substringAfterLast("/")?.removeSuffix(".conf") ?: "Imported"
                vm.addProfile(context, VPNProfile(name = name, config = config))
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Servers",
                color = WireGuardTheme.text1,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = {
                    fileLauncher.launch(arrayOf("*/*"))
                }) {
                    Icon(Icons.Filled.Upload, "Import .conf", tint = WireGuardTheme.text2)
                }
                IconButton(onClick = { showAdd = !showAdd }) {
                    Icon(
                        Icons.Filled.Add,
                        "Add Server",
                        tint = if (showAdd) WireGuardTheme.accent else WireGuardTheme.text2
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Add form
        AnimatedVisibility(visible = showAdd) {
            AddServerForm(
                onAdd = { name, privateKey, address, publicKey, endpoint, allowedIPs ->
                    val config = WireGuardConfig(
                        interface_ = InterfaceConfig(privateKey = privateKey, address = address),
                        peers = listOf(
                            PeerConfig(
                                publicKey = publicKey,
                                endpoint = endpoint,
                                allowedIPs = allowedIPs.ifEmpty { "0.0.0.0/0" }
                            )
                        )
                    )
                    vm.addProfile(context, VPNProfile(name = name, config = config))
                    showAdd = false
                },
                onCancel = { showAdd = false }
            )
        }

        // Server list
        if (state.profiles.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Dns,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = WireGuardTheme.text3
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("No servers configured", color = WireGuardTheme.text2, fontSize = 14.sp)
                    Text(
                        "Import a .conf file or add manually",
                        color = WireGuardTheme.text3,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.profiles, key = { it.id }) { profile ->
                    ServerCard(
                        profile = profile,
                        isActive = state.activeProfile?.id == profile.id,
                        isConnecting = state.connectionState == com.barsam.wireguardvpn.models.ConnectionState.CONNECTING,
                        onConnect = {
                            vm.requestConnect(
                                context as android.app.Activity,
                                profile,
                                state.activeMode
                            )
                        },
                        onDelete = { vm.deleteProfile(context, profile) }
                    )
                }
            }
        }
    }
}

@Composable
fun ServerCard(
    profile: VPNProfile,
    isActive: Boolean,
    isConnecting: Boolean,
    onConnect: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WireGuardTheme.radius.dp))
            .background(if (isActive) WireGuardTheme.accentDim else WireGuardTheme.surface)
            .border(
                1.dp,
                if (isActive) WireGuardTheme.accentBorder else WireGuardTheme.border,
                RoundedCornerShape(WireGuardTheme.radius.dp)
            )
            .clickable { expanded = !expanded }
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    profile.name,
                    color = if (isActive) WireGuardTheme.accentText else WireGuardTheme.text1,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    profile.config.peers.firstOrNull()?.endpoint ?: "No endpoint",
                    color = WireGuardTheme.text3,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            if (isActive) {
                Icon(Icons.Filled.CheckCircle, "Active", tint = WireGuardTheme.accent, modifier = Modifier.size(20.dp))
            }
        }

        AnimatedVisibility(visible = expanded) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onConnect,
                    enabled = !isActive && !isConnecting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WireGuardTheme.accent.copy(alpha = 0.15f),
                        contentColor = WireGuardTheme.accent,
                        disabledContainerColor = WireGuardTheme.surface,
                        disabledContentColor = WireGuardTheme.text3
                    ),
                    shape = RoundedCornerShape(WireGuardTheme.radiusSmall.dp)
                ) {
                    Text(if (isActive) "Connected" else "Connect", fontSize = 13.sp)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, "Delete", tint = WireGuardTheme.red.copy(alpha = 0.6f))
                }
            }
        }
    }
}

@Composable
fun AddServerForm(
    onAdd: (name: String, privateKey: String, address: String, publicKey: String, endpoint: String, allowedIPs: String) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("10.0.0.2/24") }
    var publicKey by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var allowedIPs by remember { mutableStateOf("0.0.0.0/0") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WireGuardTheme.radius.dp))
            .background(WireGuardTheme.surface)
            .border(1.dp, WireGuardTheme.border, RoundedCornerShape(WireGuardTheme.radius.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Add Server", color = WireGuardTheme.text1, fontWeight = FontWeight.Medium)
        FormField("Name", name) { name = it }
        FormField("Private Key", privateKey) { privateKey = it }
        FormField("Address", address) { address = it }
        FormField("Public Key", publicKey) { publicKey = it }
        FormField("Endpoint (ip:port)", endpoint) { endpoint = it }
        FormField("Allowed IPs", allowedIPs) { allowedIPs = it }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (name.isNotBlank() && privateKey.isNotBlank() && publicKey.isNotBlank() && endpoint.isNotBlank()) {
                        onAdd(name, privateKey, address, publicKey, endpoint, allowedIPs)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = WireGuardTheme.accent, contentColor = WireGuardTheme.bg),
                shape = RoundedCornerShape(WireGuardTheme.radiusSmall.dp)
            ) { Text("Add") }
            TextButton(onClick = onCancel) {
                Text("Cancel", color = WireGuardTheme.text2)
            }
        }
    }
}

@Composable
fun FormField(label: String, value: String, onChange: (String) -> Unit) {
    Column {
        Text(label, color = WireGuardTheme.text3, fontSize = 11.sp)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(
                color = WireGuardTheme.text1,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = WireGuardTheme.accentBorder,
                unfocusedBorderColor = WireGuardTheme.border,
                cursorColor = WireGuardTheme.accent
            ),
            singleLine = true,
            shape = RoundedCornerShape(WireGuardTheme.radiusSmall.dp)
        )
    }
}
