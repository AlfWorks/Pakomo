package com.alphynia.pakomo.kernel.socks

import com.alphynia.pakomo.forwarding.ConnectionSetupRegistry
import com.alphynia.pakomo.kernel.ip.Ipv4Packet.Companion.readInt16
import com.alphynia.pakomo.kernel.ip.Ipv4Packet.Companion.writeInt16
import com.alphynia.pakomo.kernel.ip.Ipv4Packet.Companion.writeInt32
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import com.alphynia.pakomo.kernel.KernelDispatchers
import kotlinx.coroutines.withContext

/**
 * Minimal SOCKS5 client for the tun2socks pipeline.
 *
 * Writes the Pakomo attribution preamble before the SOCKS5 greeting so the
 * existing Socks5Server (expectOriginPreamble=true) can attribute the connection.
 */
class Socks5Client(
    private val socksAddress: String,
    private val socksPort: Int,
    private val username: String,
    private val password: String,
    private val connectTimeoutMs: Int,
) {
    // --- TCP CONNECT ---

    data class TcpConnection(val sock: Socket,
        val input: InputStream,
        val output: OutputStream,
    )

    /**
     * Full SOCKS5 TCP CONNECT with preamble.
     * 1. Connect to SOCKS5 server
     * 2. Write 20-byte Pakomo preamble
     * 3. SOCKS5 auth handshake
     * 4. CONNECT to [destAddr]:[destPort]
     * Returns the connected SocketChannel ready for data relay.
     */
    suspend fun tcpConnect(
        sourceAddress: Int,
        sourcePort: Int,
        destinationAddress: Int,
        destinationPort: Int,
        synArrivalNanos: Long = 0L,
    ): TcpConnection? = withContext(KernelDispatchers.connIo) {
        try {
            val sock = Socket()
            // Large loopback buffers (set before connect so the receive window scales) let the local
            // SOCKS server stream ahead instead of stalling on a small buffer — lifts per-connection
            // download throughput.
            runCatching { sock.receiveBufferSize = 1024 * 1024 }
            runCatching { sock.sendBufferSize = 512 * 1024 }
            sock.connect(
                InetSocketAddress(socksAddress, socksPort),
                connectTimeoutMs,
            )
            sock.tcpNoDelay = true
            val input = sock.getInputStream()
            val output = sock.getOutputStream()

            // Hand this connection's SYN-arrival time to the SOCKS server (keyed by our loopback
            // port) BEFORE the preamble, so latency compensation can include the tun2socks setup.
            // The server takes it after reading the preamble — the write→read edge orders us first.
            if (synArrivalNanos != 0L) {
                ConnectionSetupRegistry.record(sock.localPort, synArrivalNanos)
            }

            // 1. Write preamble
            output.write(buildPreamble(
                protocol = 6, // TCP
                sourceAddress = sourceAddress,
                sourcePort = sourcePort,
                destinationAddress = destinationAddress,
                destinationPort = destinationPort,
            ))
            output.flush()

            // 2. Method negotiation
            output.write(byteArrayOf(0x05, 0x01, 0x02))
            output.flush()
            val methodResponse = input.readExact(2)
            if (methodResponse[0].toInt() != 0x05 || methodResponse[1].toInt() != 0x02) {
                runCatching { sock.close() }
                return@withContext null
            }

            // 3. Auth
            val userBytes = username.toByteArray(StandardCharsets.UTF_8)
            val passBytes = password.toByteArray(StandardCharsets.UTF_8)
            val authMsg = ByteBuffer.allocate(3 + userBytes.size + passBytes.size)
            authMsg.put(0x01.toByte())
            authMsg.put(userBytes.size.toByte())
            authMsg.put(userBytes)
            authMsg.put(passBytes.size.toByte())
            authMsg.put(passBytes)
            output.write(authMsg.array())
            output.flush()
            val authResponse = input.readExact(2)
            if (authResponse[0].toInt() != 0x01 || authResponse[1].toInt() != 0x00) {
                runCatching { sock.close() }
                return@withContext null
            }

            // 4. CONNECT request
            val connectReq = ByteBuffer.allocate(10)
            connectReq.put(0x05.toByte()) // version
            connectReq.put(0x01.toByte()) // CONNECT
            connectReq.put(0x00.toByte()) // reserved
            connectReq.put(0x01.toByte()) // IPv4
            connectReq.putInt(destinationAddress)
            connectReq.putShort(destinationPort.toShort())
            output.write(connectReq.array())
            output.flush()

            // Read reply (min 10 bytes)
            val reply = input.readExact(10)
            if (reply[0].toInt() != 0x05 || reply[1].toInt() != 0x00) {
                runCatching { sock.close() }
                return@withContext null
            }

            TcpConnection(sock, input, output)
        } catch (_: Exception) {
            null
        }
    }

    // --- UDP ASSOCIATE ---

    data class UdpAssociation(
        val controlSocket: Socket,
        val controlInput: InputStream,
        val controlOutput: OutputStream,
        val relayAddress: InetSocketAddress,  // where to send UDP datagrams
    )

    /**
     * Full SOCKS5 UDP ASSOCIATE with preamble.
     * Returns the control channel + relay address.
     */
    suspend fun udpAssociate(sourceAddress: Int, sourcePort: Int, destinationAddress: Int = 0, destinationPort: Int = 0): UdpAssociation? = withContext(KernelDispatchers.connIo) {
        try {
            val sock = Socket()
            sock.connect(
                InetSocketAddress(socksAddress, socksPort),
                connectTimeoutMs,
            )
            val input = sock.getInputStream()
            val output = sock.getOutputStream()

            // 1. Preamble (UDP)
            output.write(buildPreamble(
                protocol = 17, // UDP
                sourceAddress = sourceAddress,
                sourcePort = sourcePort,
                destinationAddress = destinationAddress, destinationPort = destinationPort,
            ))
            output.flush()

            // 2. Method negotiation
            output.write(byteArrayOf(0x05, 0x01, 0x02))
            output.flush()
            val methodResponse = input.readExact(2)
            if (methodResponse[0].toInt() != 0x05 || methodResponse[1].toInt() != 0x02) {
                runCatching { sock.close() }
                return@withContext null
            }

            // 3. Auth
            val userBytes = username.toByteArray(StandardCharsets.UTF_8)
            val passBytes = password.toByteArray(StandardCharsets.UTF_8)
            val authMsg = ByteBuffer.allocate(3 + userBytes.size + passBytes.size)
            authMsg.put(0x01.toByte())
            authMsg.put(userBytes.size.toByte())
            authMsg.put(userBytes)
            authMsg.put(passBytes.size.toByte())
            authMsg.put(passBytes)
            output.write(authMsg.array())
            output.flush()
            val authResponse = input.readExact(2)
            if (authResponse[0].toInt() != 0x01 || authResponse[1].toInt() != 0x00) {
                runCatching { sock.close() }
                return@withContext null
            }

            // 4. UDP ASSOCIATE request
            val assocReq = ByteBuffer.allocate(10)
            assocReq.put(0x05.toByte()) // version
            assocReq.put(0x03.toByte()) // UDP ASSOCIATE
            assocReq.put(0x00.toByte()) // reserved
            assocReq.put(0x01.toByte()) // IPv4
            assocReq.putInt(0)          // 0.0.0.0
            assocReq.putShort(0)        // port 0
            output.write(assocReq.array())
            output.flush()

            // Read reply
            val reply = input.readExact(10)
            if (reply[0].toInt() != 0x05 || reply[1].toInt() != 0x00) {
                runCatching { sock.close() }
                return@withContext null
            }
            val addrType = reply[3].toInt() and 0xFF
            val bindAddr: InetAddress
            var offset = 4
            when (addrType) {
                1 -> { // IPv4
                    bindAddr = InetAddress.getByAddress(reply.copyOfRange(offset, offset + 4))
                    offset += 4
                }
                4 -> { // IPv6 (unlikely but handle)
                    bindAddr = InetAddress.getByAddress(reply.copyOfRange(offset, offset + 16))
                    offset += 16
                }
                else -> { runCatching { sock.close() }; return@withContext null }
            }
            val bindPort = readInt16(reply, offset)

            UdpAssociation(controlSocket = sock,
                controlInput = input,
                controlOutput = output,
                relayAddress = InetSocketAddress(bindAddr, bindPort),
            )
        } catch (_: Exception) {
            null
        }
    }

    // --- Preamble ---

    private fun buildPreamble(
        protocol: Int,
        sourceAddress: Int,
        sourcePort: Int,
        destinationAddress: Int,
        destinationPort: Int,
    ): ByteArray {
        val buf = ByteArray(20)
        buf[0] = 'P'.code.toByte()
        buf[1] = 'K'.code.toByte()
        buf[2] = 'M'.code.toByte()
        buf[3] = 'O'.code.toByte()
        buf[4] = 1 // version
        buf[5] = protocol.toByte()
        writeInt16(buf, 6, sourcePort)
        writeInt16(buf, 8, destinationPort)
        writeInt32(buf, 10, sourceAddress)
        writeInt32(buf, 14, destinationAddress)
        // bytes 18-19 remain 0
        return buf
    }

    private companion object {
        private fun InputStream.readExact(size: Int): ByteArray {
            val result = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val count = read(result, offset, size - offset)
                if (count < 0) throw java.io.EOFException()
                offset += count
            }
            return result
        }
    }
}
