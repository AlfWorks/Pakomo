package com.alphynia.pakomo.forwarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsMessageTest {

    /** Builds a minimal DNS A-query for [name]. */
    private fun query(name: String, id: Int = 0x1234, rd: Boolean = true): ByteArray {
        val header = byteArrayOf(
            (id ushr 8).toByte(), id.toByte(),
            if (rd) 0x01 else 0x00, 0x00, // flags: QR=0, RD
            0x00, 0x01,                   // QDCOUNT = 1
            0x00, 0x00,                   // ANCOUNT
            0x00, 0x00,                   // NSCOUNT
            0x00, 0x00,                   // ARCOUNT
        )
        val qname = buildList {
            name.split('.').forEach { label ->
                add(label.length.toByte())
                label.forEach { add(it.code.toByte()) }
            }
            add(0.toByte())
        }.toByteArray()
        val suffix = byteArrayOf(0x00, 0x01, 0x00, 0x01) // qtype A, qclass IN
        return header + qname + suffix
    }

    @Test
    fun parsesQueryName() {
        assertEquals("www.baidu.com", DnsMessage.queryName(query("www.baidu.com")))
    }

    @Test
    fun lowercasesQueryName() {
        assertEquals("api.example.com", DnsMessage.queryName(query("API.Example.COM")))
    }

    @Test
    fun rejectsResponseAsQuery() {
        val response = query("www.baidu.com").copyOf()
        response[2] = 0x81.toByte() // set QR bit
        assertNull(DnsMessage.queryName(response))
    }

    @Test
    fun rejectsTruncatedMessage() {
        assertNull(DnsMessage.queryName(byteArrayOf(0, 1, 2, 3)))
    }

    @Test
    fun buildsNxdomainResponsePreservingQuestion() {
        val q = query("www.baidu.com", id = 0xABCD)
        val response = DnsMessage.failureResponse(q, q.size, DnsMessage.RCODE_NXDOMAIN)
        assertNotNull(response)
        response!!
        // Same transaction id.
        assertEquals(0xAB, response[0].toInt() and 0xFF)
        assertEquals(0xCD, response[1].toInt() and 0xFF)
        // QR set, rcode = NXDOMAIN (3).
        assertTrue(response[2].toInt() and 0x80 != 0)
        assertEquals(DnsMessage.RCODE_NXDOMAIN, response[3].toInt() and 0x0F)
        // QDCOUNT preserved, ANCOUNT zero.
        assertEquals(1, ((response[4].toInt() and 0xFF) shl 8) or (response[5].toInt() and 0xFF))
        assertEquals(0, ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF))
        // The echoed question still resolves to the original name.
        assertEquals("www.baidu.com", DnsMessage.queryName(q))
    }

    @Test
    fun buildsServfailResponse() {
        val q = query("x.example.com")
        val response = DnsMessage.failureResponse(q, q.size, DnsMessage.RCODE_SERVFAIL)!!
        assertEquals(DnsMessage.RCODE_SERVFAIL, response[3].toInt() and 0x0F)
    }

    @Test
    fun buildsRefusedResponse() {
        val q = query("x.example.com")
        val response = DnsMessage.failureResponse(q, q.size, DnsMessage.RCODE_REFUSED)!!
        assertEquals(DnsMessage.RCODE_REFUSED, response[3].toInt() and 0x0F)
    }
}
