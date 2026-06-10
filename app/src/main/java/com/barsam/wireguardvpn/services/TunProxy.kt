package com.barsam.wireguardvpn.services

import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges Android VPN tun interface to a SOCKS5 proxy.
 * TCP → SOCKS5 proxy (via VLESS obfuscation)
 * DNS (UDP 53) → SOCKS5 proxy
 * All other UDP → protected sockets (direct relay to bypass VPN loop)
 */
class TunProxy(
    private val vpn: VpnService,
    private val vpnFd: ParcelFileDescriptor,
    private val proxyHost: String,
    private val proxyPort: Int,
    private val dnsServer: String = "1.1.1.1"
) {
    private var running = false
    private var packetCount = 0
    private var tcpSynCount = 0
    private var dnsQueryCount = 0
    private var udpRelayCount = 0
    private val connections = ConcurrentHashMap<String, TcpConn>()
    private val udpFlows = ConcurrentHashMap<String, UdpFlow>()
    private val vpnIn = FileInputStream(vpnFd.fileDescriptor)
    private val vpnOut = FileOutputStream(vpnFd.fileDescriptor)
    private var readerThread: Thread? = null
    private var udpCleanupThread: Thread? = null

    fun start() {
        running = true
        readerThread = Thread({ readLoop() }, "tun-reader").apply { isDaemon = true; start() }
        // Cleanup idle UDP flows every 30 seconds
        udpCleanupThread = Thread({
            while (running) {
                try { Thread.sleep(30000) } catch (_: InterruptedException) { break }
                val now = System.currentTimeMillis()
                udpFlows.entries.removeIf { (_, flow) ->
                    val idle = now - flow.lastActivity > 60_000 // 60s idle timeout
                    if (idle) { flow.close(); true } else false
                }
            }
        }, "udp-cleanup").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        connections.values.forEach { it.close() }
        connections.clear()
        udpFlows.values.forEach { it.close() }
        udpFlows.clear()
        readerThread?.interrupt()
        udpCleanupThread?.interrupt()
        try { vpnIn.close() } catch (_: Exception) {}
        try { vpnOut.close() } catch (_: Exception) {}
    }

    // ── Packet reading loop ──────────────────────────────────────────

    private fun readLoop() {
        val buf = ByteArray(32768)
        android.util.Log.d("TunProxy", "readLoop started")
        while (running) {
            try {
                val n = vpnIn.read(buf)
                if (n < 20) continue

                packetCount++
                if (packetCount <= 5 || packetCount % 100 == 0) {
                    android.util.Log.d("TunProxy", "pkt #$packetCount: $n bytes")
                }

                val version = buf[0].toInt() and 0xF0 shr 4
                if (version != 4) continue

                val ihl = (buf[0].toInt() and 0x0F) * 4
                if (n < ihl) continue

                val proto = buf[9].toInt()
                val len = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
                if (len > n) continue

                when (proto) {
                    6 -> if (len >= ihl + 20) handleTcp(buf, len, ihl)
                    17 -> if (len >= ihl + 8) handleUdp(buf, len, ihl)
                }
            } catch (_: InterruptedException) { break }
            catch (e: Exception) {
                android.util.Log.e("TunProxy", "readLoop error: ${e.message}")
            }
        }
        android.util.Log.d("TunProxy", "readLoop ended: pkts=$packetCount tcp=$tcpSynCount dns=$dnsQueryCount udp=$udpRelayCount")
    }

    // ── TCP handling ─────────────────────────────────────────────────

    private fun handleTcp(pkt: ByteArray, len: Int, ihl: Int) {
        val srcPort = ((pkt[ihl].toInt() and 0xFF) shl 8) or (pkt[ihl + 1].toInt() and 0xFF)
        val dstPort = ((pkt[ihl + 2].toInt() and 0xFF) shl 8) or (pkt[ihl + 3].toInt() and 0xFF)
        val seq = int32(pkt, ihl + 4)
        val ack = int32(pkt, ihl + 8)
        val flags = pkt[ihl + 13].toInt() and 0xFF
        val dataOff = ((pkt[ihl + 12].toInt() and 0xF0) shr 4) * 4
        val srcIp = "${pkt[12].toInt() and 0xFF}.${pkt[13].toInt() and 0xFF}.${pkt[14].toInt() and 0xFF}.${pkt[15].toInt() and 0xFF}"
        val dstIp = "${pkt[16].toInt() and 0xFF}.${pkt[17].toInt() and 0xFF}.${pkt[18].toInt() and 0xFF}.${pkt[19].toInt() and 0xFF}"
        val key = "$srcIp:$srcPort-$dstIp:$dstPort"

        val syn = flags and 2 != 0
        val ackF = flags and 16 != 0
        val fin = flags and 1 != 0
        val rst = flags and 4 != 0

        if (rst) {
            connections.remove(key)?.close()
            return
        }

        if (syn && !ackF) {
            // New connection
            tcpSynCount++
            android.util.Log.d("TunProxy", "TCP SYN #$tcpSynCount: $srcIp:$srcPort -> $dstIp:$dstPort")
            val conn = TcpConn(srcIp, srcPort, dstIp, dstPort, seq)
            connections[key] = conn
            conn.serverSeq = (Math.random() * Integer.MAX_VALUE).toLong()
            conn.clientSeq = seq + 1  // ACK the client's SYN (SYN consumes 1 seq number)

            Thread({
                try {
                    val sock = Socket()
                    vpn.protect(sock)
                    sock.connect(java.net.InetSocketAddress(proxyHost, proxyPort), 5000)
                    conn.sock = sock

                    // SOCKS5 handshake
                    val si = sock.getInputStream()
                    val so = sock.getOutputStream()
                    so.write(byteArrayOf(0x05, 0x01, 0x00))
                    so.flush()
                    val r1 = ByteArray(2)
                    si.read(r1)
                    if (r1[0] != 0x05.toByte()) throw IOException("bad socks version")

                    // SOCKS5 CONNECT
                    val addr = InetAddress.getByName(dstIp).address
                    so.write(byteArrayOf(0x05, 0x01, 0x00, 0x01) + addr +
                            byteArrayOf((dstPort shr 8).toByte(), dstPort.toByte()))
                    so.flush()
                    val r2 = ByteArray(10)
                    si.read(r2)
                    if (r2[1] != 0x00.toByte()) throw IOException("socks connect refused")

                    conn.connected = true
                    android.util.Log.d("TunProxy", "SOCKS5 connected: $dstIp:$dstPort")
                    sendTcp(conn, flagSyn = true, flagAck = true)
                    conn.serverSeq++  // SYN-ACK consumes exactly 1 seq number; first data byte follows here

                    // Flush any data the client sent while we were connecting
                    conn.flushPending(sock.getOutputStream())

                    // Relay data from SOCKS5 to VPN
                    var dataPackets = 0
                    val rbuf = ByteArray(16384)
                    while (running && conn.connected) {
                        val rn = si.read(rbuf)
                        if (rn < 0) break
                        dataPackets++
                        if (dataPackets <= 3 || dataPackets % 50 == 0) {
                            android.util.Log.d("TunProxy", "relay→vpn $dstIp:$dstPort: $rn bytes (pkt #$dataPackets)")
                        }
                        conn.serverSeq = sendTcpData(conn, rbuf, rn)
                    }
                    android.util.Log.d("TunProxy", "relay done $dstIp:$dstPort: $dataPackets data packets, connected=${conn.connected}")
                } catch (e: Exception) {
                    android.util.Log.e("TunProxy", "SOCKS5 error $dstIp:$dstPort: ${e.message}")
                } finally {
                    conn.connected = false
                    try { sendTcp(conn, flagFin = true, flagAck = true) } catch (_: Exception) {}
                    connections.remove(key)
                }
            }, "tcp-$key").apply { isDaemon = true; start() }
            return
        }

        val conn = connections[key] ?: return

        if (fin) {
            conn.clientSeq = seq + 1  // FIN consumes 1 seq number
            sendTcp(conn, flagAck = true)
            connections.remove(key)?.close()
            return
        }

        if (ackF && dataOff < len - ihl) {
            val dataLen = len - ihl - dataOff
            if (dataLen > 0) {
                val data = pkt.copyOfRange(ihl + dataOff, ihl + dataOff + dataLen)
                try {
                    conn.clientSeq = seq + dataLen
                    sendTcp(conn, flagAck = true)
                    if (conn.connected) {
                        if (conn.bytesUp < 3) {
                            android.util.Log.d("TunProxy", "vpn→relay $dstIp:$dstPort: $dataLen bytes up")
                        }
                        conn.bytesUp++
                        conn.sock?.getOutputStream()?.apply {
                            write(data)
                            flush()
                        }
                    } else {
                        // Buffer until SOCKS5 connection is established
                        conn.addPending(data)
                        android.util.Log.d("TunProxy", "buffered $dataLen bytes for $dstIp:$dstPort (not connected yet)")
                    }
                } catch (_: Exception) {
                    connections.remove(key)?.close()
                }
            }
        } else if (ackF) {
            // Pure ACK — update seq tracking
            conn.clientSeq = seq
        }
    }

    private var ipId = 0

    private fun sendTcp(conn: TcpConn, flagSyn: Boolean = false, flagAck: Boolean = false,
                        flagFin: Boolean = false, flagRst: Boolean = false): ByteArray {
        var flags = 0
        if (flagSyn) flags = flags or 2
        if (flagAck) flags = flags or 16
        if (flagFin) flags = flags or 1
        if (flagRst) flags = flags or 4

        val dataOff = 5 shl 4  // 20 bytes, no options
        val pkt = ByteArray(40)
        // IP header
        ipId++
        pkt[0] = 0x45; pkt[1] = 0
        val totalLen = 40
        pkt[2] = (totalLen shr 8).toByte(); pkt[3] = totalLen.toByte()
        pkt[4] = (ipId shr 8).toByte(); pkt[5] = ipId.toByte()  // identification
        pkt[8] = 64; pkt[9] = 6  // TTL, protocol TCP
        setIp(pkt, 12, conn.dstIp)
        setIp(pkt, 16, conn.srcIp)
        ipChecksum(pkt)

        // TCP header
        pkt[20] = (conn.dstPort shr 8).toByte(); pkt[21] = conn.dstPort.toByte()
        pkt[22] = (conn.srcPort shr 8).toByte(); pkt[23] = conn.srcPort.toByte()
        set32(pkt, 24, conn.serverSeq)
        set32(pkt, 28, conn.clientSeq)
        pkt[32] = dataOff.toByte(); pkt[33] = flags.toByte()
        pkt[34] = 0xFF.toByte(); pkt[35] = 0xFF.toByte()  // window
        tcpChecksum(pkt, 40, conn.dstIp, conn.srcIp)

        synchronized(vpnOut) { vpnOut.write(pkt) }
        return pkt
    }

    private fun sendTcpData(conn: TcpConn, data: ByteArray, len: Int): Long {
        val totalLen = 20 + 20 + len
        val pkt = ByteArray(totalLen)
        // IP header
        ipId++
        pkt[0] = 0x45; pkt[1] = 0
        pkt[2] = (totalLen shr 8).toByte(); pkt[3] = totalLen.toByte()
        pkt[4] = (ipId shr 8).toByte(); pkt[5] = ipId.toByte()
        pkt[8] = 64; pkt[9] = 6
        setIp(pkt, 12, conn.dstIp)
        setIp(pkt, 16, conn.srcIp)
        ipChecksum(pkt)

        // TCP header
        pkt[20] = (conn.dstPort shr 8).toByte(); pkt[21] = conn.dstPort.toByte()
        pkt[22] = (conn.srcPort shr 8).toByte(); pkt[23] = conn.srcPort.toByte()
        set32(pkt, 24, conn.serverSeq)
        set32(pkt, 28, conn.clientSeq)
        pkt[32] = (5 shl 4).toByte(); pkt[33] = 24  // PSH+ACK
        pkt[34] = 0xFF.toByte(); pkt[35] = 0xFF.toByte()
        System.arraycopy(data, 0, pkt, 40, len)
        tcpChecksum(pkt, totalLen, conn.dstIp, conn.srcIp)

        synchronized(vpnOut) { vpnOut.write(pkt) }
        return conn.serverSeq + len
    }

    // ── UDP handling (DNS via SOCKS5 TCP, all other UDP via SOCKS5 UDP ASSOCIATE) ──

    private fun handleUdp(pkt: ByteArray, len: Int, ihl: Int) {
        val srcPort = ((pkt[ihl].toInt() and 0xFF) shl 8) or (pkt[ihl + 1].toInt() and 0xFF)
        val dstPort = ((pkt[ihl + 2].toInt() and 0xFF) shl 8) or (pkt[ihl + 3].toInt() and 0xFF)
        val udpLen = ((pkt[ihl + 4].toInt() and 0xFF) shl 8) or (pkt[ihl + 5].toInt() and 0xFF)
        val srcIp = "${pkt[12].toInt() and 0xFF}.${pkt[13].toInt() and 0xFF}.${pkt[14].toInt() and 0xFF}.${pkt[15].toInt() and 0xFF}"
        val dstIp = "${pkt[16].toInt() and 0xFF}.${pkt[17].toInt() and 0xFF}.${pkt[18].toInt() and 0xFF}.${pkt[19].toInt() and 0xFF}"

        if (dstPort == 53) {
            // DNS — relay through SOCKS5 TCP CONNECT
            handleDns(pkt, len, ihl, srcPort, dstPort, udpLen, srcIp, dstIp)
            return
        }

        // All other UDP (games, QUIC, etc.) — relay through SOCKS5 UDP ASSOCIATE
        val payload = pkt.copyOfRange(ihl + 8, ihl + udpLen)
        if (payload.isEmpty()) return

        val key = "$srcIp:$srcPort-$dstIp:$dstPort"
        val existing = udpFlows[key]
        if (existing != null && !existing.isDead()) {
            existing.lastActivity = System.currentTimeMillis()
            existing.send(dstIp, dstPort, payload)
            return
        }

        // Create new UDP ASSOCIATE flow
        udpRelayCount++
        android.util.Log.d("TunProxy", "UDP ASSOCIATE #$udpRelayCount: $srcIp:$srcPort -> $dstIp:$dstPort")
        Thread({
            try {
                val flow = setupUdpAssociate(srcIp, srcPort, dstIp, dstPort, key)
                if (flow != null) {
                    udpFlows[key] = flow
                    flow.send(dstIp, dstPort, payload)
                }
            } catch (e: Exception) {
                android.util.Log.e("TunProxy", "UDP ASSOCIATE failed: ${e.message}")
            }
        }, "udp-setup-$dstPort").apply { isDaemon = true; start() }
    }

    private fun handleDns(pkt: ByteArray, len: Int, ihl: Int, srcPort: Int, dstPort: Int, udpLen: Int, srcIp: String, dstIp: String) {
        dnsQueryCount++
        android.util.Log.d("TunProxy", "DNS query #$dnsQueryCount: $srcIp:$srcPort -> $dstIp:$dstPort")
        val payload = pkt.copyOfRange(ihl + 8, ihl + udpLen)
        if (payload.size < 12) return

        Thread({
            try {
                val sock = Socket()
                vpn.protect(sock)
                sock.connect(java.net.InetSocketAddress(proxyHost, proxyPort), 5000)
                sock.soTimeout = 5000
                val si = sock.getInputStream()
                val so = sock.getOutputStream()

                // SOCKS5 handshake
                so.write(byteArrayOf(0x05, 0x01, 0x00)); so.flush()
                val r1 = ByteArray(2); si.read(r1)

                // SOCKS5 CONNECT to DNS server port 53
                val addr = InetAddress.getByName(dnsServer).address
                so.write(byteArrayOf(0x05, 0x01, 0x00, 0x01) + addr +
                        byteArrayOf(0, 53))
                so.flush()
                val r2 = ByteArray(10); si.read(r2)

                // DNS over TCP: 2-byte length prefix + query
                val frame = ByteArray(payload.size + 2)
                frame[0] = (payload.size shr 8).toByte()
                frame[1] = payload.size.toByte()
                System.arraycopy(payload, 0, frame, 2, payload.size)
                so.write(frame); so.flush()

                // Read response: 2-byte length + DNS response
                val lbuf = ByteArray(2); si.read(lbuf)
                val rLen = ((lbuf[0].toInt() and 0xFF) shl 8) or (lbuf[1].toInt() and 0xFF)
                val resp = ByteArray(rLen)
                var off = 0
                while (off < rLen) {
                    val r = si.read(resp, off, rLen - off)
                    if (r < 0) break
                    off += r
                }
                sock.close()

                if (off > 0) {
                    android.util.Log.d("TunProxy", "DNS resolved OK: $off bytes")
                    sendUdp(pkt, ihl, srcIp, srcPort, dstIp, resp, off)
                }
            } catch (e: Exception) {
                android.util.Log.e("TunProxy", "DNS error: ${e.message}")
            }
        }, "dns-$srcPort").apply { isDaemon = true; start() }
    }

    /**
     * Set up a SOCKS5 UDP ASSOCIATE session.
     * UDP traffic is tunneled through the VLESS obfuscated channel.
     */
    private fun setupUdpAssociate(srcIp: String, srcPort: Int, dstIp: String, dstPort: Int, key: String): UdpFlow? {
        // 1. TCP connection for SOCKS5 UDP ASSOCIATE handshake (must stay alive)
        val tcpSock = Socket()
        vpn.protect(tcpSock)
        tcpSock.connect(java.net.InetSocketAddress(proxyHost, proxyPort), 5000)
        tcpSock.soTimeout = 0  // keep alive

        val si = tcpSock.getInputStream()
        val so = tcpSock.getOutputStream()

        // 2. SOCKS5 handshake
        so.write(byteArrayOf(0x05, 0x01, 0x00))
        so.flush()
        val r1 = ByteArray(2)
        si.read(r1)
        if (r1[0] != 0x05.toByte()) {
            tcpSock.close()
            return null
        }

        // 3. UDP ASSOCIATE command (CMD=0x03), destination 0.0.0.0:0
        so.write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        so.flush()

        // 4. Read response: VER(1) + REP(1) + RSV(1) + ATYP(1) + ADDR + PORT
        val header = ByteArray(4)
        var totalRead = 0
        while (totalRead < 4) {
            val n = si.read(header, totalRead, 4 - totalRead)
            if (n < 0) { tcpSock.close(); return null }
            totalRead += n
        }

        if (header[1] != 0x00.toByte()) {
            android.util.Log.e("TunProxy", "UDP ASSOCIATE rejected: rep=${header[1]}")
            tcpSock.close()
            return null
        }

        // Parse bind address based on ATYP
        val atyp = header[3].toInt() and 0xFF
        var bindAddr = proxyHost  // default to proxy host
        when (atyp) {
            0x01 -> { // IPv4
                val addrBytes = ByteArray(4)
                var read = 0
                while (read < 4) { val n = si.read(addrBytes, read, 4 - read); if (n < 0) break; read += n }
                bindAddr = addrBytes.map { (it.toInt() and 0xFF).toString() }.joinToString(".")
                if (bindAddr == "0.0.0.0") bindAddr = proxyHost
            }
            0x03 -> { // Domain
                val lenByte = ByteArray(1); si.read(lenByte)
                val domain = ByteArray(lenByte[0].toInt() and 0xFF); si.read(domain)
                bindAddr = String(domain)
            }
            0x04 -> { // IPv6
                val addrBytes = ByteArray(16)
                var read = 0
                while (read < 16) { val n = si.read(addrBytes, read, 16 - read); if (n < 0) break; read += n }
                bindAddr = InetAddress.getByAddress(addrBytes).hostAddress ?: proxyHost
            }
        }

        val portBytes = ByteArray(2)
        var read = 0
        while (read < 2) { val n = si.read(portBytes, read, 2 - read); if (n < 0) break; read += n }
        val bindPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

        android.util.Log.d("TunProxy", "UDP ASSOCIATE relay=$bindAddr:$bindPort")

        // 5. Create UDP socket for sending to relay — bind to loopback to match TCP source
        val udpSock = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        vpn.protect(udpSock)
        val relayAddr = InetAddress.getByName(bindAddr)

        val flow = UdpFlow(srcIp, srcPort, dstIp, dstPort, udpSock, tcpSock, relayAddr, bindPort)

        // 6. Reader thread — receives UDP responses from SOCKS5 relay → VPN TUN
        Thread({
            val buf = ByteArray(65535)
            while (running && !udpSock.isClosed) {
                try {
                    val dp = DatagramPacket(buf, buf.size)
                    udpSock.receive(dp)
                    if (dp.length > 10) {
                        // Parse SOCKS5 UDP header: RSV(2) + FRAG(1) + ATYP(1) + ADDR + PORT + DATA
                        val atypR = buf[3].toInt() and 0xFF
                        var dataOffset = 4
                        when (atypR) {
                            0x01 -> dataOffset += 4 + 2   // IPv4 + port
                            0x03 -> dataOffset += 1 + (buf[4].toInt() and 0xFF) + 2  // domain + port
                            0x04 -> dataOffset += 16 + 2  // IPv6 + port
                        }
                        val dataLen = dp.length - dataOffset
                        if (dataLen > 0) {
                            flow.lastActivity = System.currentTimeMillis()
                            android.util.Log.d("TunProxy", "relay→UDP $dstIp:$dstPort: $dataLen bytes back")
                            sendUdpResponse(srcIp, srcPort, dstIp, dstPort, buf, dataOffset, dataLen)
                        }
                    }
                } catch (_: java.net.SocketException) { break }
                catch (_: java.net.SocketTimeoutException) { continue }
                catch (_: Exception) { break }
                }
        }, "udp-relay-$dstPort").apply { isDaemon = true; start() }

        // 7. TCP keepalive — when TCP closes, the UDP ASSOCIATE expires
        Thread({
            try {
                val b = ByteArray(1)
                while (running && !tcpSock.isClosed) {
                    if (si.read(b) < 0) break
                }
            } catch (_: Exception) {}
            udpFlows.remove(key)
            flow.close()
        }, "udp-keepalive-$dstPort").apply { isDaemon = true; start() }

        return flow
    }

    // Send UDP response back through VPN TUN to the app
    private fun sendUdpResponse(dstIp: String, dstPort: Int, srcIp: String, srcPort: Int, buf: ByteArray, offset: Int, len: Int) {
        val udpLen = 8 + len
        val totalLen = 20 + udpLen
        val pkt = ByteArray(totalLen)

        // IP header
        pkt[0] = 0x45; pkt[1] = 0
        pkt[2] = (totalLen shr 8).toByte(); pkt[3] = totalLen.toByte()
        pkt[8] = 64; pkt[9] = 17  // TTL, protocol UDP
        setIp(pkt, 12, srcIp)   // src = remote server
        setIp(pkt, 16, dstIp)   // dst = VPN client
        ipChecksum(pkt)

        // UDP header
        pkt[20] = (srcPort shr 8).toByte(); pkt[21] = srcPort.toByte()
        pkt[22] = (dstPort shr 8).toByte(); pkt[23] = dstPort.toByte()
        pkt[24] = (udpLen shr 8).toByte(); pkt[25] = udpLen.toByte()
        pkt[26] = 0; pkt[27] = 0
        System.arraycopy(buf, offset, pkt, 28, len)

        synchronized(vpnOut) { vpnOut.write(pkt, 0, totalLen) }
    }

    private fun sendUdp(orig: ByteArray, ihl: Int, dstIp: String, dstPort: Int,
                        srcIp: String, payload: ByteArray, payloadLen: Int) {
        val udpLen = 8 + payloadLen
        val totalLen = 20 + udpLen
        val pkt = ByteArray(totalLen)

        // IP header
        pkt[0] = 0x45; pkt[1] = 0
        pkt[2] = (totalLen shr 8).toByte(); pkt[3] = totalLen.toByte()
        pkt[8] = 64; pkt[9] = 17  // TTL, protocol UDP
        setIp(pkt, 12, srcIp)   // src = DNS server
        setIp(pkt, 16, dstIp)   // dst = original sender
        ipChecksum(pkt)

        // UDP header
        pkt[20] = (53 shr 8).toByte(); pkt[21] = 53.toByte()  // src port = 53
        pkt[22] = (dstPort shr 8).toByte(); pkt[23] = dstPort.toByte()
        pkt[24] = (udpLen shr 8).toByte(); pkt[25] = udpLen.toByte()
        pkt[26] = 0; pkt[27] = 0  // checksum (0 = disabled for IPv4)
        System.arraycopy(payload, 0, pkt, 28, payloadLen)

        synchronized(vpnOut) { vpnOut.write(pkt, 0, totalLen) }
    }

    // ── Checksum helpers ─────────────────────────────────────────────

    private fun ipChecksum(pkt: ByteArray) {
        pkt[10] = 0; pkt[11] = 0
        var sum = 0L
        for (i in 0 until 20 step 2) {
            sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
        }
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        val cs = (sum.toInt().inv()) and 0xFFFF
        pkt[10] = (cs shr 8).toByte(); pkt[11] = cs.toByte()
    }

    private fun tcpChecksum(pkt: ByteArray, len: Int, srcIp: String, dstIp: String) {
        pkt[36] = 0; pkt[37] = 0
        var sum = 0L
        // Pseudo-header: source IP as 16-bit words (network byte order)
        val sp = srcIp.split(".").map { it.toInt() }
        sum += ((sp[0] shl 8) or sp[1]).toLong()
        sum += ((sp[2] shl 8) or sp[3]).toLong()
        // Destination IP as 16-bit words
        val dp = dstIp.split(".").map { it.toInt() }
        sum += ((dp[0] shl 8) or dp[1]).toLong()
        sum += ((dp[2] shl 8) or dp[3]).toLong()
        sum += 6L  // protocol TCP
        sum += (len - 20).toLong()  // TCP length
        for (i in 20 until len - 1 step 2) {
            sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
        }
        if (len % 2 != 0) sum += (pkt[len - 1].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        val cs = (sum.toInt().inv()) and 0xFFFF
        pkt[36] = (cs shr 8).toByte(); pkt[37] = cs.toByte()
    }

    // ── Byte helpers ─────────────────────────────────────────────────

    private fun int32(buf: ByteArray, off: Int): Long =
        ((buf[off].toLong() and 0xFF) shl 24) or
        ((buf[off + 1].toLong() and 0xFF) shl 16) or
        ((buf[off + 2].toLong() and 0xFF) shl 8) or
        (buf[off + 3].toLong() and 0xFF)

    private fun set32(buf: ByteArray, off: Int, v: Long) {
        buf[off] = (v shr 24).toByte(); buf[off + 1] = (v shr 16).toByte()
        buf[off + 2] = (v shr 8).toByte(); buf[off + 3] = v.toByte()
    }

    private fun setIp(buf: ByteArray, off: Int, ip: String) {
        val parts = ip.split(".")
        for (i in 0 until 4) buf[off + i] = parts[i].toInt().toByte()
    }

    // ── Connection state ─────────────────────────────────────────────

    private class UdpFlow(
        val srcIp: String, val srcPort: Int,
        val dstIp: String, val dstPort: Int,
        val udpSocket: DatagramSocket,
        val tcpSocket: Socket,          // must stay alive for UDP ASSOCIATE
        val relayAddr: InetAddress,     // SOCKS5 UDP relay address
        val relayPort: Int              // SOCKS5 UDP relay port
    ) {
        @Volatile var lastActivity: Long = System.currentTimeMillis()

        fun isDead(): Boolean = udpSocket.isClosed || tcpSocket.isClosed

        fun send(targetIp: String, targetPort: Int, data: ByteArray) {
            // Build SOCKS5 UDP datagram: RSV(2) + FRAG(1) + ATYP(1) + DST.ADDR + DST.PORT + DATA
            val addr = InetAddress.getByName(targetIp).address  // IPv4 = 4 bytes
            val headerSize = 3 + 1 + 4 + 2  // RSV + FRAG + ATYP + IPv4 + PORT
            val pkt = ByteArray(headerSize + data.size)
            pkt[0] = 0; pkt[1] = 0    // RSV
            pkt[2] = 0                // FRAG (no fragmentation)
            pkt[3] = 0x01             // ATYP = IPv4
            System.arraycopy(addr, 0, pkt, 4, 4)
            pkt[8] = (targetPort shr 8).toByte()
            pkt[9] = targetPort.toByte()
            System.arraycopy(data, 0, pkt, headerSize, data.size)

            try {
                udpSocket.send(DatagramPacket(pkt, pkt.size, relayAddr, relayPort))
                android.util.Log.d("TunProxy", "UDP→relay $targetIp:$targetPort: ${data.size} bytes via $relayAddr:$relayPort")
            } catch (e: Exception) {
                android.util.Log.e("TunProxy", "UDP send to relay failed: ${e.message}")
            }
        }

        fun close() {
            try { udpSocket.close() } catch (_: Exception) {}
            try { tcpSocket.close() } catch (_: Exception) {}
        }
    }

    private class TcpConn(
        val srcIp: String, val srcPort: Int,
        val dstIp: String, val dstPort: Int,
        initSeq: Long
    ) {
        var clientSeq = initSeq
        var serverSeq = 0L
        var sock: Socket? = null
        var connected = false
        var bytesUp = 0
        val pendingData = mutableListOf<ByteArray>()

        @Synchronized
        fun addPending(data: ByteArray) { pendingData.add(data) }

        @Synchronized
        fun flushPending(out: java.io.OutputStream) {
            for (d in pendingData) out.write(d)
            out.flush()
            pendingData.clear()
        }

        fun close() {
            connected = false
            try { sock?.close() } catch (_: Exception) {}
        }
    }
}
