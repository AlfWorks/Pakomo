package com.alphynia.pakomo.kernel

import com.alphynia.pakomo.kernel.icmp.IcmpResponder
import com.alphynia.pakomo.kernel.ip.Checksum
import com.alphynia.pakomo.kernel.ip.Ipv4Packet
import com.alphynia.pakomo.kernel.tcp.TcpSegment
import com.alphynia.pakomo.kernel.tun.TunReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KernelUnitTest {

    @Test
    fun checksumBasic() {
        val data = byteArrayOf(0x45, 0x00, 0x00, 0x73, 0x00, 0x00, 0x40, 0x00, 0x40, 0x11)
        val sum = Checksum.sum(data, 0, data.size)
        assertEquals(0x3A7B, Checksum.complement(sum))
    }

    @Test
    fun checksumIncremental() {
        val old = 0x9667
        val oldField = 0x4000
        val newField = 0x3F00
        val result = Checksum.incremental(old, oldField, newField)
        assertTrue(result in 0..0xFFFF)
    }

    @Test
    fun ipv4ParseMinimal() {
        val header = Ipv4Packet.buildHeader(
            protocol = Ipv4Packet.PROTOCOL_TCP,
            sourceAddress = 0x0A4D0001,  // 10.77.0.1
            destinationAddress = 0x0A4D0002,  // 10.77.0.2
            payloadLength = 0,
        )
        assertEquals(20, header.size)
        val parsed = Ipv4Packet.parse(header)
        assertNotNull(parsed)
        assertEquals(4, parsed!!.version)
        assertEquals(Ipv4Packet.PROTOCOL_TCP, parsed.protocol)
        assertEquals(0x0A4D0001, parsed.sourceAddress)
        assertEquals(0x0A4D0002, parsed.destinationAddress)
    }

    @Test
    fun ipv4ParseWithPayload() {
        val payload = ByteArray(40) { it.toByte() }
        val header = Ipv4Packet.buildHeader(
            protocol = Ipv4Packet.PROTOCOL_UDP,
            sourceAddress = 0x7F000001,
            destinationAddress = 0x7F000002,
            payloadLength = payload.size,
        )
        val packet = header + payload
        val parsed = Ipv4Packet.parse(packet)
        assertNotNull(parsed)
        assertEquals(40, parsed!!.payload.size)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun ipv4RoundTrip() {
        val original = Ipv4Packet.buildHeader(
            protocol = Ipv4Packet.PROTOCOL_ICMP,
            sourceAddress = 0xC0A80001.toInt(),
            destinationAddress = 0xC0A80002.toInt(),
            payloadLength = 32,
            identification = 0x1234,
            ttl = 64,
        )
        val parsed = Ipv4Packet.parse(original)
        assertNotNull(parsed)
        assertEquals(0x1234, parsed!!.identification)
        assertEquals(64, parsed.ttl)
    }

    @Test
    fun tcpSegmentParseSyn() {
        val seg = TcpSegment.build(
            sourcePort = 12345,
            destinationPort = 443,
            sequenceNumber = 0x01020304L,
            acknowledgmentNumber = 0L,
            flags = TcpSegment.FLAG_SYN,
            windowSize = 65535,
            sourceAddress = 0x0A000001,
            destinationAddress = 0x0A000002,
        )
        assertTrue(seg.size >= TcpSegment.MIN_HEADER_BYTES)
        val parsed = TcpSegment.parse(seg)
        assertNotNull(parsed)
        assertTrue(parsed!!.isSyn)
        assertEquals(12345, parsed.sourcePort)
        assertEquals(443, parsed.destinationPort)
        assertEquals(0x01020304L, parsed.sequenceNumber)
    }

    @Test
    fun tcpSegmentRoundTrip() {
        val payload = ByteArray(100) { (it % 256).toByte() }
        val seg = TcpSegment.build(
            sourcePort = 8080,
            destinationPort = 54321,
            sequenceNumber = 0xFFFFFFFFL,
            acknowledgmentNumber = 0x12345678L,
            flags = TcpSegment.FLAG_ACK or TcpSegment.FLAG_PSH,
            windowSize = 4096,
            sourceAddress = 0x7F000001,
            destinationAddress = 0x7F000002,
            payload = payload,
        )
        val parsed = TcpSegment.parse(seg)
        assertNotNull(parsed)
        assertTrue(parsed!!.isAck)
        assertTrue(parsed.isPsh)
        assertEquals(8080, parsed.sourcePort)
        assertEquals(54321, parsed.destinationPort)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun icmpEchoReply() {
        val pingPayload = byteArrayOf(
            8, 0, 0, 0,  // type=8, code=0, checksum=0, id=0
            0, 1, 0, 1,  // seq
        ) + ByteArray(48) { 'A'.code.toByte() }
        val packet = Ipv4Packet(
            version = 4, ihl = 5, dscpEcn = 0, totalLength = 20 + pingPayload.size,
            identification = 0, flags = 0, fragmentOffset = 0, ttl = 64,
            protocol = Ipv4Packet.PROTOCOL_ICMP,
            sourceAddress = 0x0A000001, destinationAddress = 0x0A000002,
            payload = pingPayload,
        )
        val reply = IcmpResponder.respond(packet)
        assertNotNull(reply)
        val replyParsed = Ipv4Packet.parse(reply!!)
        assertNotNull(replyParsed)
        assertEquals(Ipv4Packet.PROTOCOL_ICMP, replyParsed!!.protocol)
        assertEquals(0x0A000002, replyParsed.sourceAddress)
        assertEquals(0x0A000001, replyParsed.destinationAddress)
        assertEquals(0, replyParsed.payload[0].toInt() and 0xFF)
    }

    @Test
    fun icmpNonEchoIgnored() {
        val payload = byteArrayOf(3, 0, 0, 0, 0, 0, 0, 0)
        val packet = Ipv4Packet(
            version = 4, ihl = 5, dscpEcn = 0, totalLength = 20 + payload.size,
            identification = 0, flags = 0, fragmentOffset = 0, ttl = 64,
            protocol = Ipv4Packet.PROTOCOL_ICMP,
            sourceAddress = 0x0A000001, destinationAddress = 0x0A000002,
            payload = payload,
        )
        assertNull(IcmpResponder.respond(packet))
    }

    @Test
    fun connectionKeys() {
        val key1 = TunReader.tcpKey(0x0A000001, 1234, 0x0A000002, 80)
        val key2 = TunReader.tcpKey(0x0A000001, 1234, 0x0A000002, 80)
        val key3 = TunReader.tcpKey(0x0A000001, 1234, 0x0A000002, 443)
        assertEquals(key1, key2)
        assertTrue(key1 != key3)

        val ukey1 = TunReader.udpKey(0x7F000001, 53, 0x7F000002, 12345)
        assertTrue(ukey1 != key1)
    }
}
