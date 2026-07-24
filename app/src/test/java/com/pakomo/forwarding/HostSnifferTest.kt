package com.pakomo.forwarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostSnifferTest {
    @Test
    fun `extracts SNI from a TLS ClientHello`() {
        val hello = tlsClientHelloWithSni("www.baidu.com")
        assertEquals("www.baidu.com", HostSniffer.extract(hello, hello.size))
    }

    @Test
    fun `reports the full TLS record size so callers can wait for it`() {
        val hello = tlsClientHelloWithSni("example.com")
        assertEquals(hello.size, HostSniffer.tlsRecordSize(hello, hello.size))
        // A partial buffer still reports the full expected size.
        assertEquals(hello.size, HostSniffer.tlsRecordSize(hello, 10))
    }

    @Test
    fun `extracts Host header from a plaintext HTTP request and strips the port`() {
        val request = "GET /path HTTP/1.1\r\nHost: www.baidu.com:8080\r\nAccept: */*\r\n\r\n"
            .toByteArray(Charsets.US_ASCII)
        assertEquals("www.baidu.com", HostSniffer.extract(request, request.size))
    }

    @Test
    fun `returns null for traffic that is neither TLS nor HTTP`() {
        val junk = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        assertNull(HostSniffer.extract(junk, junk.size))
    }

    private fun tlsClientHelloWithSni(host: String): ByteArray {
        val hostBytes = host.toByteArray(Charsets.US_ASCII)
        val sniEntry = byteArrayOf(0x00) + u16(hostBytes.size) + hostBytes
        val sniList = u16(sniEntry.size) + sniEntry
        val sniExtension = u16(0x0000) + u16(sniList.size) + sniList
        val body = byteArrayOf(0x03, 0x03) +
            ByteArray(32) +
            byteArrayOf(0x00) +
            u16(2) + byteArrayOf(0x00, 0x2f) +
            byteArrayOf(0x01, 0x00) +
            u16(sniExtension.size) + sniExtension
        val handshake = byteArrayOf(0x01) + u24(body.size) + body
        return byteArrayOf(0x16, 0x03, 0x01) + u16(handshake.size) + handshake
    }

    private fun u16(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())

    private fun u24(value: Int) =
        byteArrayOf((value shr 16).toByte(), (value shr 8).toByte(), value.toByte())
}
