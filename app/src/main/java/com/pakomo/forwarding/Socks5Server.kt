package com.pakomo.forwarding

import com.pakomo.security.SecurityPolicy
import com.pakomo.shaping.TrafficDirection
import com.pakomo.shaping.TrafficShaper
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SocksCredentials(
    val username: String,
    val password: String,
)

class Socks5Server(
    private val credentials: SocksCredentials,
    private val protector: SocketProtector,
    private val shaper: TrafficShaper,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeSessions = AtomicInteger(0)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    val port: Int
        get() = serverSocket?.localPort ?: 0

    fun start(): Int {
        check(serverSocket == null) { "SOCKS server is already running" }
        val listener = ServerSocket(
            0,
            SecurityPolicy.SOCKS_LISTEN_BACKLOG,
            InetAddress.getByName(SecurityPolicy.SOCKS_LOOPBACK_ADDRESS),
        )
        serverSocket = listener
        acceptJob = scope.launch {
            while (!listener.isClosed) {
                val client = try {
                    listener.accept()
                } catch (_: SocketException) {
                    break
                }
                if (activeSessions.incrementAndGet() > SecurityPolicy.MAX_ACTIVE_FLOWS) {
                    activeSessions.decrementAndGet()
                    client.close()
                    continue
                }
                launch {
                    try {
                        handleClient(client)
                    } finally {
                        activeSessions.decrementAndGet()
                        runCatching { client.close() }
                    }
                }
            }
        }
        return listener.localPort
    }

    fun activeSessionCount(): Int = activeSessions.get()

    override fun close() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        scope.cancel()
    }

    private suspend fun handleClient(client: Socket) {
        client.soTimeout = SecurityPolicy.SOCKS_HANDSHAKE_TIMEOUT_MS
        val input = client.getInputStream()
        val output = client.getOutputStream()
        if (!negotiateAuthentication(input, output)) return
        if (!authenticate(input, output)) return
        val request = readRequest(input) ?: return
        when (request.command) {
            COMMAND_CONNECT -> handleConnect(client, output, request)
            COMMAND_UDP_ASSOCIATE -> handleUdpAssociate(client, input, output)
            else -> writeReply(output, REPLY_COMMAND_NOT_SUPPORTED, null)
        }
    }

    private suspend fun handleConnect(
        client: Socket,
        output: OutputStream,
        request: SocksRequest,
    ) {
        val outbound = Socket()
        try {
            if (!protector.protect(outbound)) {
                writeReply(output, REPLY_GENERAL_FAILURE, null)
                return
            }
            withContext(Dispatchers.IO) {
                outbound.connect(
                    InetSocketAddress(request.host, request.port),
                    SecurityPolicy.OUTBOUND_CONNECT_TIMEOUT_MS,
                )
            }
            client.soTimeout = 0
            outbound.soTimeout = 0
            outbound.tcpNoDelay = true
            writeReply(output, REPLY_SUCCEEDED, outbound.localSocketAddress as? InetSocketAddress)
            relay(client, outbound)
        } catch (_: Exception) {
            runCatching { writeReply(output, REPLY_HOST_UNREACHABLE, null) }
        } finally {
            runCatching { outbound.close() }
        }
    }

    private suspend fun handleUdpAssociate(
        client: Socket,
        controlInput: InputStream,
        controlOutput: OutputStream,
    ) = coroutineScope {
        val localSocket = DatagramSocket(
            InetSocketAddress(
                InetAddress.getByName(SecurityPolicy.SOCKS_LOOPBACK_ADDRESS),
                0,
            ),
        )
        val relaySocket = DatagramSocket()
        if (!protector.protect(relaySocket)) {
            localSocket.close()
            relaySocket.close()
            writeReply(controlOutput, REPLY_GENERAL_FAILURE, null)
            return@coroutineScope
        }
        writeReply(
            controlOutput,
            REPLY_SUCCEEDED,
            localSocket.localSocketAddress as InetSocketAddress,
        )
        client.soTimeout = 0
        val clientEndpoint = AtomicReference<InetSocketAddress?>(null)

        val upstream = launch {
            val packetBuffer = ByteArray(SecurityPolicy.MAX_UDP_PACKET_BYTES)
            val packet = DatagramPacket(packetBuffer, packetBuffer.size)
            while (!localSocket.isClosed) {
                try {
                    packet.length = packetBuffer.size
                    localSocket.receive(packet)
                    val sender = packet.socketAddress as? InetSocketAddress ?: continue
                    clientEndpoint.compareAndSet(null, sender)
                    if (sender != clientEndpoint.get()) continue
                    val request = parseUdpPacket(packetBuffer, packet.length) ?: continue
                    val decision = shaper.decide(
                        TrafficDirection.UPLOAD,
                        request.payload.size,
                        isDatagram = true,
                    )
                    if (!shaper.await(decision)) continue
                    relaySocket.send(
                        DatagramPacket(
                            request.payload,
                            request.payload.size,
                            InetSocketAddress(request.host, request.port),
                        ),
                    )
                } catch (_: SocketException) {
                    break
                }
            }
        }
        val downstream = launch {
            val responseBuffer = ByteArray(SecurityPolicy.MAX_UDP_PACKET_BYTES)
            val response = DatagramPacket(responseBuffer, responseBuffer.size)
            while (!relaySocket.isClosed) {
                try {
                    response.length = responseBuffer.size
                    relaySocket.receive(response)
                    val target = clientEndpoint.get() ?: continue
                    val payload = responseBuffer.copyOf(response.length)
                    val decision = shaper.decide(
                        TrafficDirection.DOWNLOAD,
                        payload.size,
                        isDatagram = true,
                    )
                    if (!shaper.await(decision)) continue
                    val wrapped = buildUdpPacket(
                        source = response.socketAddress as InetSocketAddress,
                        payload = payload,
                    )
                    localSocket.send(DatagramPacket(wrapped, wrapped.size, target))
                } catch (_: SocketException) {
                    break
                }
            }
        }

        try {
            withContext(Dispatchers.IO) {
                while (controlInput.read() >= 0) {
                    // This TCP stream controls the lifetime of the UDP association.
                }
            }
        } finally {
            localSocket.close()
            relaySocket.close()
            upstream.join()
            downstream.join()
        }
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

    private suspend fun relay(client: Socket, outbound: Socket) = coroutineScope {
        val upload = launch {
            copyShaped(
                input = client.getInputStream(),
                output = outbound.getOutputStream(),
                direction = TrafficDirection.UPLOAD,
            )
            runCatching { outbound.shutdownOutput() }
        }
        val download = launch {
            copyShaped(
                input = outbound.getInputStream(),
                output = client.getOutputStream(),
                direction = TrafficDirection.DOWNLOAD,
            )
            runCatching { client.shutdownOutput() }
        }
        upload.join()
        download.join()
    }

    private suspend fun copyShaped(
        input: InputStream,
        output: OutputStream,
        direction: TrafficDirection,
    ) {
        val buffer = ByteArray(SecurityPolicy.SOCKS_COPY_BUFFER_BYTES)
        while (true) {
            val count = withContext(Dispatchers.IO) { input.read(buffer) }
            if (count <= 0) break
            val decision = shaper.decide(
                direction = direction,
                byteCount = count,
                isDatagram = false,
            )
            if (!shaper.await(decision)) break
            withContext(Dispatchers.IO) {
                output.write(buffer, 0, count)
                output.flush()
            }
        }
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
        const val REPLY_HOST_UNREACHABLE = 4
        const val REPLY_COMMAND_NOT_SUPPORTED = 7
    }
}
