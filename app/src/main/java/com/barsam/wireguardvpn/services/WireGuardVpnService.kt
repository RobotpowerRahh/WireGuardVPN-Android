package com.barsam.wireguardvpn.services

import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.barsam.wireguardvpn.BuildConfig
import com.barsam.wireguardvpn.models.ConnectionMode
import com.barsam.wireguardvpn.models.VPNProfile
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

class WireGuardVpnService : VpnService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var vpnInterface: ParcelFileDescriptor? = null
    private var singboxProcess: Process? = null
    private var statsJob: Job? = null

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

    private val vlessUUID get() = BuildConfig.VLESS_UUID
    private val warpVlessUUID get() = BuildConfig.WARP_VLESS_UUID
    private val realityPublicKey get() = BuildConfig.REALITY_PUBLIC_KEY
    private val realityShortID get() = BuildConfig.REALITY_SHORT_ID
    private val tlsSNI get() = BuildConfig.TLS_SNI

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

        val peerEndpoint = profile.config.peers.firstOrNull()?.endpoint ?: run {
            connectionState = ConnectionState.ERROR
            errorMessage = "No server endpoint in profile"
            showNotification("Error: no endpoint")
            return
        }
        val serverAddress = peerEndpoint.substringBefore(":")

        val config = when (mode) {
            ConnectionMode.DIRECT -> generateWireGuardConfig(serverAddress, profile)
            ConnectionMode.STEALTH -> generateVLESSConfig(serverAddress, profile)
            ConnectionMode.WARP_STEALTH -> generateWARPConfig(serverAddress, profile)
        }

        val configFile = File(filesDir, "sing-box-config.json")
        configFile.writeText(config)
        configFile.setReadable(false, false)
        configFile.setReadable(true, true)

        try {
            vpnInterface = Builder()
                .setSession("WireGuardVPN")
                .addAddress("172.19.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(profile.config.interface_.dns ?: "1.1.1.1")
                .setMtu(1280)
                .setBlocking(true)
                .establish()

            if (vpnInterface == null) {
                connectionState = ConnectionState.ERROR
                errorMessage = "VPN permission denied"
                showNotification("Error: permission denied")
                return
            }
        } catch (e: Exception) {
            connectionState = ConnectionState.ERROR
            errorMessage = "Failed to create VPN tunnel"
            showNotification("Error: tunnel failed")
            return
        }

        val singboxBin = File(applicationInfo.nativeLibraryDir, "libsingbox.so")
        if (!singboxBin.exists()) {
            val altBin = File(filesDir, "sing-box")
            if (!altBin.exists()) {
                connectionState = ConnectionState.ERROR
                errorMessage = "VPN engine not found"
                showNotification("Error: engine missing")
                return
            }
        }

        try {
            val binPath = if (singboxBin.exists()) singboxBin.absolutePath
                else File(filesDir, "sing-box").absolutePath

            val builder = ProcessBuilder(binPath, "run", "-c", configFile.absolutePath)
                .redirectErrorStream(true)
            builder.environment()["HOME"] = filesDir.absolutePath
            singboxProcess = builder.start()

            val connected = waitForTunnel()
            if (connected) {
                connectionState = ConnectionState.CONNECTED
                connectedAt = System.currentTimeMillis()
                prevBytesReceived = 0
                prevBytesSent = 0
                prevStatsTime = System.currentTimeMillis()
                startStatsPolling()
                showNotification("Connected - ${profile.name}")
            } else {
                cleanup()
                connectionState = ConnectionState.ERROR
                errorMessage = "Failed to establish tunnel"
                showNotification("Error: tunnel failed")
            }
        } catch (e: Exception) {
            cleanup()
            connectionState = ConnectionState.ERROR
            errorMessage = "Connection failed"
            showNotification("Error: connection failed")
        }
    }

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
        singboxProcess?.destroy()
        singboxProcess = null
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
    }

    private suspend fun waitForTunnel(): Boolean {
        repeat(20) {
            delay(500)
            if (checkClashAPI()) return true
        }
        return false
    }

    private fun checkClashAPI(): Boolean {
        return try {
            val conn = URL("http://127.0.0.1:9090/version").openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.responseCode == 200
        } catch (_: Exception) {
            false
        }
    }

    private fun startStatsPolling() {
        statsJob = scope.launch {
            while (isActive) {
                pollStats()
                delay(1000)
            }
        }
    }

    private fun pollStats() {
        try {
            val conn = URL("http://127.0.0.1:9090/connections").openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            if (conn.responseCode != 200) return

            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val downTotal = json.optLong("downloadTotal", 0)
            val upTotal = json.optLong("uploadTotal", 0)

            val now = System.currentTimeMillis()
            val elapsed = (now - prevStatsTime) / 1000.0
            if (elapsed > 0) {
                statistics = statistics.copy(
                    downloadSpeed = (downTotal - prevBytesReceived) / elapsed,
                    uploadSpeed = (upTotal - prevBytesSent) / elapsed,
                    bytesReceived = downTotal,
                    bytesSent = upTotal
                )
            }
            prevBytesReceived = downTotal
            prevBytesSent = upTotal
            prevStatsTime = now
        } catch (_: Exception) {}
    }

    // --- Config generators ---

    private fun generateWireGuardConfig(serverAddress: String, profile: VPNProfile): String {
        val port = profile.config.peers.firstOrNull()?.endpoint?.substringAfterLast(":") ?: "51820"
        val dns = profile.config.interface_.dns ?: "1.1.1.1"
        val pubKey = profile.config.peers.firstOrNull()?.publicKey ?: ""
        return """
        {
          "log": {"level": "warning"},
          "dns": {
            "servers": [
              {"tag": "remote", "type": "udp", "server": "$dns", "detour": "wg-ep"}
            ],
            "strategy": "prefer_ipv4"
          },
          "inbounds": [{
            "type": "tun",
            "tag": "tun-in",
            "address": "172.19.0.1/30",
            "auto_route": true,
            "strict_route": true,
            "stack": "mixed",
            "fd": ${vpnInterface?.fd ?: -1}
          }],
          "endpoints": [
            {
              "type": "wireguard",
              "tag": "wg-ep",
              "address": ["${profile.config.interface_.address}"],
              "private_key": "${profile.config.interface_.privateKey}",
              "peers": [
                {
                  "address": "$serverAddress",
                  "port": $port,
                  "public_key": "$pubKey",
                  "allowed_ips": ["0.0.0.0/0"]
                }
              ],
              "mtu": 1280
            }
          ],
          "outbounds": [
            {"type": "direct", "tag": "direct"},
            {"type": "block", "tag": "block"}
          ],
          "route": {
            "rules": [
              {"action": "sniff"},
              {"protocol": "dns", "action": "hijack-dns"},
              {"ip_is_private": true, "outbound": "direct"}
            ],
            "auto_detect_interface": true,
            "final": "wg-ep"
          },
          "experimental": {
            "clash_api": {
              "external_controller": "127.0.0.1:9090"
            }
          }
        }
        """.trimIndent()
    }

    private fun generateVLESSConfig(serverAddress: String, profile: VPNProfile): String {
        val dns = profile.config.interface_.dns ?: "1.1.1.1"
        return """
        {
          "log": {"level": "warning"},
          "dns": {
            "servers": [
              {"tag": "remote", "type": "udp", "server": "$dns", "detour": "proxy-out"}
            ],
            "strategy": "prefer_ipv4"
          },
          "inbounds": [{
            "type": "tun",
            "tag": "tun-in",
            "address": "172.19.0.1/30",
            "auto_route": true,
            "strict_route": true,
            "stack": "mixed",
            "fd": ${vpnInterface?.fd ?: -1}
          }],
          "outbounds": [
            {
              "type": "vless",
              "tag": "proxy-out",
              "server": "$serverAddress",
              "server_port": 443,
              "uuid": "$vlessUUID",
              "flow": "xtls-rprx-vision",
              "tls": {
                "enabled": true,
                "server_name": "$tlsSNI",
                "utls": {"enabled": true, "fingerprint": "safari"},
                "reality": {
                  "enabled": true,
                  "public_key": "$realityPublicKey",
                  "short_id": "$realityShortID"
                }
              }
            },
            {"type": "direct", "tag": "direct"},
            {"type": "block", "tag": "block"}
          ],
          "route": {
            "rules": [
              {"action": "sniff"},
              {"protocol": "dns", "action": "hijack-dns"},
              {"ip_is_private": true, "outbound": "direct"}
            ],
            "auto_detect_interface": true,
            "final": "proxy-out"
          },
          "experimental": {
            "clash_api": {
              "external_controller": "127.0.0.1:9090"
            }
          }
        }
        """.trimIndent()
    }

    private fun generateWARPConfig(serverAddress: String, profile: VPNProfile): String {
        val dns = profile.config.interface_.dns ?: "1.1.1.1"
        return """
        {
          "log": {"level": "warning"},
          "dns": {
            "servers": [
              {"tag": "remote", "type": "udp", "server": "$dns", "detour": "proxy-out"}
            ],
            "strategy": "prefer_ipv4"
          },
          "inbounds": [{
            "type": "tun",
            "tag": "tun-in",
            "address": "172.19.0.1/30",
            "auto_route": true,
            "strict_route": true,
            "stack": "mixed",
            "fd": ${vpnInterface?.fd ?: -1}
          }],
          "outbounds": [
            {
              "type": "vless",
              "tag": "proxy-out",
              "server": "$serverAddress",
              "server_port": 443,
              "uuid": "$warpVlessUUID",
              "flow": "xtls-rprx-vision",
              "tls": {
                "enabled": true,
                "server_name": "$tlsSNI",
                "utls": {"enabled": true, "fingerprint": "safari"},
                "reality": {
                  "enabled": true,
                  "public_key": "$realityPublicKey",
                  "short_id": "$realityShortID"
                }
              }
            },
            {"type": "direct", "tag": "direct"},
            {"type": "block", "tag": "block"}
          ],
          "route": {
            "rules": [
              {"action": "sniff"},
              {"protocol": "dns", "action": "hijack-dns"},
              {"ip_is_private": true, "outbound": "direct"}
            ],
            "auto_detect_interface": true,
            "final": "proxy-out"
          },
          "experimental": {
            "clash_api": {
              "external_controller": "127.0.0.1:9090"
            }
          }
        }
        """.trimIndent()
    }
}
