package com.pakomo.forwarding

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainRoutingPolicyTest {
    @Test
    fun `matches configured domain and subdomains but never delays DNS itself`() {
        val policy = DomainRoutingPolicy(listOf("example.com"))

        assertTrue(policy.shouldShape("example.com", 443))
        assertTrue(policy.shouldShape("api.example.com.", 443))
        assertFalse(policy.shouldShape("notexample.com", 443))
        assertFalse(policy.shouldShape("example.com", 53))
    }

    @Test
    fun `learns target IPv4 address from DNS response until TTL expires`() {
        var now = 100L
        val policy = DomainRoutingPolicy(listOf("example.com")) { now }
        policy.observeDnsResponse(dnsAResponse("api.example.com", byteArrayOf(1, 2, 3, 4), ttl = 30))

        assertTrue(policy.shouldShape("1.2.3.4", 443))
        now = 131L
        assertFalse(policy.shouldShape("1.2.3.4", 443))
    }

    @Test
    fun `ignores DNS answers outside configured domains`() {
        val policy = DomainRoutingPolicy(listOf("example.com"))
        policy.observeDnsResponse(dnsAResponse("other.test", byteArrayOf(5, 6, 7, 8), ttl = 60))

        assertFalse(policy.shouldShape("5.6.7.8", 443))
    }

    private fun dnsAResponse(domain: String, address: ByteArray, ttl: Int): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte()))
        output.write(byteArrayOf(0, 1, 0, 1, 0, 0, 0, 0))
        domain.split('.').forEach { label ->
            output.write(label.length)
            output.write(label.toByteArray(Charsets.US_ASCII))
        }
        output.write(0)
        output.write(byteArrayOf(0, 1, 0, 1))
        output.write(byteArrayOf(0xC0.toByte(), 0x0C))
        output.write(byteArrayOf(0, 1, 0, 1))
        output.write(
            byteArrayOf(
                (ttl ushr 24).toByte(),
                (ttl ushr 16).toByte(),
                (ttl ushr 8).toByte(),
                ttl.toByte(),
            ),
        )
        output.write(byteArrayOf(0, 4))
        output.write(address)
        return output.toByteArray()
    }
}
