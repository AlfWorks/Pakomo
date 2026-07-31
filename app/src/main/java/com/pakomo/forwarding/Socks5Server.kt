package com.pakomo.forwarding

import android.util.Log
import com.pakomo.BuildConfig
import com.pakomo.core.model.BlackoutMode
import com.pakomo.core.model.DnsFailureResult
import com.pakomo.security.SecurityPolicy
import com.pakomo.shaping.TrafficDirection
import com.pakomo.shaping.TrafficShaper
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SocksCredentials(
    val username: String,
    val password: String,
)

class Socks5Server(
    private val credentials: SocksCredentials,
    private val protector: SocketProtector,
    shaper: TrafficShaper,
    shapingPolicy: ShapingPolicy = ShapingPolicy { ShapeEverythingShaping("全局", null) },
    faultPolicy: FaultPolicy = FaultPolicy.NONE,
    private val expectOriginPreamble: Boolean = false,
) : AutoCloseable {
    // Swappable at runtime so a rule/domain edit takes effect on new connections without tearing
    // down the tunnel. New connections resolve against the current policy; existing flows keep the
    // shaping they were already assigned. Reads pick up the latest via volatile.
    @Volatile private var shaper: TrafficShaper = shaper
    @Volatile private var shapingPolicy: ShapingPolicy = shapingPolicy
    @Volatile private var faultPolicy: FaultPolicy = faultPolicy

    fun reconfigure(shaper: TrafficShaper, shapingPolicy: ShapingPolicy, faultPolicy: FaultPolicy) {
        this.shaper = shaper
        this.shapingPolicy = shapingPolicy
        this.faultPolicy = faultPolicy
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeSessions = AtomicInteger(0)
    private var serverChannel: ServerSocketChannel? = null
    private var acceptJob: Job? = null
    private val relayLoop = NioSelectorLoop()

    val port: Int
        get() = serverChannel?.socket()?.localPort ?: 0

    fun start(): Int {
        check(serverChannel == null) { "SOCKS server is already running" }
        val listener = ServerSocketChannel.open()
        listener.socket().bind(
            InetSocketAddress(
                InetAddress.getByName(SecurityPolicy.SOCKS_LOOPBACK_ADDRESS),
                0,
            ),
            SecurityPolicy.SOCKS_LISTEN_BACKLOG,
        )
        serverChannel = listener
        acceptJob = scope.launch {
            while (listener.isOpen) {
                val client = try {
                    listener.accept().socket()
                } catch (_: IOException) {
                    break
                }
                val active = activeSessions.incrementAndGet()
                if (active > SecurityPolicy.MAX_ACTIVE_FLOWS) {
                    activeSessions.decrementAndGet()
                    safeLog("SOCKS session rejected: active-flow limit reached")
                    client.close()
                    continue
                }
                launch {
                    try {
                        handleClient(client)
                    } catch (error: Exception) {
                        safeLog("SOCKS session failed", error)
                    } finally {
                        activeSessions.decrementAndGet()
                        runCatching { client.close() }
                    }
                }
            }
        }
        return listener.socket().localPort
    }

    fun activeSessionCount(): Int = activeSessions.get()

    override fun close() {
        runCatching { serverChannel?.close() }
        serverChannel = null
        acceptJob?.cancel()
        acceptJob = null
        runCatching { relayLoop.close() }
        scope.cancel()
    }

    private suspend fun handleClient(client: Socket) {
        client.soTimeout = SecurityPolicy.SOCKS_HANDSHAKE_TIMEOUT_MS
        val input = client.getInputStream()
        val output = client.getOutputStream()
        val origin = if (expectOriginPreamble) {
            val preamble = runCatching { input.readExact(ConnectionOrigin.PREAMBLE_SIZE) }.getOrNull()
            if (preamble == null) {
                Log.w(TAG, "SOCKS origin preamble missing")
                return
            }
            val parsed = ConnectionOrigin.parse(preamble)
            if (parsed == null) {
                // A required preamble that fails to parse must not fall back to shaping-all;
                // reject the session rather than misattributing the connection.
                Log.w(TAG, "SOCKS origin preamble malformed; rejecting session")
                return
            }
            parsed
        } else {
            null
        }
        val shaping = shapingPolicy.resolve(origin)
        val fault = faultPolicy.resolve(origin)
        if (!negotiateAuthentication(input, output)) {
            Log.w(TAG, "SOCKS method negotiation failed")
            return
        }
        if (!authenticate(input, output)) {
            Log.w(TAG, "SOCKS authentication failed")
            return
        }
        val request = readRequest(input)
        if (request == null) {
            Log.w(TAG, "SOCKS request parsing failed")
            return
        }
        when (request.command) {
            COMMAND_CONNECT -> handleConnect(client, output, request, shaping, fault)
            COMMAND_UDP_ASSOCIATE -> handleUdpAssociate(client, output, shaping, fault)
            else -> writeReply(output, REPLY_COMMAND_NOT_SUPPORTED, null)
        }
    }

    private suspend fun handleConnect(
        client: Socket,
        output: OutputStream,
        request: SocksRequest,
        shaping: ConnectionShaping,
        fault: ConnectionFault,
    ) {
        val clientChannel = client.channel ?: run {
            writeReply(output, REPLY_GENERAL_FAILURE, null)
            return
        }

        // Always try the request host before connecting. For domain-scoped faults this can match a
        // destination IP learned from plaintext DNS, allowing an immediate refused response instead
        // of connecting first and degrading to a reset. A miss still falls back to SNI/Host sniffing.
        val preConnectFault = fault.decideTcp(request.host, request.port)
        if (preConnectFault !is TcpFault.None) {
            applyTcpFaultBeforeConnect(client, clientChannel, output, preConnectFault)
            return
        }

        // When the decision doesn't depend on the host, decide up front (one hit report) and refuse
        // fast on a full block. Domain-filtered flows decide after sniffing the real host.
        val nonDomainShape =
            if (shaping.usesDomainFilter) null else shaping.shouldShape(request.host, request.port)
        if (nonDomainShape == true && shaper.blocksAllTraffic()) {
            writeReply(output, REPLY_NETWORK_UNREACHABLE, null)
            return
        }

        val outbound = connectOutbound(output, request) ?: return
        try {
            outbound.socket().tcpNoDelay = true
            writeReply(output, REPLY_SUCCEEDED, outbound.socket().localSocketAddress as? InetSocketAddress)
            // Relay is non-blocking from here on: both channels are serviced by the shared reactor.
            client.soTimeout = 0
            clientChannel.configureBlocking(false)
            outbound.configureBlocking(false)

            var initialUpload: ByteArray? = null
            // Sniff the real host when either a domain-scoped fault or domain-scoped shaping needs it.
            val needHost = fault.usesDomainFilter || nonDomainShape == null
            val shapeTraffic: Boolean
            var effectiveHost = request.host
            if (needHost) {
                // Sniff the real hostname (TLS SNI / HTTP Host) so domain matching works regardless
                // of how the app resolved the name (DoH / DoT / cached DNS the tunnel can't see).
                val (peeked, sniffed) = resolveClientHost(clientChannel)
                initialUpload = peeked
                val host = sniffed ?: request.host
                effectiveHost = host
                if (fault.usesDomainFilter) {
                    val decision = fault.decideTcp(
                        host,
                        request.port,
                        TcpDecisionPhase.POST_CONNECT,
                    )
                    if (decision !is TcpFault.None) {
                        runCatching { outbound.close() }
                        applyTcpFaultAfterConnect(client, clientChannel, decision)
                        return
                    }
                }
                shapeTraffic = nonDomainShape ?: shaping.shouldShape(host, request.port)
            } else {
                shapeTraffic = nonDomainShape == true
            }
            if (shapeTraffic && shaper.blocksAllTraffic()) return // drop the flow → blocked

            // Response Hold: a fixed downstream delay applied per chunk, independent of shaping.
            val holdDownstreamNanos =
                fault.holdDownstreamMs(effectiveHost, request.port) * NANOS_PER_MILLISECOND
            // Small responses (heartbeats / reachability pings) can bypass the hold.
            val holdBypassBytes = if (holdDownstreamNanos > 0L) fault.holdBypassBytes else 0

            val flow = FlowLog.open("TCP", effectiveHost, request.port)
            flow.shaped = shapeTraffic
            try {
                relay(
                    clientChannel, outbound, shapeTraffic,
                    holdDownstreamNanos, holdBypassBytes, flow, initialUpload,
                )
            } catch (error: Exception) { Log.w("PakomoSocks", "SOCKS TCP relay closed: " + error.message); safeLog("SOCKS TCP relay closed", error)
            } finally {
                flow.close()
            }
        } catch (error: Exception) {
            Log.w("PakomoSocks", "SOCKS TCP session failed: " + error.message); safeLog("SOCKS TCP session failed", error)
        } finally {
            runCatching { outbound.close() }
        }
    }

    /** Applies a TCP fault before the outbound connect (host-independent global / whole-app case). */
    private suspend fun applyTcpFaultBeforeConnect(
        client: Socket,
        clientChannel: SocketChannel,
        output: OutputStream,
        fault: TcpFault,
    ) {
        when (fault) {
            is TcpFault.Blackout -> if (fault.mode == BlackoutMode.IMMEDIATE) {
                // Reject the new connection outright.
                writeReply(output, REPLY_CONNECTION_REFUSED, null)
            } else {
                // Silent: accept, then hold the connection with no response so the app waits/timeouts.
                writeReply(output, REPLY_SUCCEEDED, null)
                client.soTimeout = 0
                runCatching { clientChannel.configureBlocking(false) }
                parkSilently(clientChannel)
            }
            TcpFault.Reset -> {
                // Establish then reset, so the app observes a TCP reset on the connection.
                writeReply(output, REPLY_SUCCEEDED, null)
                resetClient(client)
            }
            TcpFault.None -> Unit
        }
    }

    /** Applies a TCP fault after outbound was connected and success already replied. */
    private suspend fun applyTcpFaultAfterConnect(
        client: Socket,
        clientChannel: SocketChannel,
        fault: TcpFault,
    ) {
        when (fault) {
            is TcpFault.Blackout -> if (fault.mode == BlackoutMode.SILENT) {
                parkSilently(clientChannel)
            } else {
                // Immediate blackout after success can no longer send an unreachable reply; the
                // closest equivalent is an abrupt reset of the established connection.
                resetClient(client)
            }
            TcpFault.Reset -> resetClient(client)
            TcpFault.None -> Unit
        }
    }

    /** Closes the client connection with SO_LINGER 0 so the tunnel forwards a TCP reset (RST). */
    private fun resetClient(client: Socket) {
        runCatching { client.setSoLinger(true, 0) }
        runCatching { client.channel?.close() }
        runCatching { client.close() }
    }

    /** Holds a connection open without ever responding, until the app closes it or it goes idle. */
    private suspend fun parkSilently(clientChannel: SocketChannel) {
        // Silent faults may hold many retrying connections at once; only a small drain buffer is
        // needed because payload is discarded. Avoid reserving the normal 16 KiB relay buffer for
        // every parked connection.
        val buffer = ByteBuffer.allocate(SILENT_DRAIN_BUFFER_BYTES)
        try {
            while (true) {
                buffer.clear()
                val count = relayLoop.read(clientChannel, buffer, RELAY_IDLE_TIMEOUT_MS)
                if (count < 0) break // the app gave up and closed the connection
                // Discard whatever the app sends; a silent blackout never writes anything back.
            }
        } catch (_: Exception) {
            // Idle timeout or read error: stop holding the connection.
        }
    }

    private suspend fun connectOutbound(output: OutputStream, request: SocksRequest): SocketChannel? {
        val outbound = SocketChannel.open()
        outbound.configureBlocking(true)
        if (!protector.protect(outbound.socket())) {
            writeReply(output, REPLY_GENERAL_FAILURE, null)
            runCatching { outbound.close() }
            return null
        }
        return try {
            withContext(Dispatchers.IO) {
                outbound.socket().connect(
                    InetSocketAddress(request.host, request.port),
                    SecurityPolicy.OUTBOUND_CONNECT_TIMEOUT_MS,
                )
            }
            outbound
        } catch (error: Exception) {
            safeLog("SOCKS TCP connect failed", error)
            runCatching { writeReply(output, REPLY_HOST_UNREACHABLE, null) }
            runCatching { outbound.close() }
            null
        }
    }

    /**
     * Reads the first client bytes (up to a bounded size / short timeout) to recover the destination
     * hostname from the TLS ClientHello SNI or the HTTP Host header. Returns the consumed bytes (which
     * the caller forwards as the start of the upload stream) and the hostname if found.
     */
    private suspend fun resolveClientHost(client: SocketChannel): Pair<ByteArray, String?> {
        val buffer = ByteBuffer.allocate(SecurityPolicy.SOCKS_COPY_BUFFER_BYTES)
        var host: String? = null
        try {
            while (buffer.position() < buffer.capacity()) {
                val count = relayLoop.read(client, buffer, PEEK_TIMEOUT_MS)
                if (count <= 0) break
                val length = buffer.position()
                val data = buffer.array()
                val tlsRecordSize = HostSniffer.tlsRecordSize(data, length)
                if (tlsRecordSize in 1..buffer.capacity()) {
                    if (length < tlsRecordSize) continue // keep reading until the record is complete
                    host = HostSniffer.extract(data, length)
                    break
                }
                if (HostSniffer.looksLikeHttp(data, length)) {
                    host = HostSniffer.extract(data, length)
                    if (host != null || String(data, 0, length, Charsets.US_ASCII).contains("\r\n\r\n")) break
                    continue // keep reading until headers complete
                }
                break // neither TLS nor HTTP: nothing to sniff
            }
        } catch (_: Exception) {
            // Timeout or read error (e.g. a server-speaks-first protocol): fall back to the IP.
        }
        return buffer.array().copyOf(buffer.position()) to host
    }

    private suspend fun handleUdpAssociate(
        client: Socket,
        controlOutput: OutputStream,
        shaping: ConnectionShaping,
        fault: ConnectionFault,
    ) {
        val controlChannel = checkNotNull(client.channel) {
            "UDP association requires a channel-backed control socket"
        }
        val localChannel = DatagramChannel.open()
        val relayChannel = DatagramChannel.open()
        localChannel.bind(
            InetSocketAddress(
                InetAddress.getByName(SecurityPolicy.SOCKS_LOOPBACK_ADDRESS),
                0,
            ),
        )
        if (!protector.protect(relayChannel.socket())) {
            localChannel.close()
            relayChannel.close()
            safeLog("SOCKS UDP relay socket protection failed")
            writeReply(controlOutput, REPLY_GENERAL_FAILURE, null)
            return
        }
        writeReply(
            controlOutput,
            REPLY_SUCCEEDED,
            localChannel.localAddress as InetSocketAddress,
        )
        client.soTimeout = 0
        controlChannel.configureBlocking(false)
        localChannel.configureBlocking(false)
        relayChannel.configureBlocking(false)

        val controlBuffer = ByteBuffer.allocate(1)
        val requestBuffer = ByteBuffer.allocate(SecurityPolicy.MAX_UDP_PACKET_BYTES)
        val responseBuffer = ByteBuffer.allocate(SecurityPolicy.MAX_UDP_PACKET_BYTES)
        var clientEndpoint: InetSocketAddress? = null

        // One flow record per UDP destination so the diagnostics「流量」page also shows QUIC / DNS.
        val udpFlows = HashMap<String, FlowHandle>()
        fun udpFlow(host: String, port: Int): FlowHandle = udpFlows.getOrPut("$host:$port") {
            val proto = when (port) {
                443 -> "QUIC"
                DNS_PORT -> "DNS"
                else -> "UDP"
            }
            FlowLog.open(proto, host, port)
        }

        try {
            while (controlChannel.isOpen) {
                var handledPacket = false

                controlBuffer.clear()
                if (controlChannel.read(controlBuffer) < 0) break

                requestBuffer.clear()
                val sender = localChannel.receive(requestBuffer) as? InetSocketAddress
                if (sender != null) {
                    handledPacket = true
                    if (clientEndpoint == null) clientEndpoint = sender
                    if (sender == clientEndpoint) {
                        val request = parseUdpPacket(
                            requestBuffer.array(),
                            requestBuffer.position(),
                        )
                        if (request != null) {
                            val dnsName = if (request.port == DNS_PORT) {
                                DnsMessage.queryName(request.payload)
                            } else {
                                null
                            }
                            when (val udpFault = fault.decideUdp(request.host, request.port, dnsName)) {
                                // Blackout (or a DNS timeout): forward nothing and send no reply, so
                                // the datagram is lost and a DNS query eventually times out.
                                UdpFault.Drop -> Unit
                                is UdpFault.Dns ->
                                    sendDnsFailure(localChannel, sender, request, udpFault.result)
                                UdpFault.None -> {
                                    val target = InetSocketAddress(request.host, request.port)
                                    val shape = shaping.shouldShape(request.host, request.port)
                                    val flow = udpFlow(request.host, request.port)
                                    flow.up.addAndGet(request.payload.size.toLong())
                                    if (shape) flow.shaped = true
                                    if (shape) {
                                        val decision = shaper.decide(
                                            TrafficDirection.UPLOAD,
                                            request.payload.size,
                                            isDatagram = true,
                                        )
                                        if (!decision.drop) {
                                            scheduleDatagram(relayChannel, request.payload, target, decision.waitNanos)
                                        }
                                    } else {
                                        sendDatagram(relayChannel, request.payload, target)
                                    }
                                }
                            }
                        }
                    }
                }

                responseBuffer.clear()
                val source = relayChannel.receive(responseBuffer) as? InetSocketAddress
                val target = clientEndpoint
                if (source != null && target != null) {
                    handledPacket = true
                    val payload = responseBuffer.array().copyOf(responseBuffer.position())
                    val sourceHost = source.address.hostAddress.orEmpty()
                    udpFlow(sourceHost, source.port).down.addAndGet(payload.size.toLong())
                    if (source.port == DNS_PORT) {
                        shaping.observeDnsResponse(payload)
                        // Let domain-scoped faults learn the target's IPs so they can match QUIC /
                        // no-SNI flows by destination IP, the same way domain shaping does.
                        fault.observeDnsResponse(payload)
                    }
                    val datagram = buildUdpPacket(source, payload)
                    if (shaping.shouldShape(sourceHost, source.port)) {
                        val decision = shaper.decide(
                            TrafficDirection.DOWNLOAD,
                            payload.size,
                            isDatagram = true,
                        )
                        if (!decision.drop) {
                            scheduleDatagram(localChannel, datagram, target, decision.waitNanos)
                        }
                    } else {
                        sendDatagram(localChannel, datagram, target)
                    }
                }

                if (!handledPacket) {
                    // Non-blocking channels keep idle associations from consuming IO threads.
                    delay(UDP_POLL_INTERVAL_MS)
                }
            }
        } catch (error: Exception) {
            if (controlChannel.isOpen) {
                safeLog("SOCKS UDP relay failed", error)
            }
        } finally {
            udpFlows.values.forEach { it.close() }
            localChannel.close()
            relayChannel.close()
        }
    }

    private suspend fun sendDatagram(
        channel: DatagramChannel,
        payload: ByteArray,
        target: InetSocketAddress,
    ) {
        val buffer = ByteBuffer.wrap(payload)
        while (buffer.hasRemaining() && channel.send(buffer, target) == 0) {
            delay(UDP_SEND_RETRY_DELAY_MS)
        }
    }

    /**
     * Sends a datagram after its shaping delay without blocking the UDP relay loop. Datagrams are
     * unordered, so each delayed send runs independently; a slow/late datagram never stalls the
     * others (the previous inline `await` throttled the whole association to one packet per delay).
     */
    private fun scheduleDatagram(
        channel: DatagramChannel,
        payload: ByteArray,
        target: InetSocketAddress,
        waitNanos: Long,
    ) {
        scope.launch {
            if (waitNanos > 0) delay(waitNanos / NANOS_PER_MILLISECOND)
            runCatching { sendDatagram(channel, payload, target) }
        }
    }

    /**
     * Synthesizes a DNS failure reply back to the app. NXDOMAIN / SERVFAIL / REFUSED send a same-id
     * error response that appears to come from the queried resolver; TIMEOUT sends nothing so the
     * query times out on its own.
     */
    private suspend fun sendDnsFailure(
        localChannel: DatagramChannel,
        clientEndpoint: InetSocketAddress,
        request: UdpRequest,
        result: DnsFailureResult,
    ) {
        val rcode = when (result) {
            DnsFailureResult.NXDOMAIN -> DnsMessage.RCODE_NXDOMAIN
            DnsFailureResult.SERVFAIL -> DnsMessage.RCODE_SERVFAIL
            DnsFailureResult.REFUSED -> DnsMessage.RCODE_REFUSED
            DnsFailureResult.TIMEOUT -> return
        }
        val reply = DnsMessage.failureResponse(request.payload, request.payload.size, rcode) ?: return
        val source = runCatching {
            InetSocketAddress(InetAddress.getByName(request.host), request.port)
        }.getOrNull() ?: return
        runCatching { sendDatagram(localChannel, buildUdpPacket(source, reply), clientEndpoint) }
    }

    private fun negotiateAuthentication(input: InputStream, output: OutputStream): Boolean {
        if (input.readRequired() != VERSION_SOCKS5) return false
        val methodCount = input.readRequired()
        val methods = input.readExact(methodCount)
        val accepted = methods.any { it.toInt() and 0xFF == METHOD_USERNAME_PASSWORD }
        output.write(
            byteArrayOf(
                VERSION_SOCKS5.toByte(),
                if (accepted) METHOD_USERNAME_PASSWORD.toByte() else 0xFF.toByte(),
            ),
        )
        output.flush()
        return accepted
    }

    private fun authenticate(input: InputStream, output: OutputStream): Boolean {
        if (input.readRequired() != VERSION_USER_PASS) return false
        val username = input.readExact(input.readRequired()).toString(StandardCharsets.UTF_8)
        val password = input.readExact(input.readRequired()).toString(StandardCharsets.UTF_8)
        val accepted = constantTimeEquals(username, credentials.username) &&
            constantTimeEquals(password, credentials.password)
        output.write(
            byteArrayOf(
                VERSION_USER_PASS.toByte(),
                if (accepted) 0.toByte() else 1.toByte(),
            ),
        )
        output.flush()
        return accepted
    }

    private fun readRequest(input: InputStream): SocksRequest? {
        if (input.readRequired() != VERSION_SOCKS5) return null
        val command = input.readRequired()
        input.readRequired()
        val host = when (input.readRequired()) {
            ADDRESS_IPV4 -> InetAddress.getByAddress(input.readExact(4)).hostAddress ?: return null
            ADDRESS_IPV6 -> InetAddress.getByAddress(input.readExact(16)).hostAddress ?: return null
            ADDRESS_DOMAIN -> input.readExact(input.readRequired()).toString(StandardCharsets.US_ASCII)
            else -> return null
        }
        val port = (input.readRequired() shl 8) or input.readRequired()
        return SocksRequest(command, host, port)
    }

    private suspend fun relay(
        client: SocketChannel,
        outbound: SocketChannel,
        shapeTraffic: Boolean,
        holdDownstreamNanos: Long,
        holdBypassBytes: Int,
        flow: FlowHandle?,
        initialUpload: ByteArray?,
    ) = coroutineScope {
        val upload = launch {
            pump(
                from = client,
                to = outbound,
                direction = TrafficDirection.UPLOAD,
                shapeTraffic = shapeTraffic,
                holdNanos = 0L, // Response Hold only delays the server→client direction.
                holdBypassBytes = 0,
                flow = flow,
                initial = initialUpload,
            )
            runCatching { outbound.shutdownOutput() }
        }
        val download = launch {
            pump(
                from = outbound,
                to = client,
                direction = TrafficDirection.DOWNLOAD,
                shapeTraffic = shapeTraffic,
                holdNanos = holdDownstreamNanos,
                holdBypassBytes = holdBypassBytes,
                flow = flow,
                initial = null,
            )
            runCatching { client.shutdownOutput() }
        }
        upload.join()
        download.join()
    }

    private suspend fun pump(
        from: SocketChannel,
        to: SocketChannel,
        direction: TrafficDirection,
        shapeTraffic: Boolean,
        holdNanos: Long,
        holdBypassBytes: Int,
        flow: FlowHandle?,
        initial: ByteArray?,
    ) {
        val counter = if (direction == TrafficDirection.DOWNLOAD) flow?.down else flow?.up
        if (shapeTraffic || holdNanos > 0L) {
            delayedPump(from, to, direction, shapeTraffic, holdNanos, holdBypassBytes, counter, flow, initial)
        } else {
            copyDirect(from, to, counter, initial)
        }
    }

    /** Straight non-blocking copy for a direction that is not being shaped. */
    private suspend fun copyDirect(
        from: SocketChannel,
        to: SocketChannel,
        counter: java.util.concurrent.atomic.AtomicLong?,
        initial: ByteArray?,
    ) {
        if (initial != null && initial.isNotEmpty()) {
            counter?.addAndGet(initial.size.toLong())
            relayLoop.writeFully(to, ByteBuffer.wrap(initial), RELAY_WRITE_TIMEOUT_MS)
        }
        val buffer = ByteBuffer.allocate(SecurityPolicy.SOCKS_COPY_BUFFER_BYTES)
        while (true) {
            buffer.clear()
            val count = relayLoop.read(from, buffer, RELAY_IDLE_TIMEOUT_MS)
            if (count < 0) break
            if (count == 0) continue
            counter?.addAndGet(count.toLong())
            buffer.flip()
            relayLoop.writeFully(to, buffer, RELAY_WRITE_TIMEOUT_MS)
        }
    }

    /**
     * Delayed copy for one direction. Reading is decoupled from the release delay: the reader makes
     * the loss decision (when shaping applies), stamps each chunk with an absolute release time, and
     * hands it to a bounded queue, then immediately reads the next chunk. A separate writer releases
     * chunks in arrival order (preserving the TCP byte stream) once their release time is reached.
     * This keeps the delay a constant added latency instead of a per-chunk serial wait that would
     * throttle the flow to ~1 chunk per delay and back up the queue.
     *
     * Two delays shape each chunk's release time:
     *  - weak-network shaping (only when [applyShaping]) — the configured latency/jitter, plus its
     *    loss decision that may drop the chunk;
     *  - Response Hold ([holdNanos] > 0, downstream only) — a *gate*: the first downstream byte opens
     *    the gate at (its arrival + holdMs); every chunk arriving before then is buffered and
     *    released together when the gate opens, and chunks arriving after pass at their own time. So
     *    the client sees the response begin ~holdMs late and then delivered at full speed — a
     *    constant holdMs delay, not the server's whole stream time shifted by holdMs (which would
     *    roughly double the observed delay for a response the server streams over ~holdMs).
     *
     * [holdBypassBytes] > 0 lets small responses skip the gate: while the connection's cumulative
     * downstream stays within that many bytes, chunks are released immediately (heartbeats /
     * reachability "pings"); once it grows past the threshold, this and every later chunk are gated.
     *
     * Each chunk passes through here exactly once, so neither delay is ever applied twice. When a
     * hold is active the queue is UNLIMITED so the reader never backpressures (a bounded queue would
     * stall the reader during the hold and re-stamp late-read chunks, compounding the delay); the
     * cost is buffering the response in memory during the hold window.
     */
    private suspend fun delayedPump(
        from: SocketChannel,
        to: SocketChannel,
        direction: TrafficDirection,
        applyShaping: Boolean,
        holdNanos: Long,
        holdBypassBytes: Int,
        counter: java.util.concurrent.atomic.AtomicLong?,
        flow: FlowHandle?,
        initial: ByteArray?,
    ) = coroutineScope {
        val queue = Channel<DelayedChunk>(
            capacity = if (holdNanos > 0L) Channel.UNLIMITED else SHAPED_QUEUE_CAPACITY,
        )
        val writer = launch {
            for (chunk in queue) {
                val waitNanos = chunk.releaseNanos - System.nanoTime()
                if (waitNanos > 0) delay(waitNanos / NANOS_PER_MILLISECOND)
                relayLoop.writeFully(to, ByteBuffer.wrap(chunk.bytes), RELAY_WRITE_TIMEOUT_MS)
            }
        }
        // Gate state: the hold opens the gate once, at (first downstream byte + holdMs), rather than
        // delaying each chunk by holdMs. That way a response the server streams over ~holdMs is
        // buffered and delivered as a burst when the gate opens (client sees it ~holdMs late) instead
        // of having its whole stream time-shifted (which would double the observed delay). [seenBytes]
        // drives the small-response bypass.
        var gateStarted = false
        var gateOpenNanos = 0L
        var seenBytes = 0L
        fun releaseNanosFor(chunkSize: Int, shapingNanos: Long): Long {
            val arrival = System.nanoTime()
            if (holdNanos <= 0L) return arrival + shapingNanos
            val bypass = holdBypassBytes > 0 && seenBytes + chunkSize <= holdBypassBytes
            seenBytes += chunkSize
            if (bypass) return arrival + shapingNanos
            // The first *held* chunk (the real response starting) opens the gate — not a bypassed
            // heartbeat, so an early heartbeat on a keep-alive connection can't open it prematurely.
            if (!gateStarted) {
                gateStarted = true
                gateOpenNanos = arrival + holdNanos
            }
            flow?.held = true // record the hold as actually applied
            // Held until the gate opens; chunks arriving after it pass at their own arrival time.
            return maxOf(arrival, gateOpenNanos) + shapingNanos
        }
        val buffer = ByteBuffer.allocate(SecurityPolicy.SOCKS_COPY_BUFFER_BYTES)
        try {
            if (initial != null && initial.isNotEmpty()) {
                val shapingNanos = if (applyShaping) {
                    val decision = shaper.decide(direction, initial.size, isDatagram = false)
                    if (decision.drop) -1L else decision.waitNanos
                } else {
                    0L
                }
                if (shapingNanos >= 0L) {
                    counter?.addAndGet(initial.size.toLong())
                    queue.send(DelayedChunk(initial, releaseNanosFor(initial.size, shapingNanos)))
                }
            }
            while (true) {
                buffer.clear()
                val count = relayLoop.read(from, buffer, RELAY_IDLE_TIMEOUT_MS)
                if (count < 0) break
                if (count == 0) continue
                val shapingNanos = if (applyShaping) {
                    val decision = shaper.decide(direction, count, isDatagram = false)
                    if (decision.drop) continue
                    decision.waitNanos
                } else {
                    0L
                }
                counter?.addAndGet(count.toLong())
                val bytes = ByteArray(count)
                buffer.flip()
                buffer.get(bytes)
                queue.send(DelayedChunk(bytes, releaseNanosFor(count, shapingNanos)))
            }
        } finally {
            queue.close()
        }
        writer.join()
    }

    private fun writeReply(
        output: OutputStream,
        reply: Int,
        bound: InetSocketAddress?,
    ) {
        val address = bound?.address ?: InetAddress.getByName("0.0.0.0")
        val addressType: Int
        val addressBytes: ByteArray
        when (address) {
            is Inet4Address -> {
                addressType = ADDRESS_IPV4
                addressBytes = address.address
            }
            is Inet6Address -> {
                addressType = ADDRESS_IPV6
                addressBytes = address.address
            }
            else -> {
                addressType = ADDRESS_IPV4
                addressBytes = byteArrayOf(0, 0, 0, 0)
            }
        }
        val port = bound?.port ?: 0
        output.write(
            byteArrayOf(
                VERSION_SOCKS5.toByte(),
                reply.toByte(),
                0,
                addressType.toByte(),
            ),
        )
        output.write(addressBytes)
        output.write(byteArrayOf((port ushr 8).toByte(), port.toByte()))
        output.flush()
    }

    private fun parseUdpPacket(bytes: ByteArray, size: Int): UdpRequest? {
        if (size < 10 || bytes[0].toInt() != 0 || bytes[1].toInt() != 0) return null
        if (bytes[2].toInt() != 0) return null
        var offset = 4
        val host = when (bytes[3].toInt() and 0xFF) {
            ADDRESS_IPV4 -> {
                if (size < offset + 4 + 2) return null
                InetAddress.getByAddress(bytes.copyOfRange(offset, offset + 4)).hostAddress
                    .also { offset += 4 }
            }
            ADDRESS_IPV6 -> {
                if (size < offset + 16 + 2) return null
                InetAddress.getByAddress(bytes.copyOfRange(offset, offset + 16)).hostAddress
                    .also { offset += 16 }
            }
            ADDRESS_DOMAIN -> {
                if (size <= offset) return null
                val length = bytes[offset].toInt() and 0xFF
                offset += 1
                if (size < offset + length + 2) return null
                bytes.copyOfRange(offset, offset + length).toString(StandardCharsets.US_ASCII)
                    .also { offset += length }
            }
            else -> return null
        } ?: return null
        val port = ((bytes[offset].toInt() and 0xFF) shl 8) or
            (bytes[offset + 1].toInt() and 0xFF)
        offset += 2
        if (offset > size) return null
        return UdpRequest(host, port, bytes.copyOfRange(offset, size))
    }

    private fun buildUdpPacket(source: InetSocketAddress, payload: ByteArray): ByteArray {
        val address = source.address
        val addressType = if (address is Inet4Address) ADDRESS_IPV4 else ADDRESS_IPV6
        val addressBytes = address.address
        return ByteBuffer.allocate(4 + addressBytes.size + 2 + payload.size)
            .put(0.toByte())
            .put(0.toByte())
            .put(0.toByte())
            .put(addressType.toByte())
            .put(addressBytes)
            .putShort(source.port.toShort())
            .put(payload)
            .array()
    }

    private fun InputStream.readRequired(): Int {
        val value = read()
        if (value < 0) throw EOFException()
        return value
    }

    private fun InputStream.readExact(size: Int): ByteArray {
        require(size in 0..255)
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = read(result, offset, size - offset)
            if (read < 0) throw EOFException()
            offset += read
        }
        return result
    }

    private fun constantTimeEquals(left: String, right: String): Boolean {
        val leftBytes = left.toByteArray(StandardCharsets.UTF_8)
        val rightBytes = right.toByteArray(StandardCharsets.UTF_8)
        var difference = leftBytes.size xor rightBytes.size
        val length = maxOf(leftBytes.size, rightBytes.size)
        for (index in 0 until length) {
            val a = leftBytes.getOrElse(index) { 0.toByte() }
            val b = rightBytes.getOrElse(index) { 0.toByte() }
            difference = difference or (a.toInt() xor b.toInt())
        }
        return difference == 0
    }

    private fun safeLog(message: String, error: Throwable? = null) {
        runCatching {
            if (error == null) {
                Log.d(TAG, message)
            } else {
                Log.w(TAG, message, error)
            }
        }
    }

    private data class SocksRequest(
        val command: Int,
        val host: String,
        val port: Int,
    )

    private data class UdpRequest(
        val host: String,
        val port: Int,
        val payload: ByteArray,
    )

    /** A shaped chunk awaiting its absolute release time in the pipelined TCP relay. */
    private class DelayedChunk(val bytes: ByteArray, val releaseNanos: Long)

    private companion object {
        const val VERSION_SOCKS5 = 5
        const val VERSION_USER_PASS = 1
        const val METHOD_USERNAME_PASSWORD = 2
        const val COMMAND_CONNECT = 1
        const val COMMAND_UDP_ASSOCIATE = 3
        const val ADDRESS_IPV4 = 1
        const val ADDRESS_DOMAIN = 3
        const val ADDRESS_IPV6 = 4
        const val REPLY_SUCCEEDED = 0
        const val REPLY_GENERAL_FAILURE = 1
        const val REPLY_NETWORK_UNREACHABLE = 3
        const val REPLY_HOST_UNREACHABLE = 4
        const val REPLY_CONNECTION_REFUSED = 5
        const val REPLY_COMMAND_NOT_SUPPORTED = 7
        const val DNS_PORT = 53
        const val SILENT_DRAIN_BUFFER_BYTES = 1_024
        const val UDP_POLL_INTERVAL_MS = 5L
        const val UDP_SEND_RETRY_DELAY_MS = 1L
        // Idle long-lived TCP flows are cheap now (a suspended coroutine), so the read idle
        // timeout is generous and only reclaims truly dead connections.
        const val RELAY_IDLE_TIMEOUT_MS = 300_000L
        const val RELAY_WRITE_TIMEOUT_MS = 60_000L
        // Short bound on how long domain-filtered flows wait for the client's first bytes (SNI/Host)
        // before falling back to the destination IP; keeps server-speaks-first protocols responsive.
        const val PEEK_TIMEOUT_MS = 1_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        // Bounds in-flight shaped chunks (~one delay-window of data); provides backpressure so a
        // bandwidth limit slows the reader instead of buffering unboundedly.
        const val SHAPED_QUEUE_CAPACITY = 128
        const val TAG = "PakomoSocks"
    }
}
