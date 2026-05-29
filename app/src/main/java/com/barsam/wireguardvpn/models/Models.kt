package com.barsam.wireguardvpn.models

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class VPNProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val config: WireGuardConfig,
    val isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class WireGuardConfig(
    val interface_ : InterfaceConfig,
    val peers: List<PeerConfig>
) {
    fun toConfFile(): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = ${interface_.privateKey}")
        appendLine("Address = ${interface_.address}")
        interface_.dns?.let { appendLine("DNS = $it") }
        interface_.listenPort?.let { appendLine("ListenPort = $it") }
        interface_.mtu?.let { appendLine("MTU = $it") }

        for (peer in peers) {
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = ${peer.publicKey}")
            peer.presharedKey?.let { appendLine("PresharedKey = $it") }
            appendLine("Endpoint = ${peer.endpoint}")
            appendLine("AllowedIPs = ${peer.allowedIPs}")
            peer.persistentKeepalive?.let { appendLine("PersistentKeepalive = $it") }
        }
    }

    companion object {
        fun parse(text: String): WireGuardConfig? {
            var currentSection = ""
            val interfaceDict = mutableMapOf<String, String>()
            val peerDicts = mutableListOf<Map<String, String>>()
            var currentPeer = mutableMapOf<String, String>()

            for (line in text.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    if (currentSection == "Peer" && currentPeer.isNotEmpty()) {
                        peerDicts.add(currentPeer.toMap())
                    }
                    currentSection = trimmed.substring(1, trimmed.length - 1).trim()
                    if (currentSection == "Peer") currentPeer = mutableMapOf()
                    continue
                }

                val eqIndex = trimmed.indexOf('=') ?: continue
                val key = trimmed.substring(0, eqIndex).trim()
                val value = trimmed.substring(eqIndex + 1).trim()

                when (currentSection) {
                    "Interface" -> interfaceDict[key] = value
                    "Peer" -> currentPeer[key] = value
                }
            }
            if (currentSection == "Peer" && currentPeer.isNotEmpty()) {
                peerDicts.add(currentPeer.toMap())
            }

            val privateKey = interfaceDict["PrivateKey"] ?: return null
            val address = interfaceDict["Address"] ?: return null

            val iface = InterfaceConfig(
                privateKey = privateKey,
                address = address,
                dns = interfaceDict["DNS"],
                listenPort = interfaceDict["ListenPort"]?.toIntOrNull(),
                mtu = interfaceDict["MTU"]?.toIntOrNull()
            )

            val peers = peerDicts.mapNotNull { dict ->
                val pubKey = dict["PublicKey"] ?: return@mapNotNull null
                val endpoint = dict["Endpoint"] ?: return@mapNotNull null
                val allowed = dict["AllowedIPs"] ?: return@mapNotNull null
                PeerConfig(
                    publicKey = pubKey,
                    presharedKey = dict["PresharedKey"],
                    endpoint = endpoint,
                    allowedIPs = allowed,
                    persistentKeepalive = dict["PersistentKeepalive"]?.toIntOrNull()
                )
            }

            if (peers.isEmpty()) return null

            return WireGuardConfig(interface_ = iface, peers = peers)
        }
    }
}

@Serializable
data class InterfaceConfig(
    val privateKey: String,
    val address: String,
    val dns: String? = null,
    val listenPort: Int? = null,
    val mtu: Int? = null
)

@Serializable
data class PeerConfig(
    val publicKey: String,
    val presharedKey: String? = null,
    val endpoint: String,
    val allowedIPs: String,
    val persistentKeepalive: Int? = null
)

enum class ConnectionMode(val value: Int) {
    DIRECT(0),
    STEALTH(1),
    WARP_STEALTH(2);

    companion object {
        fun fromValue(value: Int) = entries.find { it.value == value } ?: DIRECT
    }
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR
}

data class ConnectionStatistics(
    val bytesReceived: Long = 0,
    val bytesSent: Long = 0,
    val downloadSpeed: Double = 0.0,
    val uploadSpeed: Double = 0.0
)
