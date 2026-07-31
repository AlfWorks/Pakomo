package com.pakomo.kernel.tcp

import android.util.Log
import com.pakomo.kernel.ip.Ipv4Packet
import com.pakomo.kernel.socks.Socks5Client
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

class TcpConnection(
    val sourceAddress: Int,
    val sourcePort: Int,
    val destinationAddress: Int,
    val destinationPort: Int,
    private val mss: Int,
    private val onSendPacket: suspend (ByteArray) -> Unit,
    private val socksClient: Socks5Client,
    private val connectionScope: CoroutineScope,
    private val onClosed: () -> Unit,
) {
    enum class State { CLOSED, SYN_RCVD, ESTABLISHED, CLOSE_WAIT, LAST_ACK, FIN_WAIT_1, FIN_WAIT_2, TIME_WAIT }

    @Volatile var state = State.CLOSED
        private set

    private var iss = 0L
    private var sndNxt = 0L
    private var sndUna = 0L
    private var rcvNxt = 0L
    private var appWindow = 65535
    private var ourWindow = 65535
    private val sendQueue = ArrayDeque<QueuedSegment>()
    private var rtoMs = 1000L
    private var rtoJob: Job? = null
    val inputChannel = Channel<TcpSegment>(Channel.UNLIMITED)
    private var socksTcp: Socks5Client.TcpConnection? = null
    private val earlyData = ArrayDeque<ByteArray>()
    private var socksReadJob: Job? = null
    private var ourFinSent = false
    private var theirFinReceived = false

    suspend fun accept(synSeq: Long) {
        val rng = SecureRandom()
        iss = (rng.nextInt().toLong() and 0xFFFF_FFFFL)
        sndNxt = iss
        sndUna = iss
        rcvNxt = (synSeq + 1) and 0xFFFF_FFFFL
        state = State.SYN_RCVD
        launchSocksRelay()
        Log.i(TAG, "SYN $sourcePort->$destinationPort, connecting SOCKS5")
        sendSegment(0x12, ByteArray(0))
    }

    fun enqueue(segment: TcpSegment) { inputChannel.trySend(segment) }

    fun startActor() {
        connectionScope.launch {
            try { runActor() }
            catch (_: CancellationException) {}
            catch (e: Exception) { Log.w(TAG, "TCP actor failed $sourcePort->$destinationPort", e) }
            finally { cleanup() }
        }
    }

    fun closeConnection() {
        if (state == State.CLOSED) return
        state = State.CLOSED
        onClosed()
    }

    private suspend fun runActor() {
        while (state != State.CLOSED) {
            var processed = false
            while (true) {
                val seg = inputChannel.tryReceive().getOrNull() ?: break
                processTcpSegment(seg)
                processed = true
            }
            delay(if (processed) 1 else POLL_INTERVAL_MS)
        }
    }

    private suspend fun processTcpSegment(seg: TcpSegment) {
        when (state) {
            State.SYN_RCVD -> handleSynRcvd(seg)
            State.ESTABLISHED -> handleEstablished(seg)
            State.CLOSE_WAIT -> handleCloseWait(seg)
            State.LAST_ACK -> handleLastAck(seg)
            State.FIN_WAIT_1 -> handleFinWait1(seg)
            State.FIN_WAIT_2 -> handleFinWait2(seg)
            State.TIME_WAIT -> {}
            else -> {}
        }
    }

    private suspend fun handleSynRcvd(seg: TcpSegment) {
        if (!seg.isAck || seg.acknowledgmentNumber != ((iss + 1) and 0xFFFF_FFFFL)) {
            if (seg.isRst) closeConnection()
            return
        }
        if (seg.isRst) { closeConnection(); return }
        sndNxt = ((iss + 1) and 0xFFFF_FFFFL)
        sndUna = sndNxt
        rcvNxt = (seg.sequenceNumber + seg.payload.size + (if (seg.isSyn) 1 else 0) +
            (if (seg.isFin) 1 else 0)) and 0xFFFF_FFFFL
        state = State.ESTABLISHED
        Log.i(TAG, "ESTABLISHED $sourcePort->$destinationPort")
    }

    private suspend fun handleEstablished(seg: TcpSegment) {
        if (seg.isRst) { closeConnection(); return }
        appWindow = seg.windowSize
        if (seg.isAck) { processAck(seg.acknowledgmentNumber) }
        val segLen = seg.payload.size + (if (seg.isSyn) 1 else 0) + (if (seg.isFin) 1 else 0)
        if (segLen > 0) {
            val expected = rcvNxt
            if (seg.sequenceNumber == expected) {
                if (seg.payload.isNotEmpty()) { writeToSocks(seg.payload) }
                rcvNxt = (seg.sequenceNumber + segLen) and 0xFFFF_FFFFL
                sendSegment(TcpSegment.FLAG_ACK, ByteArray(0))
            } else if (isAfter(seg.sequenceNumber, expected)) {
                sendSegment(TcpSegment.FLAG_ACK, ByteArray(0))
            }
        }
        if (seg.isFin) {
            theirFinReceived = true
            rcvNxt = (rcvNxt + 1) and 0xFFFF_FFFFL
            if (ourFinSent) enterTimeWait() else { state = State.CLOSE_WAIT; sendFin() }
        }
    }

    private suspend fun handleCloseWait(seg: TcpSegment) {
        if (seg.isRst) { closeConnection(); return }
        processAck(seg.acknowledgmentNumber)
    }

    private suspend fun handleLastAck(seg: TcpSegment) {
        if (!seg.isAck) return
        if (seg.acknowledgmentNumber == (sndNxt + 1) and 0xFFFF_FFFFL ||
            seg.acknowledgmentNumber == sndNxt) closeConnection()
    }

    private suspend fun handleFinWait1(seg: TcpSegment) {
        if (seg.isRst) { closeConnection(); return }
        processAck(seg.acknowledgmentNumber)
        if (seg.isFin) {
            rcvNxt = (seg.sequenceNumber + 1) and 0xFFFF_FFFFL
            sendSegment(TcpSegment.FLAG_ACK, ByteArray(0)); enterTimeWait()
        }
        if (seg.isAck && seg.acknowledgmentNumber == (sndNxt + 1) and 0xFFFF_FFFFL) state = State.FIN_WAIT_2
    }

    private suspend fun handleFinWait2(seg: TcpSegment) {
        if (seg.isRst) { closeConnection(); return }
        processAck(seg.acknowledgmentNumber)
        if (seg.isFin) {
            rcvNxt = (seg.sequenceNumber + 1) and 0xFFFF_FFFFL
            sendSegment(TcpSegment.FLAG_ACK, ByteArray(0)); enterTimeWait()
        }
    }

    private fun launchSocksRelay() {
        connectionScope.launch {
            val tcp = withContext(Dispatchers.IO) {
                socksClient.tcpConnect(sourceAddress, sourcePort, destinationAddress, destinationPort)
            }
            if (tcp == null) { Log.w(TAG, "SOCKS5 connect failed $sourcePort"); resetConnection(); return@launch }
            Log.i(TAG, "SOCKS5 connected $sourcePort->$destinationPort")
            socksTcp = tcp
            while (earlyData.isNotEmpty()) {
                try {
                    withContext(Dispatchers.IO) {
                        tcp.output.write(earlyData.removeFirst()); tcp.output.flush()
                    }
                } catch (_: Exception) { resetConnection(); return@launch }
            }
            socksReadJob = launch {
                try {
                    val buf = ByteArray(SOCKS_READ_BUF)
                    while (isActive && (state == State.ESTABLISHED || state == State.CLOSE_WAIT)) {
                        val count = withContext(Dispatchers.IO) { tcp.input.read(buf) }
                        if (count < 0) { if (!ourFinSent) sendFin(); return@launch }
                        if (count > 0) sendDataToApp(buf.copyOf(count))
                    }
                } catch (_: Exception) { if (!ourFinSent) sendFin() }
            }
        }
    }

    private suspend fun sendDataToApp(data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val chunkSize = min(data.size - offset, min(mss, appWindow)).coerceAtLeast(1)
            if (appWindow < chunkSize) { delay(RETRY_DELAY_MS); continue }
            val chunk = data.copyOfRange(offset, offset + chunkSize)
            offset += chunkSize
            sendSegment(TcpSegment.FLAG_ACK or TcpSegment.FLAG_PSH, chunk)
        }
    }

    private suspend fun writeToSocks(data: ByteArray) {
        val tcp = socksTcp
        if (tcp == null) {
            if (earlyData.size < 64) earlyData.addLast(data)
            return
        }
        try { withContext(Dispatchers.IO) { tcp.output.write(data); tcp.output.flush() } }
        catch (_: Exception) { resetConnection() }
    }

    private suspend fun sendSegment(flags: Int, payload: ByteArray) {
        val segBytes = TcpSegment.build(
            sourcePort = destinationPort, destinationPort = sourcePort,
            sequenceNumber = sndNxt, acknowledgmentNumber = rcvNxt,
            flags = flags, windowSize = ourWindow,
            sourceAddress = destinationAddress, destinationAddress = sourceAddress,
            payload = payload,
        )
        val header = Ipv4Packet.buildHeader(
            protocol = Ipv4Packet.PROTOCOL_TCP,
            sourceAddress = destinationAddress, destinationAddress = sourceAddress,
            payloadLength = segBytes.size,
        )
        onSendPacket(header + segBytes)
        val len = payload.size + (if ((flags and TcpSegment.FLAG_SYN) != 0) 1 else 0) +
            (if ((flags and TcpSegment.FLAG_FIN) != 0) 1 else 0)
        if (len > 0) {
            sendQueue.addLast(QueuedSegment(sndNxt, len))
            sndNxt = (sndNxt + len) and 0xFFFF_FFFFL
            startRtoTimer()
        }
    }

    private fun processAck(ackNum: Long) {
        while (sendQueue.isNotEmpty()) {
            val seg = sendQueue.first()
            val segEnd = (seg.seq + seg.length) and 0xFFFF_FFFFL
            if (isAfterOrEqual(ackNum, segEnd)) { sendQueue.removeFirst(); sndUna = segEnd }
            else break
        }
        if (sendQueue.isEmpty()) cancelRto() else { rtoMs = 1000L; startRtoTimer() }
    }

    private suspend fun sendFin() {
        if (ourFinSent) return
        ourFinSent = true
        when (state) {
            State.ESTABLISHED -> {
                sendSegment(TcpSegment.FLAG_FIN or TcpSegment.FLAG_ACK, ByteArray(0))
                state = State.FIN_WAIT_1; closeSocks()
            }
            State.CLOSE_WAIT -> {
                sendSegment(TcpSegment.FLAG_FIN or TcpSegment.FLAG_ACK, ByteArray(0))
                state = State.LAST_ACK; closeSocks()
            }
            else -> {}
        }
    }

    private suspend fun resetConnection() {
        sendSegment(TcpSegment.FLAG_RST or TcpSegment.FLAG_ACK, ByteArray(0))
        closeConnection()
    }

    private fun enterTimeWait() {
        state = State.TIME_WAIT; closeSocks()
        connectionScope.launch { delay(2000L); closeConnection() }
    }

    private fun closeSocks() {
        socksReadJob?.cancel(); socksReadJob = null
        runCatching { socksTcp?.sock?.close() }; socksTcp = null
    }

    private fun startRtoTimer() { cancelRto(); rtoJob = connectionScope.launch { delay(rtoMs); retransmit() } }
    private fun cancelRto() { rtoJob?.cancel(); rtoJob = null }

    private suspend fun retransmit() {
        if (sendQueue.isEmpty()) return
        sendSegment(TcpSegment.FLAG_ACK, ByteArray(0))
        rtoMs = (rtoMs * 2).coerceAtMost(MAX_RTO_MS)
        startRtoTimer()
    }

    private fun cleanup() {
        cancelRto(); closeSocks(); inputChannel.close(); sendQueue.clear(); earlyData.clear()
    }

    private fun isAfter(s1: Long, s2: Long): Boolean = ((s1 - s2) and 0xFFFF_FFFFL) < 0x8000_0000L && s1 != s2
    private fun isAfterOrEqual(s1: Long, s2: Long): Boolean = s1 == s2 || isAfter(s1, s2)

    private data class QueuedSegment(val seq: Long, val length: Int)

    companion object {
        private const val TAG = "PakomoTcp"
        private const val POLL_INTERVAL_MS = 10L
        private const val RETRY_DELAY_MS = 50L
        private const val MAX_RTO_MS = 60000L
        private const val SOCKS_READ_BUF = 16384
    }
}
