package com.pakomo.kernel.udp

import android.util.Log
import com.pakomo.kernel.ip.Checksum
import com.pakomo.kernel.ip.Ipv4Packet
import com.pakomo.kernel.ip.Ipv4Packet.Companion.writeInt16
import com.pakomo.kernel.socks.Socks5Client
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

class UdpSession(
    val sourceAddress: Int,
    val sourcePort: Int,
    val destinationAddress: Int,
    val destinationPort: Int,
    private val onSendPacket: suspend (ByteArray) -> Unit,
    private val socksClient: Socks5Client,
    private val sessionScope: CoroutineScope,
    private val idleTimeoutMs: Long,
    private val onClosed: () -> Unit,
) {
    @Volatile var closed = false
        private set

    private val inputChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private var association: Socks5Client.UdpAssociation? = null
    private var relayChannel: DatagramChannel? = null
    private var relayJob: Job? = null

    fun enqueue(payload: ByteArray) {
        inputChannel.trySend(payload)
    }

    fun startActor() {
        sessionScope.launch {
            try {
                val assoc = withContext(Dispatchers.IO) {
                    socksClient.udpAssociate(sourceAddress, sourcePort, destinationAddress, destinationPort)
                }
                if (assoc == null) {
                    Log.w(TAG, "UDP ASSOCIATE failed $sourcePort:$destinationPort")
                    close()
                    return@launch
                }
                association = assoc
                relayChannel = DatagramChannel.open().apply {
                    configureBlocking(false)
                    bind(null)
                }
                relayJob = launch {
                    val buf = ByteBuffer.allocate(65535)
                    while (isActive && !closed) {
                        buf.clear()
                        val sourceAddr = try {
                            withContext(Dispatchers.IO) { relayChannel!!.receive(buf) }
                        } catch (_: Exception) { null }
                        if (sourceAddr != null) {
                            buf.flip()
                            val raw = ByteArray(buf.remaining())
                            buf.get(raw)
                            val payload = extractUdpPayload(raw)
                            if (payload != null) sendToTun(sourceAddr as? java.net.InetSocketAddress ?: return@launch, payload)
                        }
                        if (relayChannel == null || !relayChannel!!.isOpen) break
                    }
                }
                var lastActivity = System.nanoTime()
                while (isActive && !closed) {
                    val result = try {
                        withTimeout(IDLE_POLL_MS) { inputChannel.receive() }
                    } catch (_: TimeoutCancellationException) { null }
                    if (result != null) {
                        lastActivity = System.nanoTime()
                        sendToRelay(assoc, result)
                    } else if (System.nanoTime() - lastActivity > idleTimeoutMs * 1_000_000L) {
                        break
                    }
                }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                Log.w(TAG, "UDP session failed $sourcePort", e)
            } finally {
                close()
            }
        }
    }

    private suspend fun sendToRelay(assoc: Socks5Client.UdpAssociation, payload: ByteArray) {
        try {
            
            val udpBuf = ByteBuffer.allocate(4 + 4 + 2 + payload.size)
            udpBuf.putShort(0)
            udpBuf.put(0)
            udpBuf.put(1)
            udpBuf.putInt(destinationAddress)
            udpBuf.putShort(destinationPort.toShort())
            udpBuf.put(payload)
            withContext(Dispatchers.IO) {
                relayChannel!!.send(udpBuf.flip() as ByteBuffer, assoc.relayAddress)
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun sendToTun(source: InetSocketAddress, payload: ByteArray) {
        val udpLen = 8 + payload.size
        val udpHeader = ByteArray(8)
        // Return path is the mirror of the app's query: source port = the destination the app
        // queried (e.g. 53), dest port = the app's original source port. `source` here is the
        // local relay channel's ephemeral address, not the real server, so it must NOT be used.
        writeInt16(udpHeader, 0, destinationPort)
        writeInt16(udpHeader, 2, sourcePort)
        writeInt16(udpHeader, 4, udpLen)
        writeInt16(udpHeader, 6, 0)
        val udpDatagram = udpHeader + payload
        var csum = (destinationAddress ushr 16) and 0xFFFF
        csum += destinationAddress and 0xFFFF
        csum += (sourceAddress ushr 16) and 0xFFFF
        csum += sourceAddress and 0xFFFF
        csum += 17
        csum += udpLen
        csum += Checksum.sum(udpDatagram, 0, udpLen)
        writeInt16(udpHeader, 6, Checksum.complement(Checksum.fold(csum)))
        val header = Ipv4Packet.buildHeader(
            protocol = Ipv4Packet.PROTOCOL_UDP,
            sourceAddress = destinationAddress,
            destinationAddress = sourceAddress,
            payloadLength = udpDatagram.size,
        )
        onSendPacket(header + udpDatagram)
    }

    private fun extractUdpPayload(socks5Udp: ByteArray): ByteArray? {
        if (socks5Udp.size < 10) return null
        val addrType = socks5Udp[3].toInt() and 0xFF
        val hdrLen = when (addrType) {
            1 -> 4 + 4 + 2
            4 -> 4 + 16 + 2
            else -> return null
        }
        if (socks5Udp.size < hdrLen) return null
        return socks5Udp.copyOfRange(hdrLen, socks5Udp.size)
    }

    fun close() {
        if (closed) return
        closed = true
        relayJob?.cancel()
        inputChannel.close()
        runCatching { relayChannel?.close() }
        runCatching { association?.controlSocket?.close() }
        onClosed()
    }

    companion object {
        private const val IDLE_POLL_MS = 500L
        private const val TAG = "PakomoUdp"
    }
}
