package com.pakomo.kernel

import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.pakomo.kernel.icmp.IcmpResponder
import com.pakomo.kernel.ip.Ipv4Packet
import com.pakomo.kernel.socks.Socks5Client
import com.pakomo.kernel.tcp.TcpConnection
import com.pakomo.kernel.tcp.TcpSegment
import com.pakomo.kernel.tun.TunReader
import com.pakomo.kernel.tun.TunWriter
import com.pakomo.kernel.udp.UdpSession
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
        runCatching {
            val fl = Os.fcntlInt(tunFd, OsConstants.F_GETFL, 0)
            Os.fcntlInt(tunFd, OsConstants.F_SETFL, fl or OsConstants.O_NONBLOCK)
        }

        socksClient = Socks5Client(
            socksAddress = config.socksAddress,
            socksPort = config.socksPort,
            username = config.socksUsername,
            password = config.socksPassword,
            connectTimeoutMs = config.connectTimeoutMs,
        )
        writer = TunWriter(tunFd, engineScope).also { it.start() }

        readJob = engineScope.launch(Dispatchers.IO) {
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

        Log.i(TAG, "Engine started: tun=${config.tunAddress}, socks=${config.socksAddress}:${config.socksPort}")
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        readJob?.cancel()
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
        if (!seg.isSyn || seg.isAck) return // non-SYN to unknown connection: drop

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
        )
        tcpConnections[key] = conn
        conn.accept(seg.sequenceNumber)
        conn.startActor()
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

    companion object {
        private const val TAG = "PakomoTun2Socks"
    }
}
