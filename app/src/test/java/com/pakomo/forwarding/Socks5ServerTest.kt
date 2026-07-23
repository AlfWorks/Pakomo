package com.pakomo.forwarding

import com.pakomo.core.model.defaultRules
import com.pakomo.shaping.TrafficShaper
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
