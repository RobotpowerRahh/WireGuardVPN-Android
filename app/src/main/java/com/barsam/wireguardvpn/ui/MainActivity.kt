package com.barsam.wireguardvpn.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.barsam.wireguardvpn.ui.dashboard.DashboardScreen
import com.barsam.wireguardvpn.ui.servers.ServersScreen
import com.barsam.wireguardvpn.ui.traffic.TrafficScreen
import com.barsam.wireguardvpn.ui.settings.SettingsScreen
import com.barsam.wireguardvpn.ui.theme.WireGuardTheme

class MainActivity : ComponentActivity() {
    private lateinit var vm: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm = MainViewModel()
        enableEdgeToEdge()
        vm.loadProfiles(this)
        vm.startPolling(this)
        vm.checkForUpdate(this)

        setContent {
            WireGuardApp(vm)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MainViewModel.REQUEST_VPN_PERMISSION) {
            vm.onVpnPermissionResult(resultCode == RESULT_OK, this)
        }
    }
}

@Composable
fun WireGuardApp(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    val screens = listOf("Dashboard", "Servers", "Traffic", "Settings")
    val icons = listOf(
        Icons.Filled.Dashboard,
        Icons.Filled.Storage,
        Icons.Filled.BarChart,
        Icons.Filled.Settings
    )

    Scaffold(
        containerColor = WireGuardTheme.bg,
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF111113),
                contentColor = WireGuardTheme.text2,
                tonalElevation = 0.dp
            ) {
                screens.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = {
                            Icon(
                                icons[index],
                                contentDescription = label,
                                tint = if (selectedTab == index) WireGuardTheme.accent else WireGuardTheme.text3
                            )
                        },
                        label = {
                            Text(
                                label,
                                color = if (selectedTab == index) WireGuardTheme.accent else WireGuardTheme.text3,
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = WireGuardTheme.accentDim
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(WireGuardTheme.bg)
                .padding(padding)
        ) {
            when (selectedTab) {
                0 -> DashboardScreen(vm, state)
                1 -> ServersScreen(vm, state)
                2 -> TrafficScreen(state)
                3 -> SettingsScreen(vm, state)
            }
        }
    }
}
