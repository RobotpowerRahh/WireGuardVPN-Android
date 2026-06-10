package com.barsam.wireguardvpn.services

import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.barsam.wireguardvpn.BuildConfig
import com.barsam.wireguardvpn.models.ConnectionMode
import com.barsam.wireguardvpn.models.ExitMode
import com.barsam.wireguardvpn.models.VPNProfile
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.InetNetwork
import com.wireguard.config.Peer
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

class WireGuardVpnService : VpnService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var vpnInterface: ParcelFileDescriptor? = null
    private var singboxProcess: Process? = null
    private var statsJob: Job? = null
    private var wgBackend: GoBackend? = null
    private var wgTunnel: Tunnel? = null
    private var singboxManager: SingboxManager? = null
    private var tunProxy: TunProxy? = null
    private var protectedRelay: ProtectedTcpRelay? = null

    var connectionState = ConnectionState.DISCONNECTED
        private set
    var activeProfile: VPNProfile? = null
        private set
    var activeMode: ConnectionMode? = null
        private set
    var statistics = com.barsam.wireguardvpn.models.ConnectionStatistics()
        private set
    var connectedAt: Long = 0
        private set
    var errorMessage: String? = null
        private set

    private var prevBytesReceived: Long = 0
    private var prevBytesSent: Long = 0
    private var prevStatsTime: Long = 0

    companion object {
        val commands = Channel<VpnCommand>(Channel.UNLIMITED)
        var instance: WireGuardVpnService? = null
            private set
    }

    sealed class VpnCommand {
        data class Connect(val profile: VPNProfile, val mode: ConnectionMode) : VpnCommand()
        data object Disconnect : VpnCommand()
    }

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        wgBackend = GoBackend(this)
        singboxManager = SingboxManager(this)
        showNotification("Connecting...")
        scope.launch {
            for (cmd in commands) {
                when (cmd) {
                    is VpnCommand.Connect -> handleConnect(cmd.profile, cmd.mode)
                    is VpnCommand.Disconnect -> handleDisconnect()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        cleanup()
        super.onDestroy()
    }

    override fun onRevoke() {
        scope.launch { handleDisconnect() }
    }

    private fun showNotification(text: String) {
        val notification = Notification.Builder(this, "vpn_connection")
            .setContentTitle("WireGuardVPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    private suspend fun handleConnect(profile: VPNProfile, mode: ConnectionMode) {
        if (connectionState == ConnectionState.CONNECTED || connectionState == ConnectionState.CONNECTING) return

        connectionState = ConnectionState.CONNECTING
        activeProfile = profile
        activeMode = mode
        errorMessage = null
        showNotification("Connecting to ${profile.name}...")

        when (mode) {
            ConnectionMode.DIRECT -> connectWireGuard(profile)
            ConnectionMode.STEALTH, ConnectionMode.WARP_STEALTH -> connectSingBox(profile, mode)
        }
    }

    // --- Direct WireGuard using the native GoBackend library ---

    private suspend fun connectWireGuard(profile: VPNProfile) {
        try {
            val peer = profile.config.peers.firstOrNull()
            if (peer == null) {
                setError("No server endpoint in profile")
                return
            }

            val ifaceBuilder = com.wireguard.config.Interface.Builder()
                .parseAddresses(profile.config.interface_.address)
                .parsePrivateKey(profile.config.interface_.privateKey)
            profile.config.interface_.dns?.let { ifaceBuilder.parseDnsServers(it) }

            val peerBuilder = Peer.Builder()
                .parsePublicKey(peer.publicKey)
                .parseEndpoint(peer.endpoint)
                .parseAllowedIPs(peer.allowedIPs)
            peer.presharedKey?.let { peerBuilder.parsePreSharedKey(it) }
            peer.persistentKeepalive?.let { peerBuilder.parsePersistentKeepalive(it.toString()) }

            val wgConfig = Config.Builder()
                .setInterface(ifaceBuilder.build())
                .addPeer(peerBuilder.build())
                .build()

            val tunnelName = "wg0"
            val tunnel = object : Tunnel {
                override fun getName() = tunnelName
                override fun onStateChange(newState: Tunnel.State) {}
            }
            wgTunnel = tunnel

            TelemetryManager.log("wg_connecting", mapOf(
                "server" to profile.name,
                "endpoint" to peer.endpoint
            ))

            val state = wgBackend?.setState(tunnel, Tunnel.State.UP, wgConfig)

            if (state == Tunnel.State.UP) {
                connectionState = ConnectionState.CONNECTED
                connectedAt = System.currentTimeMillis()
                prevBytesReceived = 0
                prevBytesSent = 0
                prevStatsTime = System.currentTimeMillis()
                startStatsPollingWG()
                showNotification("Connected - ${profile.name}")
                TelemetryManager.log("connected", mapOf("server" to profile.name, "mode" to "DIRECT"))
            } else {
                setError("WireGuard tunnel failed to come up")
            }
        } catch (e: Exception) {
            setError("Connection failed")
            TelemetryManager.log("error", mapOf("message" to "WG error: ${e.message?.take(100)}"))
        }
    }

    private fun startStatsPollingWG() {
        statsJob = scope.launch {
            while (isActive) {
                try {
                    val stats = wgBackend?.getStatistics(wgTunnel!!) ?: break
                    val now = System.currentTimeMillis()
                    val elapsed = (now - prevStatsTime) / 1000.0
                    if (elapsed > 0) {
                        statistics = statistics.copy(
                            downloadSpeed = (stats.totalRx() - prevBytesReceived) / elapsed,
                            uploadSpeed = (stats.totalTx() - prevBytesSent) / elapsed,
                            bytesReceived = stats.totalRx(),
                            bytesSent = stats.totalTx()
                        )
                    }
                    prevBytesReceived = stats.totalRx()
                    prevBytesSent = stats.totalTx()
                    prevStatsTime = now
                } catch (_: Exception) {}
                delay(1000)
            }
        }
    }

    // --- Stealth/WARP modes via sing-box SOCKS5 + TunProxy (TCP + full UDP) ---

    private suspend fun connectSingBox(profile: VPNProfile, mode: ConnectionMode) {
        // LoopholeVPN runs on one fixed server. Always use the compiled-in Vultr
        // endpoint for Stealth/WARP, ignoring the selected profile's WireGuard
        // endpoint (which may be a stale/old server). The baked-in Reality creds
        // are only valid for this host anyway.
        // Exit selection (same server, different port): RESIDENTIAL=:443 (clean Rogers
        // ISP IP), DIRECT=:8444 (straight out the Vultr node — faster, full UDP).
        val exitDirect = getSharedPreferences("vpn_prefs", MODE_PRIVATE)
            .getInt("exit_mode", 0) == ExitMode.DIRECT.value
        val serverAddress = BuildConfig.SERVER_HOST
        val serverPort = if (exitDirect) BuildConfig.SERVER_PORT_DIRECT else BuildConfig.SERVER_PORT

        try {
            val mgr = singboxManager ?: run {
                setError("sing-box manager not initialized")
                return
            }

            mgr.debugLog("=== connectSingBox: mode=$mode server=$serverAddress:$serverPort ===")

            // Fixed public resolver (queried through the tunnel); don't trust the
            // selected profile's DNS, which may point at an old server's resolver.
            val dns = "1.1.1.1"

            // 1. Start sing-box SOCKS5 proxy
            val configJson = mgr.generateConfig(mode, 0, serverAddress, dns, serverPort)
            val configPath = mgr.writeConfig(configJson)

            mgr.debugLog("Starting sing-box (SOCKS5 mode, server=$serverAddress:$serverPort)...")
            val started = mgr.start(configPath, 0)
            if (!started) {
                setError("sing-box failed to start")
                cleanup()
                return
            }

            // 2. Create VPN TUN interface
            mgr.debugLog("Creating VPN TUN interface...")
            val builder = Builder()
                .setSession("WireGuardVPN")
                .addAddress("172.19.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(dns)
                .setMtu(1500)
                .setBlocking(true)
            try { builder.addDisallowedApplication("com.barsam.WireGuardVPN") } catch (_: Exception) {}
            try { builder.addDisallowedApplication("com.barsam.WireGuardVPN.debug") } catch (_: Exception) {}
            val pfd = builder.establish()

            if (pfd == null) {
                setError("VPN permission denied")
                cleanup()
                return
            }

            // 3. Start TunProxy — bridges VPN TUN → SOCKS5 (TCP) + direct (UDP)
            mgr.debugLog("Starting TunProxy → 127.0.0.1:1080")
            tunProxy = TunProxy(this, pfd, "127.0.0.1", 1080, dns)
            tunProxy?.start()
            mgr.debugLog("TunProxy started — TCP via SOCKS5, UDP via protected sockets")

            // 4. Connected
            delay(1500)
            if (connectionState == ConnectionState.CONNECTING) {
                connectionState = ConnectionState.CONNECTED
                connectedAt = System.currentTimeMillis()
                prevBytesReceived = 0
                prevBytesSent = 0
                prevStatsTime = System.currentTimeMillis()
                startStatsPollingSingbox()
                val modeLabel = if (mode == ConnectionMode.STEALTH) "Stealth" else "WARP"
                showNotification("Connected - ${profile.name} ($modeLabel)")
                TelemetryManager.log("connected", mapOf("server" to profile.name, "mode" to modeLabel))
            }

        } catch (e: Exception) {
            singboxManager?.debugLog("connectSingBox FAILED: ${e.message}")
            setError("Connection failed: ${e.message?.take(80)}")
            cleanup()
        }
    }

    private fun startStatsPollingSingbox() {
        statsJob = scope.launch {
            while (isActive) {
                try {
                    val mgr = singboxManager ?: break
                    val stats = mgr.getTrafficStats()
                    if (stats != null) {
                        val (down, up) = stats
                        val now = System.currentTimeMillis()
                        val elapsed = (now - prevStatsTime) / 1000.0
                        if (elapsed > 0 && prevStatsTime > 0) {
                            statistics = statistics.copy(
                                downloadSpeed = (down - prevBytesReceived) / elapsed,
                                uploadSpeed = (up - prevBytesSent) / elapsed,
                                bytesReceived = down,
                                bytesSent = up
                            )
                        }
                        prevBytesReceived = down
                        prevBytesSent = up
                        prevStatsTime = now
                    }
                } catch (_: Exception) {}
                delay(1000)
            }
        }
    }

    // --- Common ---

    private suspend fun handleDisconnect() {
        if (connectionState == ConnectionState.DISCONNECTED) return
        connectionState = ConnectionState.DISCONNECTING
        cleanup()
        connectionState = ConnectionState.DISCONNECTED
        activeProfile = null
        statistics = com.barsam.wireguardvpn.models.ConnectionStatistics()
        activeMode = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        statsJob?.cancel()
        statsJob = null
        // TunProxy
        tunProxy?.stop()
        tunProxy = null
        // Protected TCP relay
        protectedRelay?.stop()
        protectedRelay = null
        // WireGuard
        try {
            wgTunnel?.let { wgBackend?.setState(it, Tunnel.State.DOWN, null) }
        } catch (_: Exception) {}
        // sing-box
        singboxManager?.stop()
        singboxProcess?.destroy()
        singboxProcess = null
        // VPN interface (already detached if using tun2socks, but close if still held)
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
    }

    private fun setError(msg: String) {
        connectionState = ConnectionState.ERROR
        errorMessage = msg
        showNotification("Error")
        TelemetryManager.log("error", mapOf("message" to msg))
    }
}
