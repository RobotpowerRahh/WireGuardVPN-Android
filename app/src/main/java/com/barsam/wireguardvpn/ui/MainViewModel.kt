package com.barsam.wireguardvpn.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.barsam.wireguardvpn.models.*
import com.barsam.wireguardvpn.services.ProfileStore
import com.barsam.wireguardvpn.services.UpdateManager
import com.barsam.wireguardvpn.services.UpdateState
import kotlinx.serialization.Serializable
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
    val pendingDownloadSha256: String? = null
)

class MainViewModel : ViewModel() {
    private val _state = MutableStateFlow(VpnUiState())
    val state: StateFlow<VpnUiState> = _state.asStateFlow()

    fun loadProfiles(context: Context) {
        val store = ProfileStore.get(context)
        _state.update { it.copy(profiles = store.profiles) }
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
    }

    fun disconnect(context: Context) {
        WireGuardVpnService.commands.trySend(WireGuardVpnService.VpnCommand.Disconnect)
        _state.update {
            it.copy(
                connectionState = ConnectionState.DISCONNECTING,
                errorMessage = null
            )
        }
    }

    fun startPolling(context: Context) {
        viewModelScope.launch {
            while (true) {
                val service = WireGuardVpnService.instance
                if (service != null) {
                    _state.update {
                        it.copy(
                            connectionState = mapState(service.connectionState),
                            statistics = service.statistics,
                            connectedDuration = if (service.connectedAt > 0)
                                System.currentTimeMillis() - service.connectedAt else 0,
                            errorMessage = service.errorMessage,
                            activeProfile = service.activeProfile ?: it.activeProfile,
                            activeMode = service.activeMode ?: it.activeMode
                        )
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
