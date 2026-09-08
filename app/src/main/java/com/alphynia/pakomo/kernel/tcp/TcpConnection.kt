package com.alphynia.pakomo.kernel.tcp

import android.os.SystemClock
import android.util.Log
import com.alphynia.pakomo.BuildConfig
import com.alphynia.pakomo.kernel.socks.Socks5Client
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import com.alphynia.pakomo.kernel.KernelDispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val peerWindowShift: Int = 0,
) {
    enum class State { CLOSED, SYN_RCVD, ESTABLISHED, CLOSE_WAIT, LAST_ACK, FIN_WAIT_1, FIN_WAIT_2, TIME_WAIT }

    @Volatile var state = State.CLOSED
        private set

    private var iss = 0L
    @Volatile private var sndNxt = 0L
    @Volatile private var sndUna = 0L
    private var rcvNxt = 0L
    @Volatile private var appWindow = 65535
    // Advertise a large scaled receive window so a fast download isn't throttled to 64 KB / RTT.
    private val ourWindowShift = 7
    private val ourWindow = 512 * 1024
    private val sendQueue = ArrayDeque<QueuedSegment>()
    private var rtoMs = 1000L; private var rtoJob: Job? = null; private var retransmitCount = 0
    // Serializes all mutation of the send state (sndNxt / sendQueue / rtoJob). Without it, downstream
    // sends (on the SOCKS-read coroutine) and ACK processing / RTO (on the actor + timer coroutines)
    // race: sendQueue corrupts and multiple RTO timers fire, producing a retransmit storm that resets
    // the connection under load. The flow-control WAIT stays outside this lock so ACKs can still open
    // the window.
    private val sendLock = Mutex()
    val inputChannel = Channel<TcpSegment>(Channel.UNLIMITED)
    private var socksTcp: Socks5Client.TcpConnection? = null
    // When this connection's SYN arrived; handed to the SOCKS server to size latency compensation.
    private var synArrivalNanos = 0L
    private val earlyData = ArrayDeque<ByteArray>()
    private var earlyBytes = 0
    private var socksReadJob: Job? = null
    private var ourFinSent = false
    private var theirFinReceived = false

    /** Last time this connection saw any inbound or outbound activity; drives idle reaping. */
    @Volatile var lastActivityMs = SystemClock.elapsedRealtime()
        private set

    suspend fun accept(synSeq: Long) {
        synArrivalNanos = System.nanoTime()
        val rng = SecureRandom()
        iss = (rng.nextInt().toLong() and 0xFFFF_FFFFL)
        sndNxt = iss
        sndUna = iss
        rcvNxt = (synSeq + 1) and 0xFFFF_FFFFL
        state = State.SYN_RCVD
        launchSocksRelay()
        if (BuildConfig.DEBUG) Log.i(TAG, "SYN $sourcePort->$destinationPort, connecting SOCKS5")
        sendSegment(0x12, ByteArray(0), synAckOptions())
    }

    fun enqueue(segment: TcpSegment) { inputChannel.trySend(segment) }

    fun startActor() {
        connectionScope.launch {
            try { runActor() }
            catch (_: CancellationException) {}
            // Once CLOSED (teardown), a stray actor exception is expected noise — don't log a trace.
            catch (e: Exception) { if (state != State.CLOSED) Log.w(TAG, "TCP actor failed $sourcePort->$destinationPort", e) }
            finally { cleanup() }
        }
    }

    fun closeConnection() {
        if (state == State.CLOSED) return
        state = State.CLOSED
        onClosed()
    }

    private suspend fun runActor() {
        try {
            for (seg in inputChannel) {
                processTcpSegment(seg)
                if (state == State.CLOSED) break
            }
        } catch (_: ClosedReceiveChannelException) {
        }
    }

    private suspend fun processTcpSegment(seg: TcpSegment) {
        lastActivityMs = SystemClock.elapsedRealtime()
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
        // rcvNxt was set to synSeq+1 in accept(). Do NOT pre-advance it past this ACK's payload:
        // ingestSegment only delivers a segment whose seq == rcvNxt, so pre-advancing made the data a
        // piggybacked third-ACK carries get skipped while still being acknowledged (audit #5).
        state = State.ESTABLISHED
        if (seg.payload.isNotEmpty() || seg.isFin) ingestSegment(seg)
        if (BuildConfig.DEBUG) Log.i(TAG, "ESTABLISHED $sourcePort->$destinationPort")
    }

    private suspend fun ingestSegment(seg: TcpSegment) {
        val expected = rcvNxt
        if (seg.sequenceNumber != expected) {
            // Out of order (or already seen): re-ACK our current position so the peer retransmits the
            // gap. Do NOT consume payload or act on a FIN that sits beyond a hole — an out-of-order FIN
            // must not close the connection ahead of data still in flight (audit #3).
            if (isAfter(seg.sequenceNumber, expected)) sendSegment(TcpSegment.FLAG_ACK, ByteArray(0))
            return
        }
        // In order. Deliver payload upstream; only advance/ACK data the upstream accepted (audit #2).
        if (seg.payload.isNotEmpty()) {
            if (!writeToSocks(seg.payload)) return
            rcvNxt = (rcvNxt + seg.payload.size) and 0xFFFF_FFFFL
        }
        if (seg.isFin) {
            theirFinReceived = true
            rcvNxt = (rcvNxt + 1) and 0xFFFF_FFFFL // a FIN consumes exactly one sequence number
            sendSegment(TcpSegment.FLAG_ACK, ByteArray(0))
            // The app closed its send direction. Half-close the upstream (send EOF) but keep reading
            // the server's response; the downstream loop stays alive in CLOSE_WAIT and turns the
            // server's EOF into our FIN. Previously we FIN'd and closed the whole SOCKS connection at
            // once, cutting off responses to a request that half-closes (audit #3).
            if (ourFinSent) enterTimeWait() else { state = State.CLOSE_WAIT; halfCloseUpstream() }
        } else {
            sendSegment(TcpSegment.FLAG_ACK, ByteArray(0))
        }
    }

    private suspend fun handleEstablished(seg: TcpSegment) {
        if (seg.isRst) { closeConnection(); return }
        appWindow = seg.windowSize shl peerWindowShift
        if (seg.isAck) { processAck(seg.acknowledgmentNumber) }
        val segLen = seg.payload.size + (if (seg.isSyn) 1 else 0) + (if (seg.isFin) 1 else 0)
        if (segLen > 0) ingestSegment(seg)
    }

    private suspend fun handleCloseWait(seg: TcpSegment) {
        if (seg.isRst) { closeConnection(); return }
        processAck(seg.acknowledgmentNumber)
    }

    private suspend fun handleLastAck(seg: TcpSegment) {
        if (!seg.isAck) return
        // Our FIN already advanced sndNxt by one, so it is acknowledged when ackNum == sndNxt (the old
        // `sndNxt + 1` double-counted the FIN's sequence — audit #3).
        if (seg.acknowledgmentNumber == sndNxt) closeConnection()
    }

    private suspend fun handleFinWait1(seg: TcpSegment) {
        if (seg.isRst) { closeConnection(); return }
        processAck(seg.acknowledgmentNumber)
        if (seg.isFin) {
            rcvNxt = (seg.sequenceNumber + 1) and 0xFFFF_FFFFL
            sendSegment(TcpSegment.FLAG_ACK, ByteArray(0)); enterTimeWait()
        }
        // Our FIN advanced sndNxt by one; it is acked when ackNum == sndNxt (was `sndNxt + 1` — audit #3).
        if (seg.isAck && seg.acknowledgmentNumber == sndNxt) state = State.FIN_WAIT_2
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
            val tcp = withContext(KernelDispatchers.connIo) {
                socksClient.tcpConnect(
                    sourceAddress, sourcePort, destinationAddress, destinationPort, synArrivalNanos,
                )
            }
            if (tcp == null) { Log.w(TAG, "SOCKS5 connect failed $sourcePort"); resetConnection(); return@launch }
            if (BuildConfig.DEBUG) Log.i(TAG, "SOCKS5 connected $sourcePort->$destinationPort")
            socksTcp = tcp
            while (earlyData.isNotEmpty()) {
                try {
                    withContext(KernelDispatchers.connIo) {
                        tcp.output.write(earlyData.removeFirst()); tcp.output.flush()
                    }
                } catch (_: Exception) { resetConnection(); return@launch }
            }
            earlyBytes = 0
            // Pipeline upstream reads and app sends: a reader coroutine pulls from the SOCKS socket
            // into a bounded channel while a sender coroutine drains it to the app. Serializing the
            // two (read 16 KB, then send it, then read again) added both latencies per batch and
            // capped a single connection; overlapping them lets throughput track the faster side.
            val downstream = Channel<ByteArray>(DOWNSTREAM_CAP)
            launch {
                try { for (data in downstream) sendDataToApp(data) } catch (_: Exception) {}
            }
            socksReadJob = launch {
                try {
                    // Don't pump downstream until the handshake finishes: reading while still SYN_RCVD
                    // would find the loop condition false and exit at once (audit #5), emitting a bogus
                    // FIN. Waiting exits if the connection is torn down (state leaves SYN_RCVD).
                    while (state == State.SYN_RCVD) delay(FLOW_POLL_MS)
                    if (state == State.ESTABLISHED || state == State.CLOSE_WAIT) {
                        withContext(KernelDispatchers.connIo) {
                            val buf = ByteArray(SOCKS_READ_BUF)
                            while (isActive && (state == State.ESTABLISHED || state == State.CLOSE_WAIT)) {
                                val count = tcp.input.read(buf)
                                if (count < 0) break
                                if (count > 0) downstream.send(buf.copyOf(count))
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    downstream.close()
                    // Translate a real upstream EOF into a FIN only from a sending state; in SYN_RCVD or
                    // after teardown there is nothing to close and sendFin would just latch ourFinSent.
                    if (!ourFinSent && (state == State.ESTABLISHED || state == State.CLOSE_WAIT)) sendFin()
                }
            }
        }
    }

    private suspend fun sendDataToApp(data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            // Flow control: never keep more than the receiver's advertised window in flight. Without
            // this, a fast download overruns the app's 64 KB receive window — the app drops the
            // excess and shrinks its window to 0, we keep blasting, the same segment is retransmitted
            // to MAX_RETRANSMIT and the connection is reset (~"下几 MB 就断"). Waiting here for ACKs
            // backpressures the upstream read, so we send exactly at the window's pace.
            var usable = appWindow - inFlight()
            while (usable < 1) {
                if (state != State.ESTABLISHED && state != State.CLOSE_WAIT) return
                delay(FLOW_POLL_MS)
                usable = appWindow - inFlight()
            }
            // Send as many segments as the window allows under ONE lock acquisition. Locking per
            // segment made the send path and the ACK path ping-pong on the mutex (a context switch
            // each), which alone capped a single connection to ~7 MB/s. The window wait above stays
            // outside the lock so inbound ACKs can still advance the window.
            sendLock.withLock {
                usable = appWindow - inFlight()
                while (offset < data.size && usable >= 1) {
                    val chunkSize = min(min(data.size - offset, mss), usable)
                    sendSegmentLocked(
                        TcpSegment.FLAG_ACK or TcpSegment.FLAG_PSH,
                        data.copyOfRange(offset, offset + chunkSize),
                    )
                    offset += chunkSize
                    usable = appWindow - inFlight()
                }
            }
        }
    }

    /** Unacknowledged bytes currently in flight toward the app. */
    private fun inFlight(): Int = ((sndNxt - sndUna) and 0xFFFF_FFFFL).toInt()

    /** Window field for an outbound segment: unscaled in the SYN-ACK, scaled by our shift after. */
    private fun windowField(isSyn: Boolean): Int =
        (if (isSyn) ourWindow else ourWindow shr ourWindowShift).coerceAtMost(65535)

    /** SYN-ACK options: MSS + Window Scale (padded to a 4-byte boundary with a NOP). */
    private fun synAckOptions(): ByteArray = byteArrayOf(
        2, 4, (mss ushr 8).toByte(), mss.toByte(), // MSS
        1,                                          // NOP (align to 4 bytes)
        3, 3, ourWindowShift.toByte(),              // Window Scale
    )

    /**
     * Hand inbound data to the SOCKS upstream. Returns whether the data was accepted — the caller must
     * only advance rcvNxt / ACK when it was, so we never acknowledge bytes we could not hold. Before
     * SOCKS is connected we buffer up to our advertised receive window ([ourWindow]); beyond that we
     * refuse, applying backpressure (the peer retransmits, by which point SOCKS is normally up). The
     * old code dropped the 65th+ pending segment yet still ACKed it, losing already-acknowledged data
     * (audit #2).
     */
    private suspend fun writeToSocks(data: ByteArray): Boolean {
        val tcp = socksTcp
        if (tcp == null) {
            if (earlyBytes + data.size > ourWindow) return false
            earlyData.addLast(data); earlyBytes += data.size
            return true
        }
        return try {
            withContext(KernelDispatchers.connIo) { tcp.output.write(data); tcp.output.flush() }
            true
        } catch (_: Exception) { resetConnection(); false }
    }

    private suspend fun sendSegment(flags: Int, payload: ByteArray, options: ByteArray = ByteArray(0)) =
        sendLock.withLock { sendSegmentLocked(flags, payload, options) }

    /** Builds and emits one segment. Caller must hold [sendLock]. */
    private suspend fun sendSegmentLocked(flags: Int, payload: ByteArray, options: ByteArray = ByteArray(0)) {
        lastActivityMs = SystemClock.elapsedRealtime()
        val packet = TcpSegment.buildIpv4Packet(
            sourcePort = destinationPort, destinationPort = sourcePort,
            sequenceNumber = sndNxt, acknowledgmentNumber = rcvNxt,
            flags = flags, windowSize = windowField(flags and TcpSegment.FLAG_SYN != 0),
            sourceAddress = destinationAddress, destinationAddress = sourceAddress,
            payload = payload, options = options,
        )
        onSendPacket(packet)
        val len = payload.size + (if ((flags and TcpSegment.FLAG_SYN) != 0) 1 else 0) +
            (if ((flags and TcpSegment.FLAG_FIN) != 0) 1 else 0)
        if (len > 0) {
            sendQueue.addLast(QueuedSegment(sndNxt, len, flags, payload))
            sndNxt = (sndNxt + len) and 0xFFFF_FFFFL
            // One RTO timer tracks the oldest unacked segment; don't cancel+relaunch a coroutine per
            // segment (that alone caps a fast single connection). processAck rearms it on each ACK.
            if (rtoJob == null) startRtoTimer()
        }
    }

    private suspend fun processAck(ackNum: Long) = sendLock.withLock {
        var advanced = false
        while (sendQueue.isNotEmpty()) {
            val seg = sendQueue.first()
            val segEnd = (seg.seq + seg.length) and 0xFFFF_FFFFL
            if (isAfterOrEqual(ackNum, segEnd)) { sendQueue.removeFirst(); sndUna = segEnd; advanced = true }
            else break
        }
        // Rearm the RTO only when new data was actually acked (the oldest unacked segment changed);
        // duplicate ACKs during a fast download must not relaunch the timer coroutine each time.
        if (sendQueue.isEmpty()) cancelRto()
        else if (advanced) { retransmitCount = 0; rtoMs = 1000L; startRtoTimer() }
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

    /**
     * Half-close the upstream after the app's FIN: shut down only the SOCKS socket's output so the
     * server sees our EOF, while the input stays open to read the response. The downstream loop keeps
     * running in CLOSE_WAIT and emits our FIN when the server closes (audit #3).
     */
    private fun halfCloseUpstream() {
        runCatching { socksTcp?.sock?.shutdownOutput() }
    }

    private fun startRtoTimer() { cancelRto(); rtoJob = connectionScope.launch { delay(rtoMs); retransmit() } }
    private fun cancelRto() { rtoJob?.cancel(); rtoJob = null }

    private suspend fun retransmit() {
        val exceededLimit = sendLock.withLock {
            val q = sendQueue.firstOrNull() ?: return
            retransmitCount++
            if (retransmitCount > MAX_RETRANSMIT) {
                true
            } else {
                val packet = TcpSegment.buildIpv4Packet(
                    sourcePort = destinationPort, destinationPort = sourcePort,
                    sequenceNumber = q.seq, acknowledgmentNumber = rcvNxt,
                    flags = q.flags, windowSize = windowField(q.flags and TcpSegment.FLAG_SYN != 0),
                    sourceAddress = destinationAddress, destinationAddress = sourceAddress,
                    payload = q.payload,
                )
                if (BuildConfig.DEBUG) Log.d(TAG, "RETRANSMIT seq=${q.seq} flags=${q.flags} count=$retransmitCount")
                onSendPacket(packet)
                rtoMs = (rtoMs * 2).coerceAtMost(MAX_RTO_MS)
                startRtoTimer()
                false
            }
        }
        // Reset outside the lock: resetConnection → sendSegment re-acquires it (Mutex is not reentrant).
        if (exceededLimit) resetConnection()
    }

    private fun cleanup() {
        cancelRto(); closeSocks(); inputChannel.close(); sendQueue.clear(); earlyData.clear(); earlyBytes = 0
    }

    private fun isAfter(s1: Long, s2: Long): Boolean = ((s1 - s2) and 0xFFFF_FFFFL) < 0x8000_0000L && s1 != s2
    private fun isAfterOrEqual(s1: Long, s2: Long): Boolean = s1 == s2 || isAfter(s1, s2)

    private class QueuedSegment(val seq: Long, val length: Int, val flags: Int, val payload: ByteArray)

    companion object {
        private const val TAG = "PakomoTcp"
        private const val RETRY_DELAY_MS = 50L
        private const val FLOW_POLL_MS = 1L
        private const val MAX_RTO_MS = 60000L; private const val MAX_RETRANSMIT = 5
        private const val SOCKS_READ_BUF = 16384
        private const val DOWNSTREAM_CAP = 16
    }
}
