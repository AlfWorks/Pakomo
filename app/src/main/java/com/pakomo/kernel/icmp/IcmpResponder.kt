package com.pakomo.kernel.icmp

import com.pakomo.kernel.ip.Checksum
import com.pakomo.kernel.ip.Ipv4Packet
import com.pakomo.kernel.ip.Ipv4Packet.Companion.writeInt16

object IcmpResponder {
    private const val ICMP_ECHO_REQUEST = 8
    private const val ICMP_ECHO_REPLY = 0

    fun respond(packet: Ipv4Packet): ByteArray? {
        if (packet.protocol != Ipv4Packet.PROTOCOL_ICMP) return null
        val payload = packet.payload
        if (payload.size < 8) return null
        val icmpType = payload[0].toInt() and 0xFF
        if (icmpType != ICMP_ECHO_REQUEST) return null
        val replyPayload = payload.copyOf()
        replyPayload[0] = ICMP_ECHO_REPLY.toByte()
        replyPayload[2] = 0
        replyPayload[3] = 0
        val icmpCsum = Checksum.complement(Checksum.sum(replyPayload, 0, replyPayload.size))
        writeInt16(replyPayload, 2, icmpCsum)
        val header = Ipv4Packet.buildHeader(
            protocol = Ipv4Packet.PROTOCOL_ICMP,
            sourceAddress = packet.destinationAddress,
            destinationAddress = packet.sourceAddress,
            payloadLength = replyPayload.size,
            identification = packet.identification,
            ttl = 64,
        )
        return header + replyPayload
    }
}
