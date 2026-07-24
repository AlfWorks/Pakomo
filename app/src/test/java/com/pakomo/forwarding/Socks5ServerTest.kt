package com.pakomo.forwarding

import com.pakomo.core.model.defaultRules
import com.pakomo.shaping.TrafficShaper
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Socks5ServerTest {
    @Test
    fun authenticatedConnectRelaysBytesThroughProtectedSocket() {
        val echo = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val echoThread = thread(start = true, isDaemon = true) {
            echo.accept().use { client ->
                val buffer = ByteArray(64)
                val count = client.getInputStream().read(buffer)
                client.getOutputStream().write(buffer, 0, count)
            }
        }
        val protected = AtomicBoolean(false)
        val socks = Socks5Server(
            credentials = SocksCredentials("pakomo", "test-token"),
            protector = object : SocketProtector {
                override fun protect(socket: Socket): Boolean {
                    protected.set(true)
                    return true
                }

                override fun protect(socket: DatagramSocket): Boolean = true
            },
            shaper = TrafficShaper(defaultRules.first { it.id == "normal" }),
        )

        try {
            val socksPort = socks.start()
            Socket(InetAddress.getLoopbackAddress(), socksPort).use { client ->
                val input = client.getInputStream()
                val output = client.getOutputStream()

                output.write(byteArrayOf(5, 1, 2))
                assertArrayEquals(byteArrayOf(5, 2), input.readExact(2))

                output.write(
                    byteArrayOf(1, 6) +
                        "pakomo".toByteArray() +
                        byteArrayOf(10) +
                        "test-token".toByteArray(),
                )
                assertArrayEquals(byteArrayOf(1, 0), input.readExact(2))

                val port = echo.localPort
                output.write(
                    byteArrayOf(
                        5,
                        1,
                        0,
                        1,
                        127,
                        0,
                        0,
                        1,
                        (port ushr 8).toByte(),
                        port.toByte(),
                    ),
                )
                val replyHeader = input.readExact(4)
                assertEquals(5, replyHeader[0].toInt())
                assertEquals(0, replyHeader[1].toInt())
                val addressSize = if (replyHeader[3].toInt() == 1) 4 else 16
                input.readExact(addressSize + 2)

                val payload = "hello-pakomo".toByteArray()
                output.write(payload)
                output.flush()
                assertArrayEquals(payload, input.readExact(payload.size))
            }
            assertTrue(protected.get())
        } finally {
            socks.close()
            echo.close()
            echoThread.join(2_000)
        }
    }

    @Test
    fun udpAssociateRelaysDatagramThroughProtectedSocket() {
        val echo = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val echoThread = thread(start = true, isDaemon = true) {
            val buffer = ByteArray(256)
            val packet = DatagramPacket(buffer, buffer.size)
            echo.receive(packet)
            echo.send(DatagramPacket(packet.data, packet.length, packet.socketAddress))
        }
        val protected = AtomicBoolean(false)
        val socks = Socks5Server(
            credentials = SocksCredentials("pakomo", "test-token"),
            protector = object : SocketProtector {
                override fun protect(socket: Socket): Boolean = true

                override fun protect(socket: DatagramSocket): Boolean {
                    protected.set(true)
                    return true
                }
            },
            shaper = TrafficShaper(defaultRules.first { it.id == "normal" }),
        )

        try {
            Socket(InetAddress.getLoopbackAddress(), socks.start()).use { control ->
                val input = control.getInputStream()
                val output = control.getOutputStream()
                output.write(byteArrayOf(5, 1, 2))
                assertArrayEquals(byteArrayOf(5, 2), input.readExact(2))
                output.write(
                    byteArrayOf(1, 6) +
                        "pakomo".toByteArray() +
                        byteArrayOf(10) +
                        "test-token".toByteArray(),
                )
                assertArrayEquals(byteArrayOf(1, 0), input.readExact(2))
                output.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))

                val reply = input.readExact(10)
                assertEquals(0, reply[1].toInt())
                val relayPort = ((reply[8].toInt() and 0xFF) shl 8) or
                    (reply[9].toInt() and 0xFF)
                val payload = "dns-like-payload".toByteArray()
                val echoPort = echo.localPort
                val wrapped = byteArrayOf(
                    0,
                    0,
                    0,
                    1,
                    127,
                    0,
                    0,
                    1,
                    (echoPort ushr 8).toByte(),
                    echoPort.toByte(),
                ) + payload
                DatagramSocket(0, InetAddress.getLoopbackAddress()).use { udpClient ->
                    udpClient.soTimeout = 3_000
                    udpClient.send(
                        DatagramPacket(
                            wrapped,
                            wrapped.size,
                            InetAddress.getLoopbackAddress(),
                            relayPort,
                        ),
                    )
                    val responseBytes = ByteArray(512)
                    val response = DatagramPacket(responseBytes, responseBytes.size)
                    udpClient.receive(response)
                    assertArrayEquals(
                        payload,
                        response.data.copyOfRange(10, response.length),
                    )
                }
            }
            assertTrue(protected.get())
        } finally {
            socks.close()
            echo.close()
            echoThread.join(2_000)
        }
    }

    @Test
    fun manyIdleUdpAssociationsDoNotStarveNewSessions() {
        val associationCount = 32
        val echo = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val echoThread = thread(start = true, isDaemon = true) {
            val buffer = ByteArray(256)
            while (!echo.isClosed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    echo.receive(packet)
                    echo.send(DatagramPacket(packet.data, packet.length, packet.socketAddress))
                } catch (_: Exception) {
                    break
                }
            }
        }
        val socks = Socks5Server(
            credentials = SocksCredentials("pakomo", "test-token"),
            protector = object : SocketProtector {
                override fun protect(socket: Socket): Boolean = true
                override fun protect(socket: DatagramSocket): Boolean = true
            },
            shaper = TrafficShaper(defaultRules.first { it.id == "normal" }),
        )
        val controls = mutableListOf<Socket>()
        val udpClients = mutableListOf<DatagramSocket>()

        try {
            val socksPort = socks.start()
            repeat(associationCount) { index ->
                val control = Socket(InetAddress.getLoopbackAddress(), socksPort).apply {
                    soTimeout = 3_000
                }
                controls += control
                val relay = openUdpAssociation(control)
                val udpClient = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply {
                    soTimeout = 3_000
                }
                udpClients += udpClient

                val payload = "udp-flow-$index".toByteArray()
                val wrapped = wrapIpv4UdpRequest(echo.localPort, payload)
                udpClient.send(DatagramPacket(wrapped, wrapped.size, relay))
                val response = DatagramPacket(ByteArray(512), 512)
                udpClient.receive(response)
                assertArrayEquals(payload, response.data.copyOfRange(10, response.length))
            }
            assertEquals(associationCount, socks.activeSessionCount())
        } finally {
            udpClients.forEach { it.close() }
            controls.forEach { it.close() }
            socks.close()
            echo.close()
            echoThread.join(2_000)
        }
    }

    @Test
    fun offlineRuleRejectsTcpConnectBeforeOpeningOutboundSocket() {
        val protected = AtomicBoolean(false)
        val socks = Socks5Server(
            credentials = SocksCredentials("pakomo", "test-token"),
            protector = object : SocketProtector {
                override fun protect(socket: Socket): Boolean {
                    protected.set(true)
                    return true
                }

                override fun protect(socket: DatagramSocket): Boolean = true
            },
            shaper = TrafficShaper(defaultRules.first { it.id == "offline" }),
        )

        try {
            Socket(InetAddress.getLoopbackAddress(), socks.start()).use { client ->
                client.soTimeout = 3_000
                authenticate(client)
                val output = client.getOutputStream()
                output.write(
                    byteArrayOf(5, 1, 0, 1, 1, 1, 1, 1, 0, 80),
                )
                output.flush()
                val reply = client.getInputStream().readExact(10)
                assertEquals(3, reply[1].toInt())
            }
            assertFalse(protected.get())
        } finally {
            socks.close()
        }
    }

    @Test
    fun manyConcurrentLongLivedConnectionsRelayWithoutStalling() {
        // Multi-connection echo server that keeps echoing on every open connection.
        val echo = ServerSocket(0, 256, InetAddress.getLoopbackAddress())
        val acceptor = thread(start = true, isDaemon = true) {
            while (!echo.isClosed) {
                val conn = try { echo.accept() } catch (_: Exception) { break }
                thread(start = true, isDaemon = true) {
                    val buffer = ByteArray(64)
                    try {
                        while (true) {
                            val n = conn.getInputStream().read(buffer)
                            if (n < 0) break
                            conn.getOutputStream().write(buffer, 0, n)
                            conn.getOutputStream().flush()
                        }
                    } catch (_: Exception) {
                    } finally {
                        runCatching { conn.close() }
                    }
                }
            }
        }
        val socks = Socks5Server(
            credentials = SocksCredentials("pakomo", "test-token"),
            protector = object : SocketProtector {
                override fun protect(socket: Socket): Boolean = true
                override fun protect(socket: DatagramSocket): Boolean = true
            },
            shaper = TrafficShaper(defaultRules.first { it.id == "normal" }),
        )

        // 2x the default Dispatchers.IO pool: the old blocking relay (two parked threads per
        // flow) would exhaust the pool and most of these would never receive their echo.
        val connectionCount = 128
        val clients = mutableListOf<Socket>()
        try {
            val socksPort = socks.start()
            repeat(connectionCount) {
                val client = Socket(InetAddress.getLoopbackAddress(), socksPort)
                authenticate(client)
                val port = echo.localPort
                client.getOutputStream().write(
                    byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, (port ushr 8).toByte(), port.toByte()),
                )
                client.getOutputStream().flush()
                val header = client.getInputStream().readExact(4)
                assertEquals(0, header[1].toInt())
                val addressSize = if (header[3].toInt() == 1) 4 else 16
                client.getInputStream().readExact(addressSize + 2)
                clients.add(client)
            }

            val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
            val workers = clients.mapIndexed { index, client ->
                thread(start = true, isDaemon = true) {
                    try {
                        val payload = "msg-$index".toByteArray()
                        client.getOutputStream().write(payload)
                        client.getOutputStream().flush()
                        val echoed = client.getInputStream().readExact(payload.size)
                        if (!echoed.contentEquals(payload)) errors.add("mismatch @$index")
                    } catch (error: Exception) {
                        errors.add("conn $index: ${error.message}")
                    }
                }
            }
            val deadline = System.currentTimeMillis() + 15_000
            workers.forEach { worker ->
                val remaining = deadline - System.currentTimeMillis()
                if (remaining > 0) worker.join(remaining)
            }
            val stalled = workers.count { it.isAlive }
            assertEquals("connections stalled without echo (pool exhaustion): $stalled", 0, stalled)
            assertTrue("relay errors: ${errors.take(5)}", errors.isEmpty())
        } finally {
            clients.forEach { runCatching { it.close() } }
            socks.close()
            echo.close()
            acceptor.join(2_000)
        }
    }

    private fun openUdpAssociation(control: Socket): InetSocketAddress {
        authenticate(control)
        control.getOutputStream().run {
            write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
            flush()
        }
        val reply = control.getInputStream().readExact(10)
        assertEquals(0, reply[1].toInt())
        val port = ((reply[8].toInt() and 0xFF) shl 8) or
            (reply[9].toInt() and 0xFF)
        return InetSocketAddress(InetAddress.getLoopbackAddress(), port)
    }

    private fun authenticate(client: Socket) {
        val input = client.getInputStream()
        val output = client.getOutputStream()
        output.write(byteArrayOf(5, 1, 2))
        assertArrayEquals(byteArrayOf(5, 2), input.readExact(2))
        output.write(
            byteArrayOf(1, 6) +
                "pakomo".toByteArray() +
                byteArrayOf(10) +
                "test-token".toByteArray(),
        )
        output.flush()
        assertArrayEquals(byteArrayOf(1, 0), input.readExact(2))
    }

    private fun wrapIpv4UdpRequest(port: Int, payload: ByteArray): ByteArray =
        byteArrayOf(
            0,
            0,
            0,
            1,
            127,
            0,
            0,
            1,
            (port ushr 8).toByte(),
            port.toByte(),
        ) + payload

    private fun java.io.InputStream.readExact(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = read(bytes, offset, size - offset)
            check(count > 0)
            offset += count
        }
        return bytes
    }
}
