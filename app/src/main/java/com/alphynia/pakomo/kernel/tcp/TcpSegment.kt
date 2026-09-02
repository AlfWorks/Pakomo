package com.alphynia.pakomo.kernel.tcp

import com.alphynia.pakomo.kernel.ip.Checksum
import com.alphynia.pakomo.kernel.ip.Ipv4Packet
import com.alphynia.pakomo.kernel.ip.Ipv4Packet.Companion.readInt16
import com.alphynia.pakomo.kernel.ip.Ipv4Packet.Companion.readInt32
import com.alphynia.pakomo.kernel.ip.Ipv4Packet.Companion.writeInt16
import com.alphynia.pakomo.kernel.ip.Ipv4Packet.Companion.writeInt32

data class TcpSegment(
    val sourcePort: Int, val destinationPort: Int,
    val sequenceNumber: Long, val acknowledgmentNumber: Long,
    val dataOffset: Int, val flags: Int,
    val windowSize: Int, val urgentPointer: Int,
    val payload: ByteArray,
) {
    val isSyn: Boolean get() = (flags and FLAG_SYN) != 0
    val isAck: Boolean get() = (flags and FLAG_ACK) != 0
    val isFin: Boolean get() = (flags and FLAG_FIN) != 0
    val isRst: Boolean get() = (flags and FLAG_RST) != 0
    val isPsh: Boolean get() = (flags and FLAG_PSH) != 0

    companion object {
        const val MIN_HEADER_BYTES = 20
        const val FLAG_FIN = 0x01; const val FLAG_SYN = 0x02
        const val FLAG_RST = 0x04; const val FLAG_PSH = 0x08
        const val FLAG_ACK = 0x10; const val FLAG_URG = 0x20

        /** Shared zero-length payload/options; avoids per-call `ByteArray(0)` allocation on the hot path. */
        val EMPTY = ByteArray(0)

        fun parse(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): TcpSegment? {
            if (length < MIN_HEADER_BYTES) return null
            val srcPort = readInt16(data, offset)
            val dstPort = readInt16(data, offset + 2)
            val seqNum = readInt32(data, offset + 4).toLong() and 0xFFFF_FFFFL
            val ackNum = readInt32(data, offset + 8).toLong() and 0xFFFF_FFFFL
            val dataOffRaw = (data[offset + 12].toInt() and 0xFF) shr 4
            val headerBytes = dataOffRaw * 4
            if (headerBytes < MIN_HEADER_BYTES || length < headerBytes) return null
            val flags = data[offset + 13].toInt() and 0xFF
            val window = readInt16(data, offset + 14)
            val urgent = readInt16(data, offset + 18)
            val payloadLen = (length - headerBytes).coerceAtLeast(0)
            val payload = if (payloadLen > 0) data.copyOfRange(offset + headerBytes, offset + length) else ByteArray(0)
            return TcpSegment(srcPort, dstPort, seqNum, ackNum, dataOffRaw, flags, window, urgent, payload)
        }

        fun build(
            sourcePort: Int, destinationPort: Int,
            sequenceNumber: Long, acknowledgmentNumber: Long,
            flags: Int, windowSize: Int,
            sourceAddress: Int, destinationAddress: Int,
            payload: ByteArray = ByteArray(0), urgentPointer: Int = 0,
            options: ByteArray = ByteArray(0),
        ): ByteArray {
            // options length must be a multiple of 4 (caller pads with NOPs); data offset counts them.
            val headerLen = MIN_HEADER_BYTES + options.size
            val total = headerLen + payload.size
            val buf = ByteArray(total)
            writeInt16(buf, 0, sourcePort); writeInt16(buf, 2, destinationPort)
            writeInt32(buf, 4, sequenceNumber.toInt()); writeInt32(buf, 8, acknowledgmentNumber.toInt())
            buf[12] = ((headerLen / 4) shl 4).toByte(); buf[13] = flags.toByte()
            writeInt16(buf, 14, windowSize); writeInt16(buf, 16, 0); writeInt16(buf, 18, urgentPointer)
            if (options.isNotEmpty()) System.arraycopy(options, 0, buf, MIN_HEADER_BYTES, options.size)
            if (payload.isNotEmpty()) System.arraycopy(payload, 0, buf, headerLen, payload.size)
            val csum = computeChecksum(buf, total, sourceAddress, destinationAddress)
            writeInt16(buf, 16, csum)
            return buf
        }

        /**
         * Builds a complete IPv4 + TCP packet into a single buffer. The split path
         * ([build] + [Ipv4Packet.buildHeader] + array concatenation) allocated three arrays per
         * outbound segment; on a fast download that per-segment garbage drove GC churn in the
         * datapath. This writes the IP header, TCP header (+options) and payload into one
         * allocation and fills in both checksums in place. Layout: `[0,20)` IPv4 header,
         * `[20, 20+tcpHeaderLen)` TCP header (+options), then the payload.
         */
        fun buildIpv4Packet(
            sourcePort: Int, destinationPort: Int,
            sequenceNumber: Long, acknowledgmentNumber: Long,
            flags: Int, windowSize: Int,
            sourceAddress: Int, destinationAddress: Int,
            payload: ByteArray = EMPTY, urgentPointer: Int = 0,
            options: ByteArray = EMPTY,
            ttl: Int = 64, identification: Int = 0, dscpEcn: Int = 0,
        ): ByteArray {
            val ipLen = Ipv4Packet.MIN_HEADER_BYTES
            val tcpHeaderLen = MIN_HEADER_BYTES + options.size
            val tcpLen = tcpHeaderLen + payload.size
            val total = ipLen + tcpLen
            val buf = ByteArray(total)

            // IPv4 header [0, 20)
            buf[0] = 0x45.toByte()
            buf[1] = dscpEcn.toByte()
            writeInt16(buf, 2, total)             // total length (whole IP packet)
            writeInt16(buf, 4, identification)
            writeInt16(buf, 6, 0)                 // flags + fragment offset
            buf[8] = ttl.toByte()
            buf[9] = Ipv4Packet.PROTOCOL_TCP.toByte()
            writeInt16(buf, 10, 0)                // header checksum (filled below)
            writeInt32(buf, 12, sourceAddress)
            writeInt32(buf, 16, destinationAddress)
            val ipCsum = Checksum.complement(Checksum.sum(buf, 0, ipLen))
            writeInt16(buf, 10, ipCsum)

            // TCP header + payload [20, total)
            writeInt16(buf, ipLen, sourcePort); writeInt16(buf, ipLen + 2, destinationPort)
            writeInt32(buf, ipLen + 4, sequenceNumber.toInt())
            writeInt32(buf, ipLen + 8, acknowledgmentNumber.toInt())
            buf[ipLen + 12] = ((tcpHeaderLen / 4) shl 4).toByte(); buf[ipLen + 13] = flags.toByte()
            writeInt16(buf, ipLen + 14, windowSize)
            writeInt16(buf, ipLen + 16, 0)        // TCP checksum (filled below)
            writeInt16(buf, ipLen + 18, urgentPointer)
            if (options.isNotEmpty()) System.arraycopy(options, 0, buf, ipLen + MIN_HEADER_BYTES, options.size)
            if (payload.isNotEmpty()) System.arraycopy(payload, 0, buf, ipLen + tcpHeaderLen, payload.size)
            // TCP checksum over the pseudo-header + TCP segment bytes [ipLen, ipLen + tcpLen)
            var sum = 0
            sum += (sourceAddress ushr 16) and 0xFFFF; sum += sourceAddress and 0xFFFF
            sum += (destinationAddress ushr 16) and 0xFFFF; sum += destinationAddress and 0xFFFF
            sum += Ipv4Packet.PROTOCOL_TCP; sum += tcpLen
            sum += Checksum.sum(buf, ipLen, tcpLen)
            writeInt16(buf, ipLen + 16, Checksum.complement(Checksum.fold(sum)))
            return buf
        }

        fun computeChecksum(tcpSegment: ByteArray, tcpLength: Int, sourceAddress: Int, destinationAddress: Int): Int {
            var sum = 0
            sum += (sourceAddress ushr 16) and 0xFFFF; sum += sourceAddress and 0xFFFF
            sum += (destinationAddress ushr 16) and 0xFFFF; sum += destinationAddress and 0xFFFF
            sum += 6; sum += tcpLength
            sum += Checksum.sum(tcpSegment, 0, tcpLength)
            return Checksum.complement(Checksum.fold(sum))
        }
    }
}
