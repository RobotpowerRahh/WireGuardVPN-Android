package com.barsam.wireguardvpn.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.barsam.wireguardvpn.models.*
import com.barsam.wireguardvpn.services.ProfileStore
import com.barsam.wireguardvpn.services.TelemetryManager
import com.barsam.wireguardvpn.services.UpdateManager
import com.barsam.wireguardvpn.services.UpdateState
import com.barsam.wireguardvpn.services.WireGuardVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class VpnUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val activeProfile: VPNProfile? = null,
    val activeMode: ConnectionMode = ConnectionMode.DIRECT,
    val statistics: ConnectionStatistics = ConnectionStatistics(),
    val connectedDuration: Long = 0,
    val profiles: List<VPNProfile> = emptyList(),
    val errorMessage: String? = null,
    val selectedTab: Int = 0,
    val updateState: UpdateState = UpdateState.Idle,
    val pendingDownloadUrl: String? = null,
    val pendingDownloadSha256: String? = null,
    val telemetryEnabled: Boolean = false,
    val telemetryEndpoint: String = "http://localhost:8420",
    val connectionMode: ConnectionMode = ConnectionMode.DIRECT,
    val exitMode: ExitMode = ExitMode.RESIDENTIAL
)

class MainViewModel : ViewModel() {
    private val _state = MutableStateFlow(VpnUiState())
    val state: StateFlow<VpnUiState> = _state.asStateFlow()

