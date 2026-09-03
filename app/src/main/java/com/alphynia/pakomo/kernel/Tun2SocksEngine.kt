package com.alphynia.pakomo.kernel

import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.alphynia.pakomo.kernel.icmp.IcmpResponder
import com.alphynia.pakomo.kernel.ip.Ipv4Packet
import com.alphynia.pakomo.kernel.socks.Socks5Client
import com.alphynia.pakomo.kernel.tcp.TcpConnection
import com.alphynia.pakomo.kernel.tcp.TcpSegment
import com.alphynia.pakomo.kernel.tun.TunReader
import com.alphynia.pakomo.kernel.tun.TunWriter
import com.alphynia.pakomo.kernel.udp.UdpSession
import java.io.FileDescriptor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay; import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class Tun2SocksEngine {
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = AtomicBoolean(false)

    private var config: Tun2SocksConfig? = null
    private var tunFd: FileDescriptor? = null
    private var socksClient: Socks5Client? = null
    private var writer: TunWriter? = null
    private var readJob: Job? = null
    private var reaperJob: Job? = null

    // Connection tables
    private val tcpConnections = ConcurrentHashMap<Long, TcpConnection>()
    private val udpSessions = ConcurrentHashMap<Long, UdpSession>()

    // Stats counters (tunnel-perspective: tx=read from TUN, rx=write to TUN)
    private val txPackets = AtomicLong(0)
    private val txBytes = AtomicLong(0)
    private val rxPackets = AtomicLong(0)
    private val rxBytes = AtomicLong(0)

    // ─── Public API (3-contract: start, stop, stats) ───

    fun start(config: Tun2SocksConfig, tunFd: FileDescriptor) {
        require(!running.get()) { "Engine already running" }
        this.config = config
        this.tunFd = tunFd
        running.set(true)

        // Non-blocking TUN fd: a blocking Os.read cannot be interrupted by coroutine cancellation,
        // so stop() would leave the read thread parked until the next packet. Non-blocking makes the
        // loop poll the running flag (EAGAIN → short delay) and exit promptly on stop.
        if (android.os.Build.VERSION.SDK_INT >= 30) runCatching { val fl = Os.fcntlInt(tunFd, OsConstants.F_GETFL, 0); Os.fcntlInt(tunFd, OsConstants.F_SETFL, fl or OsConstants.O_NONBLOCK) }

        socksClient = Socks5Client(
            socksAddress = config.socksAddress,
            socksPort = config.socksPort,
            username = config.socksUsername,
            password = config.socksPassword,
            connectTimeoutMs = config.connectTimeoutMs,
        )
        writer = TunWriter(tunFd, engineScope).also { it.start() }

        readJob = engineScope.launch(KernelDispatchers.core) {
            val buffer = ByteArray(config.mtu)
            try {
                while (running.get() && isActive) {
                                        val count = try {
                        Os.read(tunFd, buffer, 0, buffer.size)
                    } catch (e: android.system.ErrnoException) {
                        if (e.errno == OsConstants.EAGAIN) { delay(10); continue }
                        else throw e
                    }
                    if (count < 0) break
                    if (count == 0) continue
                    txPackets.incrementAndGet()
                    txBytes.addAndGet(count.toLong())
                    val packet = Ipv4Packet.parse(buffer, 0, count) ?: continue
                    dispatch(packet)
                }
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "TUN read loop failed", e)
            }
        }

        reaperJob = startReaper(config)

        Log.i(TAG, "Engine started: tun=${config.tunAddress}, socks=${config.socksAddress}:${config.socksPort}")
    }

    /**
     * Periodically closes idle/abandoned connections so the session tables never fill up. Without
     * this, TCP connections that go idle in ESTABLISHED or get stuck half-closed (peer vanished,
     * never ACKs our FIN) linger forever until [Tun2SocksConfig.maxSessionCount] is hit and every
     * new SYN is silently dropped — the connection works, then "times out" a short while later.
     */
    private fun startReaper(config: Tun2SocksConfig) = engineScope.launch {
        val establishedIdleMs = config.tcpReadWriteTimeoutMs.toLong()
        while (running.get() && isActive) {
            delay(REAP_INTERVAL_MS)
            val now = android.os.SystemClock.elapsedRealtime()
            var reaped = 0
            for (conn in tcpConnections.values) {
                val idle = now - conn.lastActivityMs
                val stale = when (conn.state) {
                    // Active data states get the generous read/write idle budget.
                    TcpConnection.State.ESTABLISHED, TcpConnection.State.CLOSE_WAIT ->
                        idle > establishedIdleMs
                    // Handshaking / half-closed states must not linger: a vanished peer leaves them
                    // here with no traffic to ever close them.
                    else -> idle > HALF_OPEN_IDLE_MS
                }
                if (stale) { runCatching { conn.closeConnection() }; reaped++ }
            }
            if (reaped > 0) {
                Log.i(TAG, "reaper closed $reaped idle conns (tcp=${tcpConnections.size} udp=${udpSessions.size})")
            }
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        readJob?.cancel()
        reaperJob?.cancel()
        writer?.stop()
        // Close all connections
        tcpConnections.values.forEach { runCatching { it.closeConnection() } }
        udpSessions.values.forEach { runCatching { it.close() } }
        tcpConnections.clear()
        udpSessions.clear()
        engineScope.cancel()
        Log.i(TAG, "Engine stopped")
    }

    fun stats(): LongArray = longArrayOf(
        txPackets.get(),
        txBytes.get(),
        rxPackets.get(),
        rxBytes.get(),
    )

    // ─── Packet dispatch ───

    private suspend fun dispatch(packet: Ipv4Packet) {
        when (packet.protocol) {
            Ipv4Packet.PROTOCOL_TCP -> handleTcp(packet)
            Ipv4Packet.PROTOCOL_UDP -> handleUdp(packet)
            Ipv4Packet.PROTOCOL_ICMP -> handleIcmp(packet)
        }
    }

    private suspend fun handleTcp(packet: Ipv4Packet) {
        val seg = TcpSegment.parse(packet.payload) ?: return
        val key = TunReader.tcpKey(
            packet.sourceAddress, seg.sourcePort,
            packet.destinationAddress, seg.destinationPort,
        )
        val existing = tcpConnections[key]
        if (existing != null) {
            existing.enqueue(seg)
            return
        }
        if (!seg.isSyn || seg.isAck) {
            // Non-SYN (or SYN-ACK) to a connection we have no state for — typically a flow that was
            // already established when the tunnel came up and got captured mid-stream. Reply with a
            // RST (unless it is already a RST, to avoid a reset ping-pong) so the app's stale
            // connection fails fast and re-establishes through the tunnel, instead of silently
            // stalling until its own TCP timeout.
            if (!seg.isRst) sendReset(packet, seg)
            return
        }

        // Limit total connections
        if (tcpConnections.size >= (config?.maxSessionCount ?: 1024)) return

        val mss = resolveMss(packet.payload)
        val w = writer ?: return
        val sc = socksClient ?: return
        val cfg = config ?: return

        val conn = TcpConnection(
            sourceAddress = packet.sourceAddress,
            sourcePort = seg.sourcePort,
            destinationAddress = packet.destinationAddress,
            destinationPort = seg.destinationPort,
            mss = mss,
            onSendPacket = { pkt -> w.send(pkt); rxPackets.incrementAndGet(); rxBytes.addAndGet(pkt.size.toLong()) },
            socksClient = sc,
            connectionScope = engineScope,
            onClosed = { tcpConnections.remove(key) },
            peerWindowShift = resolveWindowShift(packet.payload),
        )
        tcpConnections[key] = conn
        conn.accept(seg.sequenceNumber)
        conn.startActor()
    }

    /**
     * Sends a stateless RST back to the app for a segment on a connection we do not track (RFC 793
     * §3.4): swap the 4-tuple and set seq/ack from the incoming segment. Used to fail mid-stream
     * flows fast when the tunnel captures them, rather than dropping and letting them hang.
     */
    private suspend fun sendReset(packet: Ipv4Packet, seg: TcpSegment) {
        val w = writer ?: return
        val seq: Long
        val ack: Long
        val flags: Int
        if (seg.isAck) {
            // Peer acked something: RST at its ack point, no ACK flag.
            seq = seg.acknowledgmentNumber
            ack = 0L
            flags = TcpSegment.FLAG_RST
        } else {
            // No ack to anchor on: RST|ACK acking the incoming sequence span.
            val segLen = seg.payload.size + (if (seg.isSyn) 1 else 0) + (if (seg.isFin) 1 else 0)
            seq = 0L
            ack = (seg.sequenceNumber + segLen) and 0xFFFF_FFFFL
            flags = TcpSegment.FLAG_RST or TcpSegment.FLAG_ACK
        }
        val rst = TcpSegment.buildIpv4Packet(
            sourcePort = seg.destinationPort,
            destinationPort = seg.sourcePort,
            sequenceNumber = seq,
            acknowledgmentNumber = ack,
            flags = flags,
            windowSize = 0,
            sourceAddress = packet.destinationAddress,
            destinationAddress = packet.sourceAddress,
        )
        w.send(rst)
        rxPackets.incrementAndGet()
        rxBytes.addAndGet(rst.size.toLong())
    }

    private suspend fun handleUdp(packet: Ipv4Packet) {
        if (packet.payload.size < 8) return
        val srcPort = ((packet.payload[0].toInt() and 0xFF) shl 8) or
            (packet.payload[1].toInt() and 0xFF)
        val dstPort = ((packet.payload[2].toInt() and 0xFF) shl 8) or
            (packet.payload[3].toInt() and 0xFF)
        val key = TunReader.udpKey(
            packet.sourceAddress, srcPort,
            packet.destinationAddress, dstPort,
        )
        val existing = udpSessions[key]
        if (existing != null) {
            existing.enqueue(packet.payload.copyOfRange(8, packet.payload.size))
            return
        }
        // Limit UDP sessions
        if (udpSessions.size >= (config?.maxSessionCount ?: 1024)) return

        val w = writer ?: return
        val sc = socksClient ?: return
        val cfg = config ?: return

        val session = UdpSession(
            sourceAddress = packet.sourceAddress,
            sourcePort = srcPort,
            destinationAddress = packet.destinationAddress,
            destinationPort = dstPort,
            onSendPacket = { pkt -> w.send(pkt); rxPackets.incrementAndGet(); rxBytes.addAndGet(pkt.size.toLong()) },
            socksClient = sc,
            sessionScope = engineScope,
            idleTimeoutMs = cfg.udpReadWriteTimeoutMs.toLong(),
            onClosed = { udpSessions.remove(key) },
        )
        udpSessions[key] = session
        session.enqueue(packet.payload.copyOfRange(8, packet.payload.size))
        session.startActor()
    }

    private suspend fun handleIcmp(packet: Ipv4Packet) {
        if (config?.icmpReplyEnabled != true) return
        val reply = IcmpResponder.respond(packet) ?: return
        writer?.send(reply)
        rxPackets.incrementAndGet()
        rxBytes.addAndGet(reply.size.toLong())
    }

    private fun resolveMss(tcpPayload: ByteArray): Int {
        val mtu = config?.mtu ?: 1500
        val dataOffset = ((tcpPayload[12].toInt() and 0xFF) shr 4) * 4
        if (dataOffset <= 20) return mtu - 40
        var offset = 20
        while (offset < dataOffset) {
            val kind = tcpPayload[offset].toInt() and 0xFF
            if (kind == 0) break
            if (kind == 1) { offset++; continue }
            val len = if (offset + 1 < dataOffset) tcpPayload[offset + 1].toInt() and 0xFF else 0
            if (len < 2 || offset + len > dataOffset) break
            if (kind == 2 && len == 4) {
                return ((tcpPayload[offset + 2].toInt() and 0xFF) shl 8) or
                    (tcpPayload[offset + 3].toInt() and 0xFF)
            }
            offset += len
        }
        return mtu - 40
    }

    /** Parses the TCP window-scale option (kind 3) from the app's SYN, or 0 if absent. */
    private fun resolveWindowShift(tcpPayload: ByteArray): Int {
        val dataOffset = ((tcpPayload[12].toInt() and 0xFF) shr 4) * 4
        if (dataOffset <= 20) return 0
        var offset = 20
        while (offset < dataOffset) {
            val kind = tcpPayload[offset].toInt() and 0xFF
            if (kind == 0) break
            if (kind == 1) { offset++; continue }
            val len = if (offset + 1 < dataOffset) tcpPayload[offset + 1].toInt() and 0xFF else 0
            if (len < 2 || offset + len > dataOffset) break
            if (kind == 3 && len == 3) return (tcpPayload[offset + 2].toInt() and 0xFF).coerceAtMost(14)
            offset += len
        }
        return 0
    }

    companion object {
        private const val TAG = "PakomoTun2Socks"
        private const val REAP_INTERVAL_MS = 15_000L
        // Handshaking / half-closed connections with no traffic are reaped well before the full
        // read/write idle budget, since a vanished peer leaves them stuck with nothing to close them.
        private const val HALF_OPEN_IDLE_MS = 30_000L
    }
}
