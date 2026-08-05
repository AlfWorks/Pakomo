package com.alphynia.pakomo.kernel.ip

import java.nio.ByteBuffer

data class Ipv4Packet(
    val version: Int, val ihl: Int, val dscpEcn: Int, val totalLength: Int,
    val identification: Int, val flags: Int, val fragmentOffset: Int,
    val ttl: Int, val protocol: Int,
    val sourceAddress: Int, val destinationAddress: Int,
    val payload: ByteArray,
) {
    companion object {
        const val MIN_HEADER_BYTES = 20
        const val PROTOCOL_ICMP = 1
        const val PROTOCOL_TCP = 6
        const val PROTOCOL_UDP = 17

        fun parse(raw: ByteArray, offset: Int = 0, length: Int = raw.size - offset): Ipv4Packet? {
            if (length < MIN_HEADER_BYTES) return null
            val b0 = raw[offset].toInt() and 0xFF
            val version = b0 shr 4
            if (version != 4) return null
            val ihl = b0 and 0x0F
            val headerBytes = ihl * 4
            if (length < headerBytes) return null
            val totalLength = ((raw[offset + 2].toInt() and 0xFF) shl 8) or (raw[offset + 3].toInt() and 0xFF)
            val actualEnd = offset + totalLength.coerceAtMost(length)
            val payloadLen = (actualEnd - offset - headerBytes).coerceAtLeast(0)
            val payload = if (payloadLen > 0) raw.copyOfRange(offset + headerBytes, actualEnd) else ByteArray(0)
            return Ipv4Packet(
                version = version, ihl = ihl,
                dscpEcn = raw[offset + 1].toInt() and 0xFF, totalLength = totalLength,
                identification = ((raw[offset + 4].toInt() and 0xFF) shl 8) or (raw[offset + 5].toInt() and 0xFF),
                flags = (raw[offset + 6].toInt() and 0xFF) shr 5,
                fragmentOffset = (((raw[offset + 6].toInt() and 0xFF) and 0x1F) shl 8) or (raw[offset + 7].toInt() and 0xFF),
                ttl = raw[offset + 8].toInt() and 0xFF, protocol = raw[offset + 9].toInt() and 0xFF,
                sourceAddress = readInt32(raw, offset + 12),
                destinationAddress = readInt32(raw, offset + 16),
                payload = payload,
            )
        }

        fun readInt32(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

        fun readInt16(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

        fun writeInt32(buf: ByteArray, offset: Int, value: Int) {
            buf[offset] = (value ushr 24).toByte()
            buf[offset + 1] = (value ushr 16).toByte()
            buf[offset + 2] = (value ushr 8).toByte()
            buf[offset + 3] = value.toByte()
        }

        fun writeInt16(buf: ByteArray, offset: Int, value: Int) {
            buf[offset] = (value ushr 8).toByte()
            buf[offset + 1] = value.toByte()
        }

        fun buildHeader(
            protocol: Int, sourceAddress: Int, destinationAddress: Int, payloadLength: Int,
            identification: Int = 0, ttl: Int = 64, dscpEcn: Int = 0,
        ): ByteArray {
            val totalLen = MIN_HEADER_BYTES + payloadLength
            val buf = ByteBuffer.allocate(MIN_HEADER_BYTES)
            buf.put(0x45.toByte()); buf.put(dscpEcn.toByte()); buf.putShort(totalLen.toShort())
            buf.putShort(identification.toShort()); buf.putShort(0.toShort())
            buf.put(ttl.toByte()); buf.put(protocol.toByte()); buf.putShort(0.toShort())
            buf.putInt(sourceAddress); buf.putInt(destinationAddress)
            val header = buf.array()
            val csum = Checksum.complement(Checksum.sum(header, 0, MIN_HEADER_BYTES))
            header[10] = (csum ushr 8).toByte(); header[11] = csum.toByte()
            return header
        }
    }
}