    fun loadProfiles(context: Context) {
        val store = ProfileStore.get(context)
        val prefs = context.getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)
        val savedMode = ConnectionMode.fromValue(prefs.getInt("connection_mode", 0))
        val savedExit = ExitMode.fromValue(prefs.getInt("exit_mode", 0))
        _state.update { it.copy(profiles = store.profiles, connectionMode = savedMode, activeMode = savedMode, exitMode = savedExit) }
    }

    fun setConnectionMode(context: Context, mode: ConnectionMode) {
        context.getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)
            .edit().putInt("connection_mode", mode.value).apply()
        _state.update { it.copy(connectionMode = mode, activeMode = mode) }
    }

    fun setExitMode(context: Context, exit: ExitMode) {
        context.getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)
            .edit().putInt("exit_mode", exit.value).apply()
        _state.update { it.copy(exitMode = exit) }
    }

    fun addProfile(context: Context, profile: VPNProfile) {
        ProfileStore.get(context).add(profile)
        loadProfiles(context)
    }

    fun deleteProfile(context: Context, profile: VPNProfile) {
        ProfileStore.get(context).delete(profile)
        loadProfiles(context)
    }

    fun selectTab(index: Int) {
        _state.update { it.copy(selectedTab = index) }
    }

    fun requestConnect(activity: Activity, profile: VPNProfile, mode: ConnectionMode) {
        val intent = VpnService.prepare(activity)
        if (intent != null) {
            activity.startActivityForResult(intent, REQUEST_VPN_PERMISSION)
            _state.update { it.copy(
                activeProfile = profile,
                activeMode = mode,
                connectionState = ConnectionState.CONNECTING
            )}
            pendingConnect = Pair(profile, mode)
        } else {
            connect(activity, profile, mode)
        }
    }

    fun onVpnPermissionResult(granted: Boolean, context: Context) {
        if (granted && pendingConnect != null) {
            val (profile, mode) = pendingConnect!!
            connect(context, profile, mode)
        } else {
            _state.update { it.copy(connectionState = ConnectionState.DISCONNECTED) }
        }
        pendingConnect = null
    }

    private fun connect(context: Context, profile: VPNProfile, mode: ConnectionMode) {
        val intent = Intent(context, WireGuardVpnService::class.java)
        context.startForegroundService(intent)
        viewModelScope.launch {
            delay(100)
            WireGuardVpnService.commands.trySend(WireGuardVpnService.VpnCommand.Connect(profile, mode))
        }
        _state.update {
            it.copy(
                connectionState = ConnectionState.CONNECTING,
                activeProfile = profile,
                activeMode = mode
            )
        }
        TelemetryManager.log("connect_attempt", mapOf(
            "server" to profile.name,
            "mode" to mode.name
        ))
    }

    fun disconnect(context: Context) {
        val duration = _state.value.connectedDuration
        val stats = _state.value.statistics
        WireGuardVpnService.commands.trySend(WireGuardVpnService.VpnCommand.Disconnect)
        _state.update {
            it.copy(
                connectionState = ConnectionState.DISCONNECTING,
                errorMessage = null
            )
        }
        TelemetryManager.log("disconnect_request", mapOf(
            "durationMs" to duration,
            "bytesDown" to stats.bytesReceived,
            "bytesUp" to stats.bytesSent
        ))
    }

    fun startPolling(context: Context) {
        viewModelScope.launch {
            while (true) {
                val service = WireGuardVpnService.instance
                if (service != null) {
                    val prev = _state.value.connectionState
                    val next = mapState(service.connectionState)
                    _state.update {
                        it.copy(
                            connectionState = next,
                            statistics = service.statistics,
                            connectedDuration = if (service.connectedAt > 0)
                                System.currentTimeMillis() - service.connectedAt else 0,
                            errorMessage = service.errorMessage,
                            activeProfile = service.activeProfile ?: it.activeProfile,
                            activeMode = service.activeMode ?: it.activeMode
                        )
                    }
                    // Telemetry on state transitions
                    if (prev != next) {
                        when (next) {
                            ConnectionState.CONNECTED -> TelemetryManager.log("connected", mapOf(
                                "server" to (service.activeProfile?.name ?: ""),
                                "mode" to (service.activeMode?.name ?: "")
                            ))
                            ConnectionState.ERROR -> TelemetryManager.log("error", mapOf(
                                "message" to (service.errorMessage ?: "unknown")
                            ))
                            else -> {}
                        }
                    }
                } else {
                    val current = _state.value.connectionState
                    if (current != ConnectionState.DISCONNECTED && current != ConnectionState.ERROR) {
                        _state.update { it.copy(connectionState = ConnectionState.DISCONNECTED) }
                    }
                }
                delay(500)
            }
        }
    }

    // --- Update methods ---

    fun checkForUpdate(context: Context) {
        viewModelScope.launch {
            _state.update { it.copy(updateState = UpdateState.Checking) }
            val result = UpdateManager.checkForUpdate(context)
            if (result is UpdateState.UpdateAvailable) {
                _state.update { it.copy(updateState = result, pendingDownloadUrl = result.downloadUrl) }
            } else {
                _state.update { it.copy(updateState = result) }
            }
            TelemetryManager.log("update_check", mapOf("result" to result.javaClass.simpleName))
        }
    }

    fun downloadUpdate(context: Context, url: String) {
        viewModelScope.launch {
            val result = UpdateManager.downloadUpdate(context, url) { progress ->
                _state.update { it.copy(updateState = UpdateState.Downloading(progress)) }
            }
            _state.update { it.copy(updateState = result) }
        }
    }

    fun installUpdate(context: Context, filePath: String) {
        UpdateManager.installUpdate(context, filePath)
    }

    // --- Telemetry methods ---

    fun initTelemetry(context: Context) {
        TelemetryManager.init(context)
        _state.update {
            it.copy(
                telemetryEnabled = TelemetryManager.isEnabled,
                telemetryEndpoint = TelemetryManager.currentEndpoint
            )
        }
        TelemetryManager.log("app_launch")
    }

    fun setTelemetryEnabled(context: Context, enabled: Boolean) {
        TelemetryManager.setEnabled(context, enabled)
        _state.update { it.copy(telemetryEnabled = enabled) }
        if (enabled) {
            TelemetryManager.log("telemetry_enabled")
        }
    }

    fun setTelemetryEndpoint(context: Context, url: String) {
        TelemetryManager.setEndpoint(context, url)
        _state.update { it.copy(telemetryEndpoint = url) }
    }

    // --- Internal ---

    private fun mapState(s: WireGuardVpnService.ConnectionState): ConnectionState = when (s) {
        WireGuardVpnService.ConnectionState.DISCONNECTED -> ConnectionState.DISCONNECTED
        WireGuardVpnService.ConnectionState.CONNECTING -> ConnectionState.CONNECTING
        WireGuardVpnService.ConnectionState.CONNECTED -> ConnectionState.CONNECTED
        WireGuardVpnService.ConnectionState.DISCONNECTING -> ConnectionState.DISCONNECTING
        WireGuardVpnService.ConnectionState.ERROR -> ConnectionState.ERROR
    }

    private var pendingConnect: Pair<VPNProfile, ConnectionMode>? = null

    companion object {
        const val REQUEST_VPN_PERMISSION = 100
    }
}
